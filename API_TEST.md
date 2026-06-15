# Spring AI Demo API 测试指南

## 测试前提

### 1. 启动应用

```bash
mvn spring-boot:run
```

**注意**：默认使用 H2 数据库和 DeepSeek 模型。如需切换，请参考 README.md。

### 2. 认证说明

**大部分聊天接口需要先登录**。以下接口无需登录：
- `/api/auth/**` - 所有认证接口
- `/api/model/**` - 模型管理接口
- `/api/timezone` - 时区接口
- 静态资源（CSS、JS、页面）

---

## 一、用户认证接口

### 1.1 获取 RSA 公钥

**请求：**
```bash
curl http://localhost:8080/api/auth/public-key
```

**响应：**
```json
{
  "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

> **说明**：Base64 编码的 SPKI 格式，前端使用 Web Crypto API 的 `RSA-OAEP` + `SHA-256` 加密。

---

### 1.2 获取验证码

**请求：**
```bash
curl http://localhost:8080/api/auth/captcha
```

**响应：**
```json
{
  "success": true,
  "captcha": "aB3xK"
}
```

> 验证码一次性使用，不区分大小写。

---

### 1.3 用户注册

**请求（密码需 RSA 加密后传输）：**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "<RSA加密后的密码>",
    "confirmPassword": "<RSA加密后的确认密码>",
    "captcha": "aB3xK"
  }'
```

**密码要求**：大写字母 + 小写字母 + 数字，6-50位，注册时需输入两遍且一致。

**成功响应：**
```json
{ "success": true, "message": "注册成功", "username": "testuser" }
```

---

### 1.4 用户登录

**请求：**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{ "username": "testuser", "password": "<RSA加密后的密码>", "captcha": "aB3xK" }'
```

**成功响应：**
```json
{ "success": true, "message": "登录成功", "username": "testuser" }
```

---

### 1.5 获取当前登录用户

```bash
curl http://localhost:8080/api/auth/user
```
```json
{ "loggedIn": true, "username": "testuser" }
```

### 1.6 退出登录

```bash
curl -X POST http://localhost:8080/api/auth/logout
```
```json
{ "success": true, "message": "已退出登录" }
```

---

## 二、聊天接口（需要登录）

### 2.1 流式发送消息（SSE）⭐ 推荐

使用 Server-Sent Events 实时流式返回 AI 回复，逐字显示。

**请求：**
```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "your-session-id",
    "message": "什么是Spring AI？",
    "ragEnabled": true
  }'
```

**请求参数：**
- `sessionId`：会话ID（可选，不传则自动创建新会话）
- `message`：用户消息
- `ragEnabled`：是否启用 RAG 知识库上下文（默认 false）

**SSE 事件流：**
```
event: session
data: a1b2c3d4-e5f6-7890-abcd-ef1234567890

event: message
data: Spring

event: message
data: AI

event: message
data: 是一个

event: done
data: {"contextUsage":35}
```

**事件类型说明：**
- `session` - 流开始时发送，包含会话ID
- `message` - 每个 AI 回复片段（逐字/逐句）
- `done` - 流结束，包含上下文使用率 `contextUsage`
- `error` - 出错时发送，包含错误信息

---

### 2.2 发送消息（POST - 非流式）

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id",
    "message": "你好，请介绍一下自己",
    "ragEnabled": false
  }'
```

**响应：**
```json
{
  "success": true,
  "sessionId": "your-session-id",
  "response": "你好！我是一个AI助手..."
}
```

---

### 2.3 发送消息（GET - 兼容旧接口）

```bash
curl "http://localhost:8080/api/chat/send?message=你好&sessionId=your-session-id"
```

---

### 2.4 创建新会话

```bash
curl -X POST http://localhost:8080/api/chat/conversation
```
```json
{ "success": true, "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" }
```

---

### 2.5 获取当前用户的所有会话

```bash
curl http://localhost:8080/api/chat/conversations
```
```json
{
  "success": true,
  "conversations": [
    { "id": "a1b2c3d4-...", "title": "什么是Spring AI？...", "updatedAt": "2026-06-15T10:30:05" }
  ]
}
```

