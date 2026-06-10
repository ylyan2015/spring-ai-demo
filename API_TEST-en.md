# Spring AI Demo API Testing Guide

## Testing Prerequisites

### 1. Start the Application

Ensure Ollama service is started (if using offline mode), then run the application:

```bash
mvn spring-boot:run
```

**Note**: By default, H2 database (file persistence) and DeepSeek model are used. For switching, please refer to README-en.md.

### 2. Authentication Notes

**Most chat endpoints require login first**. The following endpoints do not require authentication:
- `/api/auth/**` - All auth endpoints
- `/api/model/**` - Model management endpoints
- Static resources (CSS, JS, pages)

---

## I. User Authentication Endpoints

### 1.1 Get RSA Public Key

The frontend uses this public key to RSA-encrypt passwords before transmission.

**Request:**
```bash
curl http://localhost:8080/api/auth/public-key
```

**Response:**
```json
{
  "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

> **Note**: The public key is Base64-encoded SPKI format. The frontend uses Web Crypto API with `RSA-OAEP` + `SHA-256` for encryption.

---

### 1.2 Get Captcha

Generates a 5-character random alphanumeric captcha displayed on the page for user input.

**Request:**
```bash
curl http://localhost:8080/api/auth/captcha
```

**Response:**
```json
{
  "success": true,
  "captcha": "aB3xK"
}
```

> **Note**: The captcha is bound to the Session and is single-use — it expires immediately after submission. Case-insensitive.

---

### 1.3 User Registration

**Request (password must be RSA-encrypted):**

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

**Password Requirements**:
- Must contain at least one uppercase letter
- Must contain at least one lowercase letter
- Must contain at least one digit
- Length: 6-50 characters
- Must be entered twice during registration and match

**Success Response:**
```json
{
  "success": true,
  "message": "Registration successful",
  "username": "testuser"
}
```

**Failure Response (example):**
```json
{
  "success": false,
  "message": "Passwords do not match"
}
```

Common error messages:
- `Captcha error` - Captcha is incorrect or expired
- `Password must contain uppercase, lowercase letters and digits, 6-50 characters`
- `Passwords do not match`
- `Username already exists`
- `Password decryption failed, please try again`

---

### 1.4 User Login

**Request (password must be RSA-encrypted):**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "<RSA-encrypted-password>",
    "captcha": "aB3xK"
  }'
```

**Success Response:**
```json
{
  "success": true,
  "message": "Login successful",
  "username": "testuser"
}
```

**Failure Response:**
```json
{
  "success": false,
  "message": "Incorrect username or password"
}
```

---

### 1.5 Get Current Logged-in User

**Request:**
```bash
curl http://localhost:8080/api/auth/user
```

**Logged-in Response:**
```json
{
  "loggedIn": true,
  "username": "testuser"
}
```

**Not Logged-in Response:**
```json
{
  "loggedIn": false
}
```

---

### 1.6 Logout

**Request:**
```bash
curl -X POST http://localhost:8080/api/auth/logout
```

**Response:**
```json
{
  "success": true,
  "message": "Logged out successfully"
}
```

---

## II. Chat Endpoints (Login Required)

> **Note**: The following endpoints require login. Accessing them without login will return 401/403 status codes.

### 2.1 Create New Session

The session will be automatically bound to the current logged-in user.

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

### 2.2 Get All Sessions for Current User

Returns only the current logged-in user's session list, sorted by update time descending.

**Request:**
```bash
curl http://localhost:8080/api/chat/conversations
```

**Response:**
```json
{
  "success": true,
  "conversations": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "title": "What is Spring AI?...",
      "updatedAt": "2026-06-10T10:30:05"
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "title": "New Chat",
      "updatedAt": "2026-06-10T09:15:00"
    }
  ]
}
```

---

### 2.3 Send Message (POST Method - Recommended)

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

### 2.4 Send Message (GET Method - Legacy Compatibility)

**Request:**
```bash
curl "http://localhost:8080/api/chat/send?message=Hello&sessionId=your-session-id-here"
```

---

