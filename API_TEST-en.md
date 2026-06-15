# Spring AI Demo API Testing Guide

## Testing Prerequisites

### 1. Start the Application

```bash
mvn spring-boot:run
```

**Note**: By default, H2 database and DeepSeek model are used. For switching, refer to README-en.md.

### 2. Authentication Notes

**Most chat endpoints require login first**. No authentication required for:
- `/api/auth/**` - All auth endpoints
- `/api/model/**` - Model management endpoints
- `/api/timezone` - Timezone endpoint
- Static resources (CSS, JS, pages)

---

## I. User Authentication Endpoints

### 1.1 Get RSA Public Key

**Request:**
```bash
curl http://localhost:8080/api/auth/public-key
```

**Response:**
```json
{ "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..." }
```

> Base64-encoded SPKI format. Frontend uses Web Crypto API with `RSA-OAEP` + `SHA-256`.

---

### 1.2 Get Captcha

```bash
curl http://localhost:8080/api/auth/captcha
```
```json
{ "success": true, "captcha": "aB3xK" }
```

> Single-use, case-insensitive.

---

### 1.3 User Registration

**Request (RSA-encrypted password):**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "<RSA-encrypted-password>",
    "confirmPassword": "<RSA-encrypted-confirm-password>",
    "captcha": "aB3xK"
  }'
```

**Password Requirements**: uppercase + lowercase + digits, 6-50 chars, must match confirmation.

**Success Response:**
```json
{ "success": true, "message": "Registration successful", "username": "testuser" }
```

---

### 1.4 User Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{ "username": "testuser", "password": "<RSA-encrypted-password>", "captcha": "aB3xK" }'
```
```json
{ "success": true, "message": "Login successful", "username": "testuser" }
```

---

### 1.5 Get Current User

```bash
curl http://localhost:8080/api/auth/user
```
```json
{ "loggedIn": true, "username": "testuser" }
```

### 1.6 Logout

```bash
curl -X POST http://localhost:8080/api/auth/logout
```
```json
{ "success": true, "message": "Logged out successfully" }
```

---

## II. Chat Endpoints (Login Required)

### 2.1 Stream Message (SSE) ⭐ Recommended

Real-time streaming AI responses using Server-Sent Events.

**Request:**
```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "your-session-id",
    "message": "What is Spring AI?",
    "ragEnabled": true
  }'
```

**Request Parameters:**
- `sessionId`: Session ID (optional, auto-creates if omitted)
- `message`: User message
- `ragEnabled`: Enable RAG knowledge base context (default: false)

**SSE Event Stream:**
```
event: session
data: a1b2c3d4-e5f6-7890-abcd-ef1234567890

event: message
data: Spring

event: message
data: AI is

event: done
data: {"contextUsage":35}
```

**Event Types:**
- `session` - Sent at stream start, contains session ID
- `message` - Each AI response chunk (token-by-token)
- `done` - Stream complete, includes `contextUsage` percentage
- `error` - Error occurred, contains error message

---

### 2.2 Send Message (POST - Non-streaming)

```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "your-session-id", "message": "Hello", "ragEnabled": false }'
```
```json
{ "success": true, "sessionId": "your-session-id", "response": "Hello! I am an AI assistant..." }
```

---

### 2.3 Send Message (GET - Legacy)

```bash
curl "http://localhost:8080/api/chat/send?message=Hello&sessionId=your-session-id"
```

---

### 2.4 Create New Session

```bash
curl -X POST http://localhost:8080/api/chat/conversation
```
```json
{ "success": true, "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" }
```

---

### 2.5 Get All Sessions

```bash
curl http://localhost:8080/api/chat/conversations
```
```json
{
  "success": true,
  "conversations": [
    { "id": "a1b2c3d4-...", "title": "What is Spring AI?...", "updatedAt": "2026-06-15T10:30:05" }
  ]
}
```

---

### 2.6 Get Session History

```bash
curl http://localhost:8080/api/chat/history/your-session-id
```
```json
{
  "success": true, "sessionId": "your-session-id",
  "messages": [
    { "id": 1, "role": "user", "content": "Hello", "messageOrder": 0 },
    { "id": 2, "role": "assistant", "content": "Hello!...", "messageOrder": 1 }
  ],
  "count": 2
}
```

---

### 2.7 Delete Session (Owner Only)

```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id
```
```json
{ "success": true, "message": "Session deleted" }
```

---

### 2.8 Compress Context

AI summarizes older messages into a summary to free up context space.

