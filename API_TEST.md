# Spring AI Demo API 测试指南

## 测试前提

### 1. 启动应用

确保 Ollama 服务已启动（如果使用离线模式），然后运行应用：

```bash
mvn spring-boot:run
```

**注意**：默认使用 H2 数据库（文件持久化）和 DeepSeek 模型。如需切换，请参考 README.md。

### 2. 认证说明

**大部分聊天接口需要先登录**。以下接口无需登录：
- `/api/auth/**` - 所有认证接口
- `/api/model/**` - 模型管理接口
- 静态资源（CSS、JS、页面）

---

## 一、用户认证接口

### 1.1 获取 RSA 公钥

前端使用此公钥对密码进行 RSA 加密后再传输。

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

> **说明**：公钥为 Base64 编码的 SPKI 格式，前端使用 Web Crypto API 的 `RSA-OAEP` + `SHA-256` 进行加密。

---

### 1.2 获取验证码

生成5位随机字母数字验证码，显示在页面上供用户输入。

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

> **说明**：验证码与 Session 绑定，一次性使用，提交后立即失效。不区分大小写。

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

**密码要求**：
- 必须包含至少一个大写字母
- 必须包含至少一个小写字母
- 必须包含至少一个数字
- 长度 6-50 位
- 注册时需输入两遍且一致

**成功响应：**
```json
{
  "success": true,
  "message": "注册成功",
  "username": "testuser"
}
```

**失败响应（示例）：**
```json
{
  "success": false,
  "message": "两次输入的密码不一致"
}
```

常见错误信息：
- `验证码错误` - 验证码不正确或已过期
- `密码必须包含大写字母、小写字母和数字，长度6-50位`
- `两次输入的密码不一致`
- `用户名已存在`
- `密码解密失败，请重试`

---

### 1.4 用户登录

**请求（密码需 RSA 加密后传输）：**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "<RSA加密后的密码>",
    "captcha": "aB3xK"
  }'
```

**成功响应：**
```json
{
  "success": true,
  "message": "登录成功",
  "username": "testuser"
}
```

**失败响应：**
```json
{
  "success": false,
  "message": "用户名或密码错误"
}
```

---

### 1.5 获取当前登录用户

**请求：**
```bash
curl http://localhost:8080/api/auth/user
```

**已登录响应：**
```json
{
  "loggedIn": true,
  "username": "testuser"
}
```

**未登录响应：**
```json
{
  "loggedIn": false
}
```

---

### 1.6 退出登录

**请求：**
```bash
curl -X POST http://localhost:8080/api/auth/logout
```

**响应：**
```json
{
  "success": true,
  "message": "已退出登录"
}
```

---

## 二、聊天接口（需要登录）

> **注意**：以下接口需要先登录。未登录时访问会返回 401/403 状态码。

### 2.1 创建新会话

会话将自动绑定到当前登录用户。

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/conversation
```

**响应：**
```json
{
  "success": true,
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

### 2.2 获取当前用户的所有会话

仅返回当前登录用户的会话列表，按更新时间降序排列。

**请求：**
```bash
curl http://localhost:8080/api/chat/conversations
```

**响应：**
```json
{
  "success": true,
  "conversations": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "title": "什么是Spring AI？...",
      "updatedAt": "2026-06-10T10:30:05"
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "title": "新对话",
      "updatedAt": "2026-06-10T09:15:00"
    }
  ]
}
```

---

### 2.3 发送消息（POST方式 - 推荐）

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id-here",
    "message": "你好，请介绍一下自己"
  }'
```

**响应：**
```json
{
  "success": true,
  "sessionId": "your-session-id-here",
  "response": "你好！我是一个AI助手..."
}
```

---

### 2.4 发送消息（GET方式 - 兼容旧接口）

**请求：**
```bash
curl "http://localhost:8080/api/chat/send?message=你好&sessionId=your-session-id-here"
```

---

### 2.5 获取会话历史

**请求：**
```bash
curl http://localhost:8080/api/chat/history/your-session-id-here
```

**响应：**
```json
{
  "success": true,
  "sessionId": "your-session-id-here",
  "messages": [
    {
      "id": 1,
      "sessionId": "your-session-id-here",
      "role": "user",
      "content": "你好，请介绍一下自己",
      "messageOrder": 0,
      "createdAt": "2026-06-10T10:30:00"
    },
    {
      "id": 2,
      "sessionId": "your-session-id-here",
      "role": "assistant",
      "content": "你好！我是一个AI助手...",
      "messageOrder": 1,
      "createdAt": "2026-06-10T10:30:05"
    }
  ],
  "count": 2
}
```

