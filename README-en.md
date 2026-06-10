# Spring AI Demo - Multi-Model Intelligent Chat Application

An intelligent chat application example based on Spring Boot 3.4.1 and Spring AI 1.0.0, supporting multiple AI models (Ollama, OpenAI, DeepSeek, etc.) with built-in user registration and login system.

## Project Overview

This project demonstrates how to integrate the Spring AI framework into a Spring Boot application, with flexible switching between different AI model providers, including locally deployed Ollama models and online API services (OpenAI, DeepSeek, etc.). It also provides a complete user authentication system where chat history is bound to users, ensuring data privacy and security.

### ✨ Core Features

- ✅ **User Authentication** - Registration/login with random captcha verification
- ✅ **Secure Password Transfer** - RSA asymmetric encryption for password transmission, BCrypt for storage
- ✅ **Multi-turn Conversation Support** - Automatically maintains conversation context, AI remembers previous chat content
- ✅ **Session Management** - Supports creating, querying, and deleting multiple independent sessions
- ✅ **Chat Bound to Users** - Each user's chat history is isolated, only viewable and deletable by the owner
- ✅ **History Persistence** - Uses PostgreSQL/H2 database to store conversation history for easy reference
- ✅ **Context Memory** - Configurable context window, retains the last N rounds of conversation
- ✅ **RESTful API** - Provides complete REST API interfaces
- ✅ **Web Interface** - Beautiful chat interface with session list and real-time conversation
- ✅ **Model Switching** - Directly switch between offline mode and online expert mode in the web interface

## Technology Stack

- **Java 17** - Programming language
- **Spring Boot 3.4.1** - Application framework
- **Spring AI 1.0.0** - AI integration framework
- **Spring Security** - Security authentication framework
- **Spring Data JPA** - Data persistence
- **Thymeleaf** - Template engine
- **PostgreSQL/H2 Database** - Database (switchable)
- **AI Models**: Ollama / OpenAI / DeepSeek (switchable)
- **RSA + BCrypt** - Password encryption scheme
- **Maven** - Build tool

## Prerequisites

Before running this project, please ensure the following software is installed:

1. **JDK 17** or higher
2. **Maven 3.6+**
3. **AI Model Service** (choose at least one):
   - **Ollama** (local model) - Download and install from [https://ollama.com](https://ollama.com)
   - **OpenAI API Key** - Register an OpenAI account to obtain
   - **DeepSeek API Key** - Register a DeepSeek account to obtain

## Quick Start

### 1. Clone the Project

```bash
git clone <repository-url>
cd spring-ai-demo
```

### 2. Prepare AI Model Service

Configure according to your chosen model type:

#### Option A: Using Ollama (Local Model)
```bash
# Start Ollama service
ollama serve

# Pull model in another terminal
ollama pull qwen2.5:7b-instruct
# Or other models: ollama pull llama3, ollama pull mistral, etc.
```

#### Option B: Using OpenAI
After obtaining the API Key, set the environment variable:
```bash
export OPENAI_API_KEY=sk-your-api-key-here
```

#### Option C: Using DeepSeek
After obtaining the API Key, set the environment variable:
```bash
export DEEPSEEK_API_KEY=your-api-key-here
```

### 3. Build and Run the Application

```bash
# Build the project using Maven
mvn clean install

# Run the application
mvn spring-boot:run
```

Or directly run the main class `SpringAiDemoApplication`.

### 4. Use Web Interface (Recommended)

After the application starts, access directly in your browser:

```
http://localhost:8080
```

The system will automatically redirect to the login/registration page. Please register an account for first-time use.

#### Registration & Login

1. **Register an Account**:
   - Click the "Register" tab
   - Enter username
   - Enter password (must contain uppercase, lowercase letters and digits, 6-50 characters)
   - Re-enter password for confirmation
   - Enter the 5-character random captcha displayed on the page
   - Click the "Register" button

2. **Login**:
   - Enter username and password
   - Enter the 5-character random captcha displayed on the page
   - Click the "Login" button

> **Security Note**: Passwords are encrypted using RSA asymmetric encryption before transmission. The server stores password hashes using the BCrypt algorithm. Even if the database is compromised, plaintext passwords cannot be recovered.

#### Chat Features

The web interface provides the following features:
- 👤 **User Info** - Current username displayed at bottom-left, with logout support
- 📝 **Session Management** - All sessions for the current user displayed in the left sidebar, click to switch
- ➕ **New Conversation** - Click "New Chat" button to create a new session
- 💬 **Real-time Chat** - Input and send messages, supports Enter to send, Shift+Enter for new line
- 📜 **History** - Automatically loads current session's message history
- 🗑️ **Delete Session** - Users can only delete their own sessions
- 🎨 **Friendly Interface** - Clear message bubbles distinguishing user and AI messages
- 🔄 **Model Switching** - Directly switch AI models at the bottom of the sidebar

### 5. Test with API

If you want to test via API, please register and login first:

#### Get RSA Public Key
```bash
curl http://localhost:8080/api/auth/public-key
```

#### Get Captcha
```bash
curl http://localhost:8080/api/auth/captcha
```

#### Register User
```bash
# Password must be encrypted with RSA public key before transmission, see API_TEST-en.md for details
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"<RSA-encrypted-password>","confirmPassword":"<RSA-encrypted-password>","captcha":"captcha-code"}'
```

#### Send Message (requires login)
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id",
    "message": "Hello, please introduce yourself"
  }'