### 2.5 Get Session History

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
      "createdAt": "2026-06-10T10:30:00"
    },
    {
      "id": 2,
      "sessionId": "your-session-id-here",
      "role": "assistant",
      "content": "Hello! I am an AI assistant...",
      "messageOrder": 1,
      "createdAt": "2026-06-10T10:30:05"
    }
  ],
  "count": 2
}
```

---

### 2.6 Delete Session (Owner Only)

Users can only delete sessions they created. Attempting to delete another user's session will return an error.

**Request:**
```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id-here
```

**Success Response:**
```json
{
  "success": true,
  "message": "Session deleted"
}
```

**Failure Response (not owner):**
```json
{
  "success": false,
  "message": "No permission to delete this session"
}
```

---

## III. Model Management Endpoints (No Login Required)

### 3.1 Get Current Model

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

### 3.2 Get Available Models List

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

### 3.3 Switch Model

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

## IV. Complete Testing Workflow

### Step 1: Get RSA Public Key
```bash
curl http://localhost:8080/api/auth/public-key
# Save the publicKey value for encrypting passwords later
```

### Step 2: Get Captcha
```bash
curl http://localhost:8080/api/auth/captcha
# Record the captcha value
```

### Step 3: Register User (requires RSA-encrypted password, browser console is more convenient)

> **Tip**: Since passwords need RSA encryption, it's recommended to use the browser console (F12) or Postman's Pre-request Script to encrypt passwords.
>
> Browser console encryption example:
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

### Step 4: Create Session and Chat
```bash
# Create session (must be logged in)
SESSION_ID=$(curl -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)
echo "Session ID: $SESSION_ID"

# Send message
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"message\": \"What is Spring AI?\"
  }"

# View all sessions for current user
curl http://localhost:8080/api/chat/conversations

# View conversation history
curl http://localhost:8080/api/chat/history/$SESSION_ID | python3 -m json.tool

# Delete session (owner only)
curl -X DELETE http://localhost:8080/api/chat/conversation/$SESSION_ID
```

---

## V. Testing with Postman

**Environment Variables:**
- `base_url`: `http://localhost:8080`
- `session_id`: Obtained from create session response

**Request List:**

1. **Get Public Key**
   - Method: GET
   - URL: `{{base_url}}/api/auth/public-key`

2. **Get Captcha**
   - Method: GET
   - URL: `{{base_url}}/api/auth/captcha`

3. **Register** (requires Pre-request Script to encrypt password)
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

4. **Login** (requires Pre-request Script to encrypt password)
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
       "message": "Hello"
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

## VI. Access H2 Database Console

You can access the H2 database console through your browser to view stored data:

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (leave empty)

In the console, you can query:
```sql
-- View all users (passwords are BCrypt hashes, plaintext cannot be recovered)
SELECT id, username, created_at FROM users;

-- View all sessions (including user ID binding)
SELECT id, session_id, user_id, title, created_at FROM conversations;

-- View all sessions for a specific user
SELECT * FROM conversations WHERE user_id = 1 ORDER BY updated_at DESC;

-- View all messages for a specific session
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- Count messages per session
SELECT session_id, COUNT(*) as message_count
FROM messages
GROUP BY session_id;
```

---

## VII. Configuration Guide

### 7.1 Model Configuration

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

### 7.2 Context Length Configuration

You can adjust the following configuration in `application.yml`:

```yaml
chat:
  max-context-messages: 10  # Retain the last 10 rounds of conversation as context
```

- Increase this value to allow AI to remember longer conversation history
- Decrease this value to reduce token consumption and improve response speed

---

## VIII. Important Notes

1. **Login Requirement**: Chat-related endpoints require login first; unauthenticated requests return 401/403
2. **Session Ownership**: Each session is bound to the user who created it; users can only view and delete their own sessions
3. **Password Security**: Passwords are transmitted with RSA encryption and stored as BCrypt hashes; no plaintext passwords exist in the database
4. **Captcha**: 5-character random alphanumeric, single-use, case-insensitive
5. **Session ID Management**: Clients need to save sessionId to maintain conversation context
6. **Context Limit**: By default, the last 10 rounds of conversation are retained, excess history will be truncated
7. **Auto Title Generation**: The first message automatically generates a session title (first 20 characters)
8. **Database Persistence**: H2 uses file persistence mode, data is stored in `./data/chatdb.mv.db` file and will not be lost after application restart

---

## IX. FAQ

**Q: Why do chat endpoints return 401?**
A: You need to login first. Call `/api/auth/login` first and ensure cookies are saved.

**Q: Why doesn't the AI remember previous conversations?**
A: Ensure you're using the same sessionId. If no sessionId is provided, a new session is created each time.

**Q: How to clear conversation history?**
A: Use the delete session endpoint `DELETE /api/chat/conversation/{sessionId}`. You can only delete your own sessions.

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

**Q: Is the RSA public key the same every time?**
A: No. The RSA key pair is regenerated each time the application starts, so you need to re-fetch the public key after restarting the application.

**Q: What if I forget my password?**
A: The current version does not support password reset. You can delete the user record directly from the database and re-register: `DELETE FROM users WHERE username = 'your-username';`