---

### 2.6 获取会话历史

```bash
curl http://localhost:8080/api/chat/history/your-session-id
```
```json
{
  "success": true,
  "sessionId": "your-session-id",
  "messages": [
    { "id": 1, "role": "user", "content": "你好", "messageOrder": 0, "createdAt": "..." },
    { "id": 2, "role": "assistant", "content": "你好！...", "messageOrder": 1, "createdAt": "..." }
  ],
  "count": 2
}
```

---

### 2.7 删除会话（仅拥有者）

```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id
```
```json
{ "success": true, "message": "会话已删除" }
```

---

### 2.8 压缩上下文

用 AI 将较早的对话总结为摘要，释放上下文空间。

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/compress/your-session-id
```

**响应：**
```json
{
  "success": true,
  "message": "上下文已压缩，8 条旧消息已总结为摘要",
  "contextUsage": 25
}
```

---

### 2.9 获取上下文使用率

```bash
curl http://localhost:8080/api/chat/context-usage/your-session-id
```
```json
{ "success": true, "contextUsage": 65 }
```

---

## 三、知识库文档接口（需要登录）

### 3.1 上传文档到知识库

支持 TXT、PDF、MD 等格式，最大 10MB。

**请求（multipart/form-data）：**
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/document.pdf"
```

**响应：**
```json
{
  "success": true,
  "message": "文档上传成功",
  "document": {
    "docId": "doc-1",
    "fileName": "document.pdf",
    "chunkCount": 15,
    "fileSize": 102400,
    "uploadedAt": "2026-06-15T10:30:00"
  }
}
```

> **说明**：文档上传后自动经过 Apache Tika 解析 → TokenTextSplitter 分块 → EmbeddingModel 向量化 → 存入 VectorStore。

---

### 3.2 获取文档列表

```bash
curl http://localhost:8080/api/documents/list
```
```json
{
  "success": true,
  "documents": [
    { "docId": "doc-1", "fileName": "doc.pdf", "chunkCount": 15, "fileSize": 102400, "uploadedAt": "..." }
  ],
  "count": 1
}
```

---

### 3.3 删除文档

持久化模式下会同时从向量库和数据库中删除。

```bash
curl -X DELETE http://localhost:8080/api/documents/doc-1
```
```json
{ "success": true, "message": "文档已删除" }
```

---

## 四、模型管理接口

### 4.1 获取当前模型

```bash
curl http://localhost:8080/api/model/current
```
```json
{ "success": true, "model": "deepseek", "modelName": "DeepSeek V3" }
```

### 4.2 获取可用模型列表

```bash
curl http://localhost:8080/api/model/available
```
```json
{
  "success": true,
  "models": [
    { "key": "ollama", "name": "离线模式", "description": "本地部署，免费使用，隐私安全", "type": "offline" },
    { "key": "deepseek", "name": "DeepSeek V3", "description": "在线专家模式，更强的推理能力", "type": "online" },
    { "key": "openai", "name": "OpenAI GPT", "description": "OpenAI 官方模型，稳定可靠", "type": "online" }
  ]
}
```

### 4.3 切换模型

```bash
curl -X POST http://localhost:8080/api/model/switch \
  -H "Content-Type: application/json" \
  -d '{ "model": "ollama" }'
```
```json
{ "success": true, "message": "模型已切换到: 离线模式", "model": "ollama", "modelName": "离线模式" }
```

---

### 4.4 获取模型参数预设

获取当前用户对某模型的自定义参数（temperature、topP、topK、maxTokens）。

**获取某模型的参数：**
```bash
curl http://localhost:8080/api/model/params/ollama
```
```json
{
  "success": true,
  "preset": {
    "id": 1, "userId": 1, "modelKey": "ollama",
    "temperature": 0.7, "maxTokens": 2048, "topP": 0.9, "topK": 40,
    "updatedAt": "2026-06-15T10:00:00"
  }
}
```

**获取所有模型的参数：**
```bash
curl http://localhost:8080/api/model/params
```

### 4.5 保存/更新模型参数预设