```bash
curl -X POST http://localhost:8080/api/chat/compress/your-session-id
```
```json
{
  "success": true,
  "message": "Context compressed, 8 old messages summarized",
  "contextUsage": 25
}
```

---

### 2.9 Get Context Usage

```bash
curl http://localhost:8080/api/chat/context-usage/your-session-id
```
```json
{ "success": true, "contextUsage": 65 }
```

---

## III. Knowledge Base Endpoints (Login Required)

### 3.1 Upload Document

Supports TXT, PDF, MD formats. Max 10MB.

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/document.pdf"
```
```json
{
  "success": true,
  "message": "Document uploaded successfully",
  "document": {
    "docId": "doc-1",
    "fileName": "document.pdf",
    "chunkCount": 15,
    "fileSize": 102400,
    "uploadedAt": "2026-06-15T10:30:00"
  }
}
```

> Documents are auto-parsed by Apache Tika → chunked by TokenTextSplitter → vectorized by EmbeddingModel → stored in VectorStore.

---

### 3.2 List Documents

```bash
curl http://localhost:8080/api/documents/list
```
```json
{
  "success": true,
  "documents": [
    { "docId": "doc-1", "fileName": "doc.pdf", "chunkCount": 15, "fileSize": 102400 }
  ],
  "count": 1
}
```

---

### 3.3 Delete Document

In persistent mode, deletes from both vector store and database.

```bash
curl -X DELETE http://localhost:8080/api/documents/doc-1
```
```json
{ "success": true, "message": "Document deleted" }
```

---

## IV. Model Management Endpoints

### 4.1 Get Current Model

```bash
curl http://localhost:8080/api/model/current
```
```json
{ "success": true, "model": "deepseek", "modelName": "DeepSeek V3" }
```

### 4.2 Get Available Models

```bash
curl http://localhost:8080/api/model/available
```
```json
{
  "success": true,
  "models": [
    { "key": "ollama", "name": "Offline Mode", "description": "Locally deployed, free, privacy-safe", "type": "offline" },
    { "key": "deepseek", "name": "DeepSeek V3", "description": "Online expert mode", "type": "online" },
    { "key": "openai", "name": "OpenAI GPT", "description": "OpenAI official models", "type": "online" }
  ]
}
```

### 4.3 Switch Model

```bash
curl -X POST http://localhost:8080/api/model/switch \
  -H "Content-Type: application/json" \
  -d '{ "model": "ollama" }'
```
```json
{ "success": true, "message": "Model switched to: Offline Mode", "model": "ollama" }
```

---

### 4.4 Get Model Parameter Presets

Get user's custom parameters (temperature, topP, topK, maxTokens) for a model.

**Get preset for a model:**
```bash
curl http://localhost:8080/api/model/params/ollama
```
```json
{
  "success": true,
  "preset": {
    "id": 1, "userId": 1, "modelKey": "ollama",
    "temperature": 0.7, "maxTokens": 2048, "topP": 0.9, "topK": 40
  }
}
```

**Get all presets:**
```bash
curl http://localhost:8080/api/model/params
```

### 4.5 Save/Update Parameter Preset

```bash
curl -X POST http://localhost:8080/api/model/params/deepseek \
  -H "Content-Type: application/json" \
  -d '{ "temperature": 0.8, "maxTokens": 4096, "topP": 0.95 }'
```
```json
{
  "success": true,
  "message": "Parameters saved",
  "preset": { "id": 2, "userId": 1, "modelKey": "deepseek", "temperature": 0.8, "maxTokens": 4096, "topP": 0.95 }
}
```

> Presets are auto-applied to all subsequent conversations with that model.

---

## V. Timezone Endpoint

### 5.1 Get IP Timezone and Location

Auto-detects client timezone and coordinates based on IP.

```bash
curl http://localhost:8080/api/timezone
```
```json
{
  "success": true,
  "timezone": "America/New_York",
  "ip": "203.0.113.1",
  "latitude": 40.7128,
  "longitude": -74.006
}
```

> Local/private IPs use server system default timezone. Results cached for 24 hours.

---

## VI. Complete Testing Workflow

### Step 1: Get RSA public key and captcha
```bash
curl http://localhost:8080/api/auth/public-key
curl http://localhost:8080/api/auth/captcha
```

### Step 2: Register and login

> **Tip**: Use browser console (F12) for RSA encryption:
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

### Step 3: Create session and stream chat
```bash
# Create session
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

# Stream chat (SSE)
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"What is Spring AI?\",\"ragEnabled\":false}"

# Check context usage
curl http://localhost:8080/api/chat/context-usage/$SESSION_ID