---

### 2.6 删除会话（仅允许拥有者删除）

用户只能删除自己创建的会话，尝试删除他人会话会返回错误。

**请求：**
```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id-here
```

**成功响应：**
```json
{
  "success": true,
  "message": "会话已删除"
}
```

**失败响应（非本人会话）：**
```json
{
  "success": false,
  "message": "无权删除该会话"
}
```

---

## 三、模型管理接口（无需登录）

### 3.1 获取当前模型

**请求：**
```bash
curl http://localhost:8080/api/model/current
```

**响应：**
```json
{
  "success": true,
  "model": "ollama",
  "modelName": "离线模式"
}
```

### 3.2 获取可用模型列表

**请求：**
```bash
curl http://localhost:8080/api/model/available
```

**响应：**
```json
{
  "success": true,
  "models": [
    {
      "key": "ollama",
      "name": "离线模式",
      "description": "本地部署，免费使用，隐私安全",
      "type": "offline"
    },
    {
      "key": "deepseek",
      "name": "DeepSeek V4 Pro",
      "description": "在线专家模式，更强的推理能力",
      "type": "online"
    }
  ]
}
```

### 3.3 切换模型

**请求：**
```bash
curl -X POST http://localhost:8080/api/model/switch \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek"
  }'
```

**响应：**
```json
{
  "success": true,
  "message": "模型已切换到: DeepSeek V4 Pro",
  "model": "deepseek",
  "modelName": "DeepSeek V4 Pro"
}
```

---

## 四、完整测试流程

### 步骤1：获取 RSA 公钥
```bash
curl http://localhost:8080/api/auth/public-key
# 保存 publicKey 值，用于后续加密密码
```

### 步骤2：获取验证码
```bash
curl http://localhost:8080/api/auth/captcha
# 记录 captcha 值
```

### 步骤3：注册用户（需 RSA 加密密码，这里使用浏览器控制台更方便）

> **提示**：由于密码需要 RSA 加密，建议使用浏览器控制台（F12）或 Postman 的 Pre-request Script 来加密密码。
>
> 浏览器控制台加密示例：
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

### 步骤4：创建会话并聊天
```bash
# 创建会话（需已登录）
SESSION_ID=$(curl -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)
echo "Session ID: $SESSION_ID"

# 发送消息
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"什么是Spring AI？\"
  }"

# 查看当前用户的所有会话
curl http://localhost:8080/api/chat/conversations

# 查看对话历史
curl http://localhost:8080/api/chat/history/$SESSION_ID | python3 -m json.tool

# 删除会话（仅本人可删）
curl -X DELETE http://localhost:8080/api/chat/conversation/$SESSION_ID
```

---

## 五、使用 Postman 测试

**环境变量：**
- `base_url`: `http://localhost:8080`
- `session_id`: 从创建会话响应中获取

**请求列表：**

1. **Get Public Key**
   - Method: GET
   - URL: `{{base_url}}/api/auth/public-key`

2. **Get Captcha**
   - Method: GET
   - URL: `{{base_url}}/api/auth/captcha`

3. **Register** (需 Pre-request Script 加密密码)
   - Method: POST
   - URL: `{{base_url}}/api/auth/register`
   - Body (JSON):
     ```json
     {
       "username": "testuser",
       "password": "{{encrypted_password}}",
       "confirmPassword": "{{encrypted_confirm_password}}",
       "captcha": "captcha-value"
     }
     ```

4. **Login** (需 Pre-request Script 加密密码)
   - Method: POST
   - URL: `{{base_url}}/api/auth/login`
   - Body (JSON):
     ```json
     {
       "username": "testuser",
       "password": "{{encrypted_password}}",
       "captcha": "captcha-value"
     }
     ```

5. **Get Current User**
   - Method: GET
   - URL: `{{base_url}}/api/auth/user`

6. **Logout**
   - Method: POST
   - URL: `{{base_url}}/api/auth/logout`

7. **Create Conversation**
   - Method: POST
   - URL: `{{base_url}}/api/chat/conversation`

8. **List User Conversations**
   - Method: GET
   - URL: `{{base_url}}/api/chat/conversations`

9. **Send Message**
   - Method: POST
   - URL: `{{base_url}}/api/chat/send`
   - Body (JSON):
     ```json
     {
       "sessionId": "{{session_id}}",
       "message": "你好"
     }
     ```

