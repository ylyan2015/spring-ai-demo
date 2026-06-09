# Spring AI Demo API 测试指南

## 多轮对话功能测试

### 1. 启动应用

确保 Ollama 服务已启动（如果使用离线模式），然后运行应用：

```bash
mvn spring-boot:run
```

**注意**：默认使用 H2 数据库（文件持久化）和 Ollama 模型。如需切换，请参考 README.md。

### 2. API 接口说明

#### 2.1 模型管理接口

##### 2.1.1 获取当前模型

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

##### 2.1.2 获取可用模型列表

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

##### 2.1.3 切换模型

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

#### 2.2 创建新会话

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

#### 2.3 发送消息（POST方式 - 推荐）

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

#### 2.4 发送消息（GET方式 - 兼容旧接口）

**请求：**
```bash
curl "http://localhost:8080/api/chat/send?message=你好&sessionId=your-session-id-here"
```

---

#### 2.5 获取会话历史

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
      "createdAt": "2026-06-08T10:30:00"
    },
    {
      "id": 2,
      "sessionId": "your-session-id-here",
      "role": "assistant",
      "content": "你好！我是一个AI助手...",
      "messageOrder": 1,
      "createdAt": "2026-06-08T10:30:05"
    }
  ],
  "count": 2
}
```

---

#### 2.6 删除会话

**请求：**
```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id-here
```

**响应：**
```json
{
  "success": true,
  "message": "会话已删除"
}
```

---

### 3. 多轮对话示例

下面是一个完整的多轮对话测试流程：

#### 步骤1：创建会话
```bash
# 创建新会话，记录返回的 sessionId
SESSION_ID=$(curl -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)
echo "Session ID: $SESSION_ID"
```

#### 步骤2：第一轮对话
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"什么是Spring AI？\"
  }"
```

#### 步骤3：第二轮对话（上下文记忆）
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"它有什么主要功能？\"
  }"
```

> 注意：AI 能够理解"它"指的是 Spring AI，因为它记得上一轮对话的内容。

#### 步骤4：第三轮对话
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"能给我一个使用示例吗？\"
  }"
```

#### 步骤5：查看完整对话历史
```bash
curl http://localhost:8080/api/chat/history/$SESSION_ID | python3 -m json.tool
```

---

### 4. 使用 Postman 测试

如果你更喜欢使用图形界面工具，可以导入以下 Postman 集合：

**环境变量：**
- `base_url`: `http://localhost:8080`

**请求列表：**

1. **Get Current Model**
   - Method: GET
   - URL: `{{base_url}}/api/model/current`

2. **Switch Model**
   - Method: POST
   - URL: `{{base_url}}/api/model/switch`
   - Body (JSON):
     ```json
     {
       "model": "deepseek"
     }
     ```

3. **Create Conversation**
   - Method: POST
   - URL: `{{base_url}}/api/chat/conversation`

4. **Send Message**
   - Method: POST
   - URL: `{{base_url}}/api/chat/send`
   - Body (JSON):
     ```json
     {
       "sessionId": "{{session_id}}",
       "message": "你好"
     }
     ```

5. **Get History**
   - Method: GET
   - URL: `{{base_url}}/api/chat/history/{{session_id}}`

6. **Delete Conversation**
   - Method: DELETE
   - URL: `{{base_url}}/api/chat/conversation/{{session_id}}`

---

### 5. 访问 H2 数据库控制台

你可以通过浏览器访问 H2 数据库控制台来查看存储的数据：

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

**注意**：H2 使用文件持久化模式，数据存储在 `./data/chatdb.mv.db` 文件中，应用重启后数据不会丢失。

在控制台中，你可以查询：
```sql
-- 查看所有会话
SELECT * FROM conversations;

-- 查看某个会话的所有消息
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- 统计每个会话的消息数量
SELECT session_id, COUNT(*) as message_count 
FROM messages 
GROUP BY session_id;
```

---

### 6. 配置说明

#### 6.1 模型配置

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

#### 6.2 上下文长度配置

在 `application.yml` 中可以调整以下配置：

```yaml
chat:
  max-context-messages: 10  # 保留最近10轮对话作为上下文
```

- 增加此值可以让 AI 记住更长的对话历史
- 减少此值可以降低 token 消耗，提高响应速度

---

### 7. 注意事项

1. **会话ID管理**：客户端需要保存 sessionId 以维持对话上下文
2. **上下文限制**：默认保留最近10轮对话，超出的历史会被截断
3. **自动标题生成**：第一条消息会自动生成会话标题（前20个字符）
4. **数据库持久化**：H2 使用文件持久化模式，数据存储在 `./data/chatdb.mv.db` 文件中，应用重启后数据不会丢失
5. **模型切换**：可以通过 Web 界面或 API 实时切换模型，无需重启应用

---

### 8. 常见问题

**Q: 为什么 AI 不记得之前的对话？**
A: 确保你使用了相同的 sessionId。如果不传 sessionId，每次都会创建新会话。

**Q: 如何清空对话历史？**
A: 使用删除会话接口，或者直接在数据库中删除对应记录。

**Q: 可以修改上下文长度吗？**
A: 可以，在 application.yml 中修改 `chat.max-context-messages` 的值。

**Q: 如何切换AI模型？**
A: 有两种方式：
   1. **Web界面**（推荐）：在左侧边栏底部选择模型，立即生效
   2. **API调用**：POST `/api/model/switch`，传入 `{"model": "deepseek"}`
   3. **配置文件**：修改 `application.yml` 中的 `spring.profiles.active`

**Q: 切换模型后需要重启应用吗？**
A: 不需要，通过 Web 界面或 API 切换模型后立即生效。

**Q: 支持哪些AI模型？**
A: 目前支持：
   - **离线模式**：Ollama 本地模型（qwen2.5, llama3, mistral 等）
   - **在线模式**：DeepSeek V4 Pro（需要 API Key）