```bash
curl -X POST http://localhost:8080/api/model/params/deepseek \
  -H "Content-Type: application/json" \
  -d '{
    "temperature": 0.8,
    "maxTokens": 4096,
    "topP": 0.95
  }'
```
```json
{
  "success": true,
  "message": "参数已保存",
  "preset": { "id": 2, "userId": 1, "modelKey": "deepseek", "temperature": 0.8, "maxTokens": 4096, "topP": 0.95, "topK": null }
}
```

> **说明**：参数预设保存后，后续该模型的所有对话将自动应用这些参数。

---

## 五、时区接口

### 5.1 获取IP时区和位置信息

根据客户端 IP 自动获取时区和经纬度。

```bash
curl http://localhost:8080/api/timezone
```
```json
{
  "success": true,
  "timezone": "Asia/Shanghai",
  "ip": "203.0.113.1",
  "latitude": 31.2222,
  "longitude": 121.4581
}
```

> **说明**：本地/私有 IP 使用服务器系统默认时区。结果缓存 24 小时。

---

## 六、完整测试流程

### 步骤1：获取 RSA 公钥和验证码
```bash
curl http://localhost:8080/api/auth/public-key
curl http://localhost:8080/api/auth/captcha
```

### 步骤2：注册并登录

> **提示**：密码需要 RSA 加密，建议使用浏览器控制台（F12）：
> ```javascript
> async function encryptPassword(password, publicKeyBase64) {
>     const binaryDer = atob(publicKeyBase64);
>     const derArray = new Uint8Array(binaryDer.length);
>     for (let i = 0; i < binaryDer.length; i++) derArray[i] = binaryDer.charCodeAt(i);
>     const cryptoKey = await crypto.subtle.importKey('spki', derArray.buffer,
>         { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
>     const encrypted = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, cryptoKey,
>         new TextEncoder().encode(password));
>     return btoa(String.fromCharCode(...new Uint8Array(encrypted)));
> }
> ```

### 步骤3：创建会话并流式聊天
```bash
# 创建会话
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

# 流式聊天（SSE）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"什么是Spring AI？\",\"ragEnabled\":false}"

# 查看上下文使用率
curl http://localhost:8080/api/chat/context-usage/$SESSION_ID

# 压缩上下文（当使用率过高时）
curl -X POST http://localhost:8080/api/chat/compress/$SESSION_ID
```

### 步骤4：上传文档并使用 RAG
```bash
# 上传文档
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@./test-document.txt"

# 查看文档列表
curl http://localhost:8080/api/documents/list

# 开启 RAG 聊天
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"根据知识库回答问题\",\"ragEnabled\":true}"

# 删除文档
curl -X DELETE http://localhost:8080/api/documents/doc-1
```

---

## 七、使用 Postman 测试

**环境变量：**
- `base_url`: `http://localhost:8080`
- `session_id`: 从创建会话响应中获取

**请求列表：**

| # | 名称 | 方法 | URL | Body |
|---|------|------|-----|------|
| 1 | Get Public Key | GET | `{{base_url}}/api/auth/public-key` | - |
| 2 | Get Captcha | GET | `{{base_url}}/api/auth/captcha` | - |
| 3 | Register | POST | `{{base_url}}/api/auth/register` | JSON (需加密密码) |
| 4 | Login | POST | `{{base_url}}/api/auth/login` | JSON (需加密密码) |
| 5 | Get User | GET | `{{base_url}}/api/auth/user` | - |
| 6 | Logout | POST | `{{base_url}}/api/auth/logout` | - |
| 7 | Create Session | POST | `{{base_url}}/api/chat/conversation` | - |
| 8 | List Sessions | GET | `{{base_url}}/api/chat/conversations` | - |
| 9 | Stream Chat | POST | `{{base_url}}/api/chat/stream` | `{"sessionId":"{{session_id}}","message":"你好","ragEnabled":false}` |
| 10 | Send Message | POST | `{{base_url}}/api/chat/send` | `{"sessionId":"{{session_id}}","message":"你好"}` |
| 11 | Get History | GET | `{{base_url}}/api/chat/history/{{session_id}}` | - |
| 12 | Compress Context | POST | `{{base_url}}/api/chat/compress/{{session_id}}` | - |
| 13 | Context Usage | GET | `{{base_url}}/api/chat/context-usage/{{session_id}}` | - |
| 14 | Delete Session | DELETE | `{{base_url}}/api/chat/conversation/{{session_id}}` | - |
| 15 | Upload Document | POST | `{{base_url}}/api/documents/upload` | form-data: file |
| 16 | List Documents | GET | `{{base_url}}/api/documents/list` | - |
| 17 | Delete Document | DELETE | `{{base_url}}/api/documents/doc-1` | - |
| 18 | Current Model | GET | `{{base_url}}/api/model/current` | - |
| 19 | Switch Model | POST | `{{base_url}}/api/model/switch` | `{"model":"deepseek"}` |
| 20 | Get Presets | GET | `{{base_url}}/api/model/params` | - |
| 21 | Save Preset | POST | `{{base_url}}/api/model/params/deepseek` | `{"temperature":0.8,"maxTokens":4096}` |
| 22 | Get Timezone | GET | `{{base_url}}/api/timezone` | - |

