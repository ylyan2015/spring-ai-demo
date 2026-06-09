# Spring AI Demo API 测试指南

## 多轮对话功能测试

### 1. 启动应用

确保 Ollama 服务已启动，然后运行应用：

```bash
mvn spring-boot:run
```

### 2. API 接口说明

#### 2.1 创建新会话

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

#### 2.2 发送消息（POST方式 - 推荐）

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

#### 2.3 发送消息（GET方式 - 兼容旧接口）

**请求：**
```bash
curl "http://localhost:8080/api/chat/send?message=你好&sessionId=your-session-id-here"
```

---

#### 2.4 获取会话历史

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

#### 2.5 删除会话

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

1. **Create Conversation**
   - Method: POST
   - URL: `{{base_url}}/api/chat/conversation`

2. **Send Message**
   - Method: POST
   - URL: `{{base_url}}/api/chat/send`
   - Body (JSON):
     ```json
     {
       "sessionId": "{{session_id}}",
       "message": "你好"
     }
     ```

3. **Get History**
   - Method: GET
   - URL: `{{base_url}}/api/chat/history/{{session_id}}`

4. **Delete Conversation**
   - Method: DELETE
   - URL: `{{base_url}}/api/chat/conversation/{{session_id}}`

---

### 5. 访问 H2 数据库控制台

你可以通过浏览器访问 H2 数据库控制台来查看存储的数据：

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:chatdb`
- Username: `sa`
- Password: (留空)

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
4. **数据库持久化**：所有对话都存储在 H2 内存数据库中，应用重启后数据会丢失

---

### 8. 常见问题

**Q: 为什么 AI 不记得之前的对话？**
A: 确保你使用了相同的 sessionId。如果不传 sessionId，每次都会创建新会话。

**Q: 如何清空对话历史？**
A: 使用删除会话接口，或者直接在数据库中删除对应记录。

**Q: 可以修改上下文长度吗？**
A: 可以，在 application.yml 中修改 `chat.max-context-messages` 的值。