# Compress context (when usage is high)
curl -X POST http://localhost:8080/api/chat/compress/$SESSION_ID
```

### Step 4: Upload document and use RAG
```bash
# Upload document
curl -X POST http://localhost:8080/api/documents/upload -F "file=@./test-doc.txt"

# List documents
curl http://localhost:8080/api/documents/list

# Chat with RAG enabled
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"Answer based on knowledge base\",\"ragEnabled\":true}"

# Delete document
curl -X DELETE http://localhost:8080/api/documents/doc-1
```

---

## VII. Testing with Postman

**Environment Variables:**
- `base_url`: `http://localhost:8080`
- `session_id`: From create session response

**Request List:**

| # | Name | Method | URL | Body |
|---|------|--------|-----|------|
| 1 | Get Public Key | GET | `{{base_url}}/api/auth/public-key` | - |
| 2 | Get Captcha | GET | `{{base_url}}/api/auth/captcha` | - |
| 3 | Register | POST | `{{base_url}}/api/auth/register` | JSON (encrypted password) |
| 4 | Login | POST | `{{base_url}}/api/auth/login` | JSON (encrypted password) |
| 5 | Get User | GET | `{{base_url}}/api/auth/user` | - |
| 6 | Logout | POST | `{{base_url}}/api/auth/logout` | - |
| 7 | Create Session | POST | `{{base_url}}/api/chat/conversation` | - |
| 8 | List Sessions | GET | `{{base_url}}/api/chat/conversations` | - |
| 9 | Stream Chat | POST | `{{base_url}}/api/chat/stream` | `{"sessionId":"{{session_id}}","message":"Hello","ragEnabled":false}` |
| 10 | Send Message | POST | `{{base_url}}/api/chat/send` | `{"sessionId":"{{session_id}}","message":"Hello"}` |
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

## VIII. Access H2 Database Console

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (leave empty)

```sql
-- View all users
SELECT id, username, created_at FROM users;

-- View all sessions
SELECT id, session_id, user_id, title, created_at FROM conversations;

-- View messages for a session
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- View RAG document metadata (persistent mode)
SELECT * FROM rag_document;

-- View model parameter presets
SELECT * FROM model_param_presets;
```

---

## IX. Configuration Guide

### 9.1 Model Configuration

```yaml
spring:
  profiles:
    active: deepseek,openai,h2  # Combine: ollama/openai/deepseek + h2/postgresql
```

### 9.2 RAG Configuration

```yaml
rag:
  embedding-model: ollama    # embedding: ollama or openai
  store-type: memory         # memory (in-memory) or pgvector (persistent)
  pgvector:
    dimensions: 768          # nomic-embed-text dimensions
    initialize-schema: true  # auto-create tables
```

### 9.3 Context Length

```yaml
chat:
  max-context-messages: 10   # Retain last 10 conversation rounds
```

---

## X. Important Notes

1. **Login Requirement**: Chat and document endpoints require login; returns 401/403 if unauthenticated
2. **Session Ownership**: Each session bound to its creator; users can only manage their own
3. **Password Security**: RSA-encrypted transmission, BCrypt hash storage
4. **Captcha**: 5-char random alphanumeric, single-use, case-insensitive
5. **SSE Streaming**: Use `POST /api/chat/stream` for real-time streaming responses
6. **RAG Knowledge Base**: Upload documents, then set `ragEnabled: true` in chat requests
7. **Context Limit**: Default 10 conversation rounds; use compression to free space
8. **Model Presets**: Saved per-user per-model, auto-applied during conversations
9. **File Upload**: Max 10MB, supports TXT/PDF/MD formats
10. **Database Persistence**: H2 file mode stored in `./data/chatdb.mv.db`

---

## XI. FAQ

**Q: Why do chat endpoints return 401?**
A: Login first via `/api/auth/login` and ensure cookies are saved.

**Q: How to consume SSE stream in frontend?**
A: Use `fetch` + `ReadableStream` (recommended for POST), or `EventSource` (GET only).

**Q: RAG context enhancement not working?**
A: Ensure documents are uploaded, `ragEnabled` is `true`, and the embedding model is available.

**Q: How to switch AI models?**
A: Web UI (recommended), API `POST /api/model/switch`, or config file.

**Q: Need to restart after switching models?**
A: No, switching takes effect immediately via Web UI or API.

**Q: How do model parameter presets work?**
A: Once saved, custom parameters auto-apply to all conversations with that model for the user.

**Q: Is the RSA public key the same every time?**
A: No, the RSA key pair is regenerated on each application startup.
