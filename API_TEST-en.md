# Spring AI Demo API Testing Guide

## Multi-turn Conversation Feature Testing

### 1. Start the Application

Ensure Ollama service is started (if using offline mode), then run the application:

```bash
mvn spring-boot:run
```

**Note**: By default, H2 database (file persistence) and Ollama model are used. For switching, please refer to README-en.md.

### 2. API Endpoints Description

#### 2.1 Model Management Endpoints

##### 2.1.1 Get Current Model

**Request:**
```bash
curl http://localhost:8080/api/model/current
```

**Response:**
```json
{
  "success": true,
  "model": "ollama",
  "modelName": "Offline Mode"
}
```

##### 2.1.2 Get Available Models List

**Request:**
```bash
curl http://localhost:8080/api/model/available
```

**Response:**
```json
{
  "success": true,
  "models": [
    {
      "key": "ollama",
      "name": "Offline Mode",
      "description": "Locally deployed, free to use, privacy-safe",
      "type": "offline"
    },
    {
      "key": "deepseek",
      "name": "DeepSeek V4 Pro",
      "description": "Online expert mode with stronger reasoning capabilities",
      "type": "online"
    }
  ]
}
```

##### 2.1.3 Switch Model

**Request:**
```bash
curl -X POST http://localhost:8080/api/model/switch \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek"
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Model switched to: DeepSeek V4 Pro",
  "model": "deepseek",
  "modelName": "DeepSeek V4 Pro"
}
```

---

#### 2.2 Create New Session

**Request:**
```bash
curl -X POST http://localhost:8080/api/chat/conversation
```

**Response:**
```json
{
  "success": true,
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

#### 2.3 Send Message (POST Method - Recommended)

**Request:**
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id-here",
    "message": "Hello, please introduce yourself"
  }'
```

**Response:**
```json
{
  "success": true,
  "sessionId": "your-session-id-here",
  "response": "Hello! I am an AI assistant..."
}
```

---

#### 2.4 Send Message (GET Method - Legacy Compatibility)

**Request:**
```bash
curl "http://localhost:8080/api/chat/send?message=Hello&sessionId=your-session-id-here"
```

---

#### 2.5 Get Session History

**Request:**
```bash
curl http://localhost:8080/api/chat/history/your-session-id-here
```

**Response:**
```json
{
  "success": true,
  "sessionId": "your-session-id-here",
  "messages": [
    {
      "id": 1,
      "sessionId": "your-session-id-here",
      "role": "user",
      "content": "Hello, please introduce yourself",
      "messageOrder": 0,
      "createdAt": "2026-06-08T10:30:00"
    },
    {
      "id": 2,
      "sessionId": "your-session-id-here",
      "role": "assistant",
      "content": "Hello! I am an AI assistant...",
      "messageOrder": 1,
      "createdAt": "2026-06-08T10:30:05"
    }
  ],
  "count": 2
}
```

---

#### 2.6 Delete Session

**Request:**
```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id-here
```

**Response:**
```json
{
  "success": true,
  "message": "Session deleted"
}
```

---

### 3. Multi-turn Conversation Example

Here is a complete multi-turn conversation testing workflow:

#### Step 1: Create Session
```bash
# Create new session and record the returned sessionId
SESSION_ID=$(curl -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)
echo "Session ID: $SESSION_ID"
```

#### Step 2: First Round of Conversation
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"What is Spring AI?\"
  }"
```

#### Step 3: Second Round of Conversation (Context Memory)
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"What are its main features?\"
  }"
```

> Note: The AI can understand that "it" refers to Spring AI because it remembers the previous conversation.

#### Step 4: Third Round of Conversation
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"Can you give me a usage example?\"
  }"
```

#### Step 5: View Complete Conversation History
```bash
curl http://localhost:8080/api/chat/history/$SESSION_ID | python3 -m json.tool
```

---

### 4. Testing with Postman

If you prefer using a graphical interface tool, you can import the following Postman collection:

**Environment Variables:**
- `base_url`: `http://localhost:8080`

**Request List:**

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
       "message": "Hello"
     }
     ```

5. **Get History**
   - Method: GET
   - URL: `{{base_url}}/api/chat/history/{{session_id}}`

6. **Delete Conversation**
   - Method: DELETE
   - URL: `{{base_url}}/api/chat/conversation/{{session_id}}`

---

### 5. Access H2 Database Console

You can access the H2 database console through your browser to view stored data:

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (leave empty)

**Note**: H2 uses file persistence mode, data is stored in `./data/chatdb.mv.db` file and will not be lost after application restart.

In the console, you can query:
```sql
-- View all sessions
SELECT * FROM conversations;

-- View all messages for a specific session
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- Count messages per session
SELECT session_id, COUNT(*) as message_count 
FROM messages 
GROUP BY session_id;
```

---

### 6. Configuration Guide

#### 6.1 Model Configuration

You can switch the default model in `application.yml`:

```yaml
spring:
  profiles:
    active: ollama,h2  # Options: ollama, openai, deepseek
```

Or switch directly through the web interface (recommended):
1. Open http://localhost:8080
2. Select model at the bottom of the left sidebar
3. Changes take effect immediately

#### 6.2 Context Length Configuration

You can adjust the following configuration in `application.yml`:

```yaml
chat:
  max-context-messages: 10  # Retain the last 10 rounds of conversation as context
```

- Increase this value to allow AI to remember longer conversation history
- Decrease this value to reduce token consumption and improve response speed

---

### 7. Important Notes

1. **Session ID Management**: Clients need to save sessionId to maintain conversation context
2. **Context Limit**: By default, the last 10 rounds of conversation are retained, excess history will be truncated
3. **Auto Title Generation**: The first message automatically generates a session title (first 20 characters)
4. **Database Persistence**: H2 uses file persistence mode, data is stored in `./data/chatdb.mv.db` file and will not be lost after application restart
5. **Model Switching**: Models can be switched in real-time via web interface or API without restarting the application

---

### 8. FAQ

**Q: Why doesn't the AI remember previous conversations?**
A: Ensure you're using the same sessionId. If no sessionId is provided, a new session is created each time.

**Q: How to clear conversation history?**
A: Use the delete session endpoint, or directly delete corresponding records in the database.

**Q: Can I modify the context length?**
A: Yes, modify the `chat.max-context-messages` value in application.yml.

**Q: How to switch AI models?**
A: There are three ways:
   1. **Web Interface** (Recommended): Select model at the bottom of the left sidebar, takes effect immediately
   2. **API Call**: POST `/api/model/switch`, pass `{"model": "deepseek"}`
   3. **Configuration File**: Modify `spring.profiles.active` in `application.yml`

**Q: Do I need to restart the application after switching models?**
A: No, switching models via web interface or API takes effect immediately.

**Q: Which AI models are supported?**
A: Currently supports:
   - **Offline Mode**: Ollama local models (qwen2.5, llama3, mistral, etc.)
   - **Online Mode**: DeepSeek V4 Pro (requires API Key)