```

**For detailed API documentation, please refer to:** [API_TEST-en.md](API_TEST-en.md)

## Project Structure

```
spring-ai-demo/
├── src/main/java/com/github/ylyan2015/springaidemo/
│   ├── SpringAiDemoApplication.java    # Main application class
│   ├── config/
│   │   ├── AiModelConfig.java          # AI model configuration
│   │   ├── RsaKeyPairGenerator.java    # RSA key pair generator
│   │   └── SecurityConfig.java         # Spring Security configuration
│   ├── controller/
│   │   ├── AuthController.java         # Auth controller (register/login/captcha)
│   │   ├── ChatController.java         # Chat controller (REST API)
│   │   ├── ModelController.java        # Model management controller
│   │   └── PageController.java         # Page controller (Web routing)
│   ├── service/
│   │   ├── AuthService.java            # Auth service (register/login logic)
│   │   ├── CaptchaService.java         # Captcha service
│   │   └── ChatService.java            # Chat service (business logic)
│   ├── entity/
│   │   ├── Conversation.java           # Conversation entity (bound to userId)
│   │   ├── Message.java                # Message entity
│   │   └── User.java                   # User entity
│   └── repository/
│       ├── ConversationRepository.java # Conversation data access
│       ├── MessageRepository.java      # Message data access
│       └── UserRepository.java         # User data access
├── src/main/resources/
│   ├── static/
│   │   ├── css/
│   │   │   └── chat.css                # Chat interface styles
│   │   └── js/
│   │       └── chat.js                 # Frontend interaction logic
│   ├── templates/
│   │   ├── index.html                  # Main chat page template
│   │   └── login.html                  # Login/register page template
│   └── application.yml                 # Application configuration file
├── pom.xml                             # Maven configuration file
├── README.md / README-en.md            # Project documentation (Chinese/English)
└── API_TEST.md / API_TEST-en.md        # API testing guide (Chinese/English)
```

## User Authentication Flow

```
Registration/Login Flow:
1. Frontend fetches RSA public key (/api/auth/public-key)
2. Frontend fetches captcha (/api/auth/captcha), displays 5 random alphanumeric characters
3. User fills in the form (registration requires entering password twice, must match)
4. Frontend encrypts password with RSA public key (Web Crypto API)
5. Frontend sends encrypted password + username + captcha to backend
6. Backend validates captcha → RSA decrypts password → validates password strength → BCrypt encrypts for storage
7. On successful login/registration, HttpSession is established
```

## Configuration Guide

### Web Interface Model Switching (Recommended)

After the application starts, you can directly switch AI models in the web interface:

1. Open http://localhost:8080
2. Find the "Model" dropdown menu at the bottom of the left sidebar
3. Select the desired model:
   - **Offline Mode** - Uses local Ollama model, free, privacy-safe
   - **DeepSeek V4 Pro** - Online expert mode with stronger reasoning capabilities

Changes take effect immediately without restarting the application. A green notification appears in the top-right corner upon successful switching.

### Configuration File Model Switching

This project supports multiple AI models that can be easily switched via Spring Profile:

#### Supported Models

1. **Ollama** (Local Model) - `application-ollama.yml`
   - Models: qwen2.5:7b-instruct, llama3, mistral, gemma2, etc.
   - Advantages: Free, privacy-safe, available offline

2. **OpenAI** - `application-openai.yml`
   - Models: gpt-3.5-turbo, gpt-4, gpt-4-turbo, etc.
   - Requires API Key

3. **DeepSeek** - `application-deepseek.yml`
   - Models: deepseek-chat, deepseek-coder
   - Uses OpenAI-compatible API
   - Requires API Key

#### Switching Methods

**Method 1: Modify application.yml**

```yaml
spring:
  profiles:
    active: ollama  # Options: ollama, openai, deepseek