---

## 八、访问 H2 数据库控制台

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

```sql
-- 查看所有用户
SELECT id, username, created_at FROM users;

-- 查看所有会话
SELECT id, session_id, user_id, title, created_at FROM conversations;

-- 查看某个会话的所有消息
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- 查看 RAG 文档元信息（持久化模式）
SELECT * FROM rag_document;

-- 查看模型参数预设
SELECT * FROM model_param_presets;
```

---

## 九、配置说明

### 9.1 模型配置

```yaml
spring:
  profiles:
    active: deepseek,openai,h2  # 可组合: ollama/openai/deepseek + h2/postgresql
```

### 9.2 RAG 配置

```yaml
rag:
  embedding-model: ollama    # embedding: ollama 或 openai
  store-type: memory         # memory（内存）或 pgvector（持久化）
  pgvector:
    dimensions: 768          # nomic-embed-text 维度
    initialize-schema: true  # 自动创建表
```

### 9.3 上下文长度配置

```yaml
chat:
  max-context-messages: 10   # 保留最近10轮对话
```

---

## 十、注意事项

1. **登录要求**：聊天和文档接口需要先登录，未登录返回 401/403
2. **会话归属**：每个会话绑定创建用户，用户只能操作自己的会话
3. **密码安全**：RSA 加密传输，BCrypt 哈希存储
4. **验证码**：5位随机字母数字，一次性使用，不区分大小写
5. **SSE 流式**：使用 `POST /api/chat/stream` 获取实时流式回复
6. **RAG 知识库**：上传文档后，在聊天时设置 `ragEnabled: true` 启用上下文增强
7. **上下文限制**：默认保留最近10轮对话，可通过压缩功能释放空间
8. **模型参数**：参数预设按用户和模型维度保存，切换模型时自动应用
9. **文件上传**：最大 10MB，支持 TXT/PDF/MD 等格式
10. **数据库持久化**：H2 文件模式存储在 `./data/chatdb.mv.db`

---

## 十一、常见问题

**Q: 为什么聊天接口返回 401？**
A: 需要先登录，调用 `/api/auth/login` 并确保 Cookie 被保存。

**Q: SSE 流式接口如何在前端消费？**
A: 使用 `fetch` + `ReadableStream` 或 `EventSource`（仅支持GET），推荐使用 fetch POST 方式。

**Q: RAG 上下文增强没有效果？**
A: 确认已上传文档，并且 `ragEnabled` 设为 `true`。还需确认 embedding 模型可用。

**Q: 如何切换AI模型？**
A: Web界面（推荐）、API 调用 `POST /api/model/switch`、或修改配置文件。

**Q: 切换模型后需要重启吗？**
A: 不需要，通过 Web 界面或 API 切换立即生效。

**Q: 模型参数预设如何生效？**
A: 保存后，该用户在该模型下的所有对话自动应用自定义参数。

**Q: RSA 公钥每次一样吗？**
A: 不一样，每次启动应用重新生成。