10. **Get History**
    - Method: GET
    - URL: `{{base_url}}/api/chat/history/{{session_id}}`

11. **Delete Conversation**
    - Method: DELETE
    - URL: `{{base_url}}/api/chat/conversation/{{session_id}}`

12. **Get Current Model**
    - Method: GET
    - URL: `{{base_url}}/api/model/current`

13. **Switch Model**
    - Method: POST
    - URL: `{{base_url}}/api/model/switch`
    - Body (JSON): `{"model": "deepseek"}`

---

## 六、访问 H2 数据库控制台

你可以通过浏览器访问 H2 数据库控制台来查看存储的数据：

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

在控制台中，你可以查询：
```sql
-- 查看所有用户（密码为BCrypt哈希，无法还原明文）
SELECT id, username, created_at FROM users;

-- 查看所有会话（包含用户ID绑定关系）
SELECT id, session_id, user_id, title, created_at FROM conversations;

-- 查看某个用户的所有会话
SELECT * FROM conversations WHERE user_id = 1 ORDER BY updated_at DESC;

-- 查看某个会话的所有消息
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- 统计每个会话的消息数量
SELECT session_id, COUNT(*) as message_count
FROM messages
GROUP BY session_id;
```

---

## 七、配置说明

### 7.1 模型配置

在 `application.yml` 中可以切换默认模型：

```yaml
spring:
  profiles:
    active: ollama,h2  # 可选: ollama, openai, deepseek
```

或者通过 Web 界面直接切换（推荐）：
1. 打开 http://localhost:8080
2. 在左侧边栏底部选择模型
3. 切换后立即生效

### 7.2 上下文长度配置

在 `application.yml` 中可以调整以下配置：

```yaml
chat:
  max-context-messages: 10  # 保留最近10轮对话作为上下文
```

- 增加此值可以让 AI 记住更长的对话历史
- 减少此值可以降低 token 消耗，提高响应速度

---

## 八、注意事项

1. **登录要求**：聊天相关接口需要先登录，未登录会返回 401/403
2. **会话归属**：每个会话绑定到创建它的用户，用户只能查看和删除自己的会话
3. **密码安全**：密码使用 RSA 加密传输，BCrypt 哈希存储，数据库中不存在明文密码
4. **验证码**：5位随机字母数字，一次性使用，不区分大小写
5. **会话ID管理**：客户端需要保存 sessionId 以维持对话上下文
6. **上下文限制**：默认保留最近10轮对话，超出的历史会被截断
7. **自动标题生成**：第一条消息会自动生成会话标题（前20个字符）
8. **数据库持久化**：H2 使用文件持久化模式，数据存储在 `./data/chatdb.mv.db` 文件中

---

## 九、常见问题

**Q: 为什么访问聊天接口返回 401？**
A: 需要先登录。先调用 `/api/auth/login` 登录，确保 Cookie 被保存。

**Q: 为什么 AI 不记得之前的对话？**
A: 确保你使用了相同的 sessionId。如果不传 sessionId，每次都会创建新会话。

**Q: 如何清空对话历史？**
A: 使用删除会话接口 `DELETE /api/chat/conversation/{sessionId}`，仅能删除自己的会话。

**Q: 可以修改上下文长度吗？**
A: 可以，在 application.yml 中修改 `chat.max-context-messages` 的值。

**Q: 如何切换AI模型？**
A: 有三种方式：
   1. **Web界面**（推荐）：在左侧边栏底部选择模型，立即生效
   2. **API调用**：POST `/api/model/switch`，传入 `{"model": "deepseek"}`
   3. **配置文件**：修改 `application.yml` 中的 `spring.profiles.active`

**Q: 切换模型后需要重启应用吗？**
A: 不需要，通过 Web 界面或 API 切换模型后立即生效。

**Q: 支持哪些AI模型？**
A: 目前支持：
   - **离线模式**：Ollama 本地模型（qwen2.5, llama3, mistral 等）
   - **在线模式**：DeepSeek V4 Pro（需要 API Key）

**Q: RSA 公钥每次一样吗？**
A: 不一样。RSA 密钥对在应用每次启动时重新生成，所以重启应用后需要重新获取公钥。

**Q: 忘记密码怎么办？**
A: 当前版本不支持密码重置。可以直接在数据库中删除用户记录后重新注册：`DELETE FROM users WHERE username = 'your-username';`