```

**Method 2: Specify at Startup**

```bash
# Use Ollama
mvn spring-boot:run -Dspring-boot.run.profiles=ollama

# Use OpenAI
mvn spring-boot:run -Dspring-boot.run.profiles=openai

# Use DeepSeek
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

**Method 3: Environment Variable**

```bash
export SPRING_PROFILES_ACTIVE=openai
mvn spring-boot:run
```

### Database Mode Switching

This project supports both PostgreSQL and H2 database modes, which can be switched as follows:

#### Method 1: Modify application.yml

Modify the `spring.profiles.active` value in `application.yml` (combined with model configuration):

```yaml
spring:
  profiles:
    active: ollama,h2  # Use Ollama model + H2 database
    # active: openai,postgresql  # Use OpenAI + PostgreSQL
```

**Note**: Multiple profiles can be specified simultaneously, separated by commas. The first is the model type, the second is the database type.

#### Method 2: Specify at Startup

```bash
# Use PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=ollama,postgresql

# Use H2
mvn spring-boot:run -Dspring-boot.run.profiles=ollama,h2

# Use OpenAI + PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=openai,postgresql
```

#### Method 3: Via Environment Variable

```bash
export SPRING_PROFILES_ACTIVE=deepseek,postgresql
mvn spring-boot:run
```

### PostgreSQL Configuration

If using PostgreSQL, ensure:

1. PostgreSQL service is installed and running
2. Create database: `CREATE DATABASE chatdb;`
3. Configure correct username and password in `application-postgresql.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatdb
    driver-class-name: org.postgresql.Driver
    username: postgres
    password: postgres  # Change to your password
```

### H2 Configuration

H2 uses file persistence mode, data will be saved in `./data/chatdb.mv.db` file:

- **Data Storage Location**: `data` folder in project root directory
- **H2 Console**: Access http://localhost:8080/h2-console
- **JDBC URL**: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- **Username**: `sa`
- **Password**: (leave empty)

**Advantages**:
- ✅ Data persistence, data not lost after application restart
- ✅ No need to install additional database services
- ✅ Suitable for development and small projects

### Complete Configuration Examples

#### Example 1: Ollama + H2 (Default Configuration)
```yaml
spring:
  profiles:
    active: ollama,h2
```

#### Example 2: OpenAI + PostgreSQL
```yaml
spring:
  profiles:
    active: openai,postgresql
```

#### Example 3: DeepSeek + H2
```yaml
spring:
  profiles:
    active: deepseek,h2
```

You can modify as needed:
- **Model Configuration**: Modify in corresponding `application-{model}.yml` file
  - Ollama: `base-url`, `model`
  - OpenAI: `api-key`, `model`
  - DeepSeek: `base-url`, `api-key`, `model`
- `max-context-messages`: Context memory length, controls how many rounds of conversation AI can remember
- `port`: Application running port

## API Endpoints Overview

### Auth Endpoints (No Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/public-key` | GET | Get RSA public key |
| `/api/auth/captcha` | GET | Get 5-character random captcha |
| `/api/auth/register` | POST | User registration |
| `/api/auth/login` | POST | User login |
| `/api/auth/logout` | POST | Logout |
| `/api/auth/user` | GET | Get current user info |

### Chat Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat/conversation` | POST | Create new session |
| `/api/chat/conversations` | GET | Get all sessions for current user |
| `/api/chat/send` | POST | Send message |
| `/api/chat/history/{sessionId}` | GET | Get conversation history |
| `/api/chat/conversation/{sessionId}` | DELETE | Delete session (owner only) |

### Model Management Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/model/current` | GET | Get current model |
| `/api/model/available` | GET | Get available models list |
| `/api/model/switch` | POST | Switch model |

**For detailed API documentation and examples, please refer to:** [API_TEST-en.md](API_TEST-en.md)

## Development Guide

### How Multi-turn Conversations Work

1. **User Login**: Session is established after user registers/logs in
2. **Session Creation**: Each conversation session has a unique ID (UUID), bound to the current user
3. **Message Storage**: Both user messages and AI replies are saved to the database
4. **Context Building**: When sending a new message, automatically retrieves the last N rounds of conversation history
5. **AI Call**: Sends complete conversation history to the AI model for context memory
6. **Result Saving**: AI replies are also saved to the database for future use

### Adding New AI Features

You can refer to the implementation in `ChatService`:

```java
@Service
public class MyService {
    private final ChatClient chatClient;
    
    public String processMessage(String userMessage) {
        List<Message> messages = Arrays.asList(
            new SystemMessage("You are a professional assistant"),
            new UserMessage(userMessage)
        );
        
        return chatClient.prompt()
                .messages(messages)
                .call()
                .content();
    }
}
```

## FAQ

### 1. Connection to Ollama Failed

Ensure Ollama service is running:
```bash
ollama serve
```

And confirm the specified model is downloaded:
```bash
ollama pull qwen2.5:7b-instruct
```

### 2. OpenAI/DeepSeek API Call Failed

- Check if API Key is correctly set
- Confirm network connection is normal
- Check if account balance is sufficient

### 3. Model Switching Failed

- Ensure the corresponding model service is configured (Ollama service started or API Key correct)
- Check network connection (online models require internet)
- Check console logs for detailed error information

### 4. Port Conflict

If port 8080 is occupied, you can modify the port number in `application.yml`.

### 5. AI Doesn't Remember Previous Conversations

Ensure you're using the same `sessionId`. If no sessionId is provided, a new session is created each time.

### 6. How to Switch Models?

#### Method 1: Web Interface Switching (Recommended)

Switch directly in the web interface without restarting the application:
1. Open http://localhost:8080
2. Select model at the bottom of the left sidebar
3. Changes take effect immediately

#### Method 2: Configuration File Switching

Refer to the "Configuration File Model Switching" section above. You can switch by modifying configuration files, command-line parameters, or environment variables.

### 7. How to View Data in Database?

#### H2 Database

Access H2 Console: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (leave empty)

**Note**: H2 uses file persistence mode, data is stored in `./data/chatdb.mv.db` file and will not be lost after application restart.

#### PostgreSQL Database

Connect using any PostgreSQL client tool:
- Host: `localhost`
- Port: `5432`
- Database: `chatdb`
- Username: `postgres`
- Password: `postgres` (or your configured password)

Recommended tools:
- pgAdmin
- DBeaver
- DataGrip
- psql command-line tool

### 8. Getting "Captcha Error" During Registration?

The captcha is case-insensitive, but make sure to enter it promptly after fetching. Captchas are single-use and expire after submission; please refresh to get a new one.

### 9. What If I Forget My Password?

The current version does not support password reset. You can delete the user record directly from the database and re-register:
```sql
DELETE FROM users WHERE username = 'your-username';
```

## References

- [Spring AI Official Documentation](https://spring.io/projects/spring-ai)
- [Spring Security Official Documentation](https://spring.io/projects/spring-security)
- [Ollama Official Website](https://ollama.com)
- [Spring Boot Official Documentation](https://spring.io/projects/spring-boot)

## License

This project is for learning and demonstration purposes only.

## Author

Yan Yulin
