# Spring AI Demo - Multi-Model Intelligent Chat Application

An intelligent chat application example based on Spring Boot 3.4.1 and Spring AI 1.0.0, supporting multiple AI models (Ollama, OpenAI, DeepSeek, etc.) with built-in user authentication, RAG knowledge base, SSE streaming output, AI image generation (Tongyi Wanxiang), multi-storage service, Docker one-click deployment, and more.

## Project Overview

This project demonstrates how to integrate the Spring AI framework into a Spring Boot application, with flexible switching between different AI model providers, including locally deployed Ollama models and online API services (OpenAI, DeepSeek, etc.). It also provides a complete user authentication system, RAG knowledge base with document upload and context augmentation, AI image generation with multi-storage management, ensuring data privacy and security.

### ✨ Core Features

- ✅ **User Authentication** - Registration/login with random captcha verification
- ✅ **Secure Password Transfer** - RSA asymmetric encryption for password transmission, BCrypt for storage
- ✅ **Multi-turn Conversation Support** - Automatically maintains conversation context, AI remembers previous chat content
- ✅ **Session Management** - Supports creating, querying, and deleting multiple independent sessions
- ✅ **Chat Bound to Users** - Each user's chat history is isolated, only viewable and deletable by the owner
- ✅ **History Persistence** - Uses PostgreSQL/H2 database to store conversation history
- ✅ **Context Memory** - Configurable context window, retains the last N rounds of conversation
- ✅ **Context Management** - Context usage monitoring, AI-powered compression to extend conversation lifespan
- ✅ **SSE Streaming Output** - Server-Sent Events for real-time streaming AI responses, token-by-token display
- ✅ **RAG Knowledge Base** - Upload documents (TXT/PDF/MD, etc.), auto-parse, chunk, vectorize, and inject relevant context during chat
- ✅ **RAG Persistence** - Supports memory mode (testing) and PgVector persistent mode (production), seamless switching
- ✅ **Model Parameter Presets** - Each user can customize temperature, topP, topK, maxTokens per model
- ✅ **IP-based Timezone Detection** - Automatically detects client timezone and coordinates for localized weather display
- ✅ **RESTful API** - Provides complete REST API interfaces
- ✅ **Web Interface** - Beautiful chat interface with session list, real-time conversation, Markdown rendering
- ✅ **Model Switching** - Directly switch between offline mode and online expert mode in the web interface
- ✅ **Spring Boot Actuator** - Built-in health checks, metrics monitoring, and operational endpoints
- ✅ **Docker One-Click Deployment** - Multi-stage image build + docker-compose orchestration for app and middleware, ready out of the box
- ✅ **AI Response Notification** - Desktop notification + sound + page title badge, never miss a reply when switching tabs
- ✅ **AI Image Generation** - Integrated with Alibaba Cloud Bailian Tongyi Wanxiang model (wan2.6-t2i), generate high-quality images from prompts
- ✅ **Multi-Storage Support** - Unified storage abstraction layer, supports local storage / Alibaba Cloud OSS / MinIO / FastDFS with one-click switching
- ✅ **Image Generation Records** - Generation records persisted in database, associated with users and chat sessions, supports history query

## Technology Stack

- **Java 17** - Programming language
- **Spring Boot 3.4.1** - Application framework
- **Spring AI 1.0.0** - AI integration framework
- **Spring Security** - Security authentication framework
- **Spring Data JPA** - Data persistence
- **Thymeleaf** - Template engine
- **PostgreSQL + PgVector** - Database + persistent vector storage
- **H2 Database** - Lightweight embedded database (development mode)
- **AI Models**: Ollama / OpenAI / DeepSeek (switchable)
- **DashScope Tongyi Wanxiang** - AI image generation engine (wan2.6-t2i)
- **Apache Tika** - Document parsing (RAG: supports TXT/PDF/MD and more)
- **RSA + BCrypt** - Password encryption scheme
- **Lombok** - Code simplification
- **Spring Boot Actuator** - Operations monitoring
- **Aliyun OSS SDK / MinIO SDK** - Multi-storage support
- **Docker + docker-compose** - Containerized deployment and service orchestration
- **Maven** - Build tool

## Prerequisites

### Option A: Docker Deployment (Recommended, Zero Local Dependencies)

Only Docker and docker-compose required — no JDK, Maven, PostgreSQL, or other local tools needed:

1. **Docker** - Download from [https://www.docker.com/get-started](https://www.docker.com/get-started)
2. **Docker Compose** - Included with Docker Desktop

### Option B: Local Development

1. **JDK 17** or higher
2. **Maven 3.6+**
3. **AI Model Service** (choose at least one):
   - **Ollama** (local model) - Download from [https://ollama.com](https://ollama.com)
   - **OpenAI API Key** - Register at OpenAI
   - **DeepSeek API Key** - Register at DeepSeek
4. **(Optional) DashScope API Key** - For image generation, register at Alibaba Cloud Bailian Platform
5. **(Optional) PostgreSQL + pgvector extension** - For RAG persistent storage

## Quick Start

### 🐳 Docker One-Click Deployment (Recommended)

Just 3 steps to launch all services (app + PostgreSQL/pgvector + optional Ollama):

```bash
# 1. Clone the project
git clone <repository-url>
cd spring-ai-demo

# 2. Configure API Keys
cp .env.example .env
# Edit .env with your real DeepSeek / OpenAI / DashScope API Keys

# To use AI image generation, set DASHSCOPE_API_KEY (get from Alibaba Cloud Bailian Platform)

# 3. Start all services
docker compose up -d
```

Visit http://localhost:8080 after startup.

#### Enable Ollama Local Models (Optional)

```bash
docker compose --profile ollama up -d

# Pull a model (first time only)
docker exec spring-ai-ollama ollama pull qwen2.5:7b-instruct
```

#### Common Docker Commands

```bash
# Check service status
docker compose ps

# View app logs
docker compose logs -f app

# Rebuild and restart
docker compose up -d --build

# Stop all services
docker compose down

# Stop and remove volumes (reset database)
docker compose down -v
```

> **Note**: docker-compose automatically activates the `docker` Spring profile, which internally activates `postgresql`, `deepseek`, and `openai` sub-profiles, and replaces database and Ollama addresses with Docker service names.

### 💻 Local Development Setup

#### 1. Clone the Project

```bash
git clone <repository-url>
cd spring-ai-demo
```

### 2. Prepare AI Model Service

#### Option A: Using Ollama (Local Model)
```bash
# Start Ollama service
ollama serve

# Pull models in another terminal
ollama pull qwen2.5:7b-instruct
# RAG embedding model (optional)
ollama pull nomic-embed-text
```

#### Option B: Using OpenAI
```bash
export OPENAI_API_KEY=sk-your-api-key-here
```

#### Option C: Using DeepSeek
```bash
export DEEPSEEK_API_KEY=your-api-key-here
```

#### 3. Build and Run

```bash
mvn clean install
mvn spring-boot:run
```

Or directly run the main class `SpringAiDemoApplication`.

#### 4. Use Web Interface (Recommended)

After startup, access in your browser:

```
http://localhost:8080
```

#### Registration & Login

1. **Register**: Enter username, password (uppercase + lowercase + digits, 6-50 chars), confirm password, and 5-char captcha
2. **Login**: Enter username, password, and captcha

> **Security Note**: Passwords are RSA-encrypted before transmission (RSA-OAEP + SHA-256). Server stores BCrypt hashes.

#### Chat Features

- 👤 **User Info** - Current username at bottom-left, with logout support
- 📝 **Session Management** - All sessions in left sidebar, click to switch
- ➕ **New Conversation** - Click "New Chat" to create
- 💬 **Streaming Chat** - SSE real-time streaming AI output, token-by-token
- 📜 **History** - Auto-loads current session messages
- 🗑️ **Delete Session** - Owner-only deletion
- 🎨 **Markdown Rendering** - Code highlighting, tables, lists, etc.
- 🔄 **Model Switching** - Switch AI models at bottom of sidebar
- 📊 **Context Monitor** - Real-time context usage display with AI compression
- 🔔 **AI Reply Notification** - Switch tabs after sending; get notified via desktop notification + sound + title badge when AI replies

#### RAG Knowledge Base

- 📄 **Document Upload** - TXT, PDF, MD formats, max 10MB
- 📋 **Document List** - View uploaded documents and chunk counts
- 🗑️ **Document Delete** - Remove documents and their vectors
- 🔀 **Context Toggle** - Enable/disable RAG context injection

#### 5. Test with API

```bash
# Get RSA public key
curl http://localhost:8080/api/auth/public-key

# Get captcha
curl http://localhost:8080/api/auth/captcha

# Streaming chat (SSE)
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"sessionId":"your-session-id","message":"Hello","ragEnabled":false}'

# Non-streaming chat
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"your-session-id","message":"Hello"}'
```

**For detailed API documentation:** [API_TEST-en.md](API_TEST-en.md)

## Project Structure

```
spring-ai-demo/
├── src/main/java/com/github/ylyan2015/springaidemo/
│   ├── SpringAiDemoApplication.java    # Main application class
│   ├── config/
│   │   ├── AiModelConfig.java          # AI model config (dual-mode vector store)
│   │   ├── RsaKeyPairGenerator.java    # RSA key pair generator
│   │   ├── SecurityConfig.java         # Spring Security configuration
│   │   ├── StorageConfig.java          # Storage service factory (local/oss/minio/fastdfs)
│   │   ├── StorageProperties.java      # Storage configuration properties
│   │   └── WebMvcConfig.java           # Web MVC config (static resource mapping for local storage)
│   ├── controller/
│   │   ├── AuthController.java         # Auth controller (register/login/captcha)
│   │   ├── ChatController.java         # Chat controller (REST API + SSE)
│   │   ├── DocumentController.java     # Knowledge base document controller
│   │   ├── ImageController.java        # Image generation controller (REST API)
│   │   ├── ModelController.java        # Model management (switch/param presets)
│   │   ├── ModelService.java           # Model service (dynamic switch/presets)
│   │   ├── PageController.java         # Page controller (Web routing)
│   │   └── TimeZoneController.java     # Timezone controller (IP geolocation)
│   ├── service/
│   │   ├── AuthService.java            # Auth service (register/login logic)
│   │   ├── CaptchaService.java         # Captcha service
│   │   ├── ChatService.java            # Chat service (business logic/compression)
│   │   ├── ImageService.java           # Image generation service (DashScope API + storage)
│   │   └── RagService.java             # RAG service (parse/vectorize/search)
│   ├── entity/
│   │   ├── Conversation.java           # Conversation entity (bound to userId)
│   │   ├── ImageRecord.java            # Image generation record entity
│   │   ├── Message.java                # Message entity
│   │   ├── ModelParamPreset.java       # Model parameter preset entity
│   │   ├── RagDocument.java            # RAG document metadata entity (persistent)
│   │   └── User.java                   # User entity
│   ├── exception/
│   │   ├── ImageGenerationException.java # Image generation exception
│   │   └── StorageException.java         # Storage exception
│   └── repository/
│       ├── ConversationRepository.java
│       ├── ImageRecordRepository.java  # Image generation record data access
│       ├── MessageRepository.java
│       ├── ModelParamPresetRepository.java
│       ├── RagDocumentRepository.java
│       └── UserRepository.java
├── src/main/java/com/github/ylyan2015/springaidemo/dto/
│   ├── ImageGenerateRequest.java       # Image generation request DTO
│   └── ImageGenerateResponse.java      # Image generation response DTO
├── src/main/java/com/github/ylyan2015/springaidemo/storage/
│   ├── StorageService.java             # Storage service interface (unified abstraction)
│   ├── LocalStorageService.java        # Local storage implementation
│   ├── OssStorageService.java          # Alibaba Cloud OSS implementation
│   ├── MinioStorageService.java        # MinIO implementation
│   └── FastDfsStorageService.java      # FastDFS implementation (skeleton)
├── src/main/resources/
│   ├── static/css/chat.css             # Chat interface styles
│   ├── static/js/chat.js               # Frontend logic (SSE/RAG/Markdown/Notifications)
│   ├── templates/index.html            # Main chat page template
│   ├── templates/login.html            # Login/register page template
│   ├── application.yml                 # Main configuration
│   ├── application-h2.yml              # H2 database config
│   ├── application-postgresql.yml      # PostgreSQL + PgVector config
│   ├── application-ollama.yml          # Ollama model config
│   ├── application-openai.yml          # OpenAI model config
│   ├── application-deepseek.yml        # DeepSeek model config
│   └── application-docker.yml          # Docker deployment config
├── pom.xml                             # Maven configuration
├── Dockerfile                          # Docker multi-stage build
├── docker-compose.yml                  # docker-compose service orchestration
├── .env.example                        # API Key environment variable template
├── .dockerignore                       # Docker build exclusion rules
├── README.md / README-en.md            # Project documentation (Chinese/English)
└── API_TEST.md / API_TEST-en.md        # API testing guide (Chinese/English)
```

## Configuration Guide

### Web Interface Model Switching (Recommended)

1. Open http://localhost:8080
2. Select model from dropdown at bottom of sidebar:
   - **Offline Mode** - Local Ollama model, free, privacy-safe
   - **DeepSeek V3** - Online expert mode
   - **OpenAI GPT** - OpenAI official models

### Configuration File Switching

```yaml
spring:
  profiles:
    active: deepseek,openai,h2  # Combine model + database profiles
```

**Supported profiles:**
- Models: `ollama`, `openai`, `deepseek`
- Databases: `h2`, `postgresql`

### RAG Knowledge Base Configuration

```yaml
rag:
  embedding-model: ollama    # Embedding provider: ollama (local) or openai
  store-type: memory         # Vector store: memory (testing) or pgvector (production)
```

### Image Generation Configuration

Uses Alibaba Cloud Bailian DashScope Tongyi Wanxiang model for image generation. Requires API Key:

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key}  # Get from Alibaba Cloud Bailian Platform
```

> **Get API Key**: Visit [Alibaba Cloud Bailian Platform](https://bailian.console.aliyun.com/) → Model Plaza → Tongyi Wanxiang → Get API Key

**Image Generation Request Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `prompt` | string | Yes | - | Image description prompt |
| `size` | string | No | `1024x1024` | Image size (e.g. 1024x1024, 720x1280) |
| `n` | int | No | `1` | Number of images (1~4) |
| `quality` | string | No | - | Image quality (standard/hd) |
| `style` | string | No | - | Image style (realistic/illustration) |
| `sessionId` | string | No | - | Associated chat session ID |

### Storage Configuration

Generated images are automatically transferred to the configured storage service. Supports four storage modes with one-click switching:

```yaml
storage:
  type: local  # Storage type: local / oss / minio / fastdfs
  local:
    base-path: ./uploads     # Local file storage root
    access-path: /images     # Local file access path prefix
  oss:
    endpoint: ${OSS_ENDPOINT:}
    access-key-id: ${OSS_ACCESS_KEY:}
    access-key-secret: ${OSS_SECRET:}
    bucket-name: ${OSS_BUCKET:}
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET:}
    bucket-name: ${MINIO_BUCKET:ai-images}
```

| Storage Type | Config Value | Use Case |
|-------------|-------------|----------|
| Local | `local` (default) | Dev/testing, no external dependencies |
| Alibaba Cloud OSS | `oss` | Production, highly available object storage |
| MinIO | `minio` | Self-hosted object storage, S3-compatible |
| FastDFS | `fastdfs` | Legacy distributed file system |

#### RAG Storage Modes

| Mode | Config Value | Use Case | Data Persistence |
|------|-------------|----------|-----------------|
| Memory | `memory` (default) | Dev/testing | Cleared on restart |
| PgVector | `pgvector` | Production | Survives restarts |

**Switch to PgVector persistent mode:**

1. Ensure PostgreSQL has pgvector extension installed
2. Activate `postgresql` profile (auto-sets `rag.store-type: pgvector`)
3. Configure embedding dimensions (default 768 for nomic-embed-text)

```bash
# Quick start PgVector via Docker
docker run -it --rm --name postgres -p 5432:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector
```

### Complete Configuration Examples

#### Example 1: Ollama + H2 (Local Development)
```yaml
spring:
  profiles:
    active: ollama,h2
rag:
  store-type: memory
```

#### Example 2: DeepSeek + OpenAI + H2 (Online Development)
```yaml
spring:
  profiles:
    active: deepseek,openai,h2
```

#### Example 3: DeepSeek + PostgreSQL (Production)
```yaml
spring:
  profiles:
    active: deepseek,openai,postgresql
rag:
  store-type: pgvector
  pgvector:
    dimensions: 768
```

#### Example 4: Docker Deployment (auto-activated by docker-compose)
```yaml
# application-docker.yml automatically includes these profiles
spring:
  profiles:
    include: postgresql,deepseek,openai
# No manual config needed — just run docker compose up
```

## API Endpoints Overview

### Auth Endpoints (No Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/public-key` | GET | Get RSA public key |
| `/api/auth/captcha` | GET | Get 5-char random captcha |
| `/api/auth/register` | POST | User registration |
| `/api/auth/login` | POST | User login |
| `/api/auth/logout` | POST | Logout |
| `/api/auth/user` | GET | Get current user info |

### Chat Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat/stream` | POST | Stream message (SSE) |
| `/api/chat/send` | POST | Send message (non-streaming) |
| `/api/chat/send` | GET | Send message (legacy) |
| `/api/chat/conversation` | POST | Create new session |
| `/api/chat/conversations` | GET | Get all sessions |
| `/api/chat/history/{sessionId}` | GET | Get conversation history |
| `/api/chat/conversation/{sessionId}` | DELETE | Delete session (owner only) |
| `/api/chat/compress/{sessionId}` | POST | Compress context (AI summary) |
| `/api/chat/context-usage/{sessionId}` | GET | Get context usage |

### Knowledge Base Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/documents/upload` | POST | Upload document to knowledge base |
| `/api/documents/list` | GET | List all uploaded documents |
| `/api/documents/{docId}` | DELETE | Delete a document |

### Model Management Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/model/current` | GET | Get current model |
| `/api/model/available` | GET | Get available models |
| `/api/model/switch` | POST | Switch model |
| `/api/model/params` | GET | Get all param presets |
| `/api/model/params/{modelKey}` | GET | Get param preset for model |
| `/api/model/params/{modelKey}` | POST | Save/update param preset |

### Timezone Endpoint

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/timezone` | GET | Get timezone and coordinates by IP |

### Image Generation Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/image/generate` | POST | Generate image (Tongyi Wanxiang wan2.6-t2i + auto storage) |
| `/api/image/records` | GET | Get all image generation records for current user |
| `/api/image/records/{sessionId}` | GET | Get image generation records for a session |

**For detailed API documentation:** [API_TEST-en.md](API_TEST-en.md)

## Development Guide

### Multi-turn Conversation Flow

1. **User Login** → Session established
2. **Session Creation** → UUID-based, bound to user
3. **Message Storage** → Both user/AI messages persisted
4. **Context Building** → Last N rounds auto-retrieved
5. **RAG Enhancement** → Relevant knowledge base chunks injected (if enabled)
6. **AI Call** → Full history sent to model (streaming/non-streaming)
7. **Result Saving** → AI response persisted for future use

### Context Management

- **Usage Monitoring**: Real-time context usage percentage
- **AI Compression**: Summarizes older messages to free context space
- **Configurable Window**: `chat.max-context-messages` controls retention

### RAG Workflow

1. **Upload** → User uploads document via API or Web UI
2. **Parse & Chunk** → Apache Tika parses → TokenTextSplitter chunks
3. **Vectorize & Store** → EmbeddingModel generates vectors → Stored in VectorStore
4. **Search** → Top-K relevant chunks retrieved for user query
5. **Inject** → Retrieved chunks prepended as SystemMessage

### Docker Deployment Architecture

`docker-compose.yml` defines 3 services:

| Service | Image | Description |
|---------|-------|-------------|
| `app` | Local build (Dockerfile) | Spring AI app, multi-stage build, JRE 17 Alpine runtime |
| `postgres` | `pgvector/pgvector:pg16` | PostgreSQL + pgvector extension, RAG vector persistence |
| `ollama` | `ollama/ollama` | Local AI model service, optionally enabled |

Services communicate over the internal `ai-net` network. The app accesses middleware via service names (`postgres:5432`, `ollama:11434`).

- **Health Check**: postgres has a `pg_isready` health check; app waits for database readiness before starting
- **Data Persistence**: Named volumes `pgdata` and `ollama-data` ensure data survives restarts
- **Profile Control**: ollama service uses `profiles: [ollama]`, not started by default — requires `--profile ollama` to explicitly enable
- **Secure Runtime**: App runs as non-root user `appuser` inside the container

## FAQ

### 1. Ollama Connection Failed
Ensure `ollama serve` is running and models are pulled.

### 2. API Call Failed
Check API Key, network connection, and account balance.

### 3. AI Doesn't Remember Conversations
Ensure consistent `sessionId` usage across requests.

### 4. RAG Document Upload Failed
- Confirm file size ≤ 10MB
- Ensure embedding model is available: `ollama pull nomic-embed-text`

### 5. Forgot Password
Delete user from database and re-register:
```sql
DELETE FROM users WHERE username = 'your-username';
```

### 6. Slow Docker Build
The first build downloads Maven dependencies. Subsequent builds benefit from Docker layer caching (`dependency:go-offline`). For slow networks, configure a Maven mirror in the `Dockerfile` build stage:
```dockerfile
COPY settings.xml /root/.m2/settings.xml
```

### 7. Docker Container Cannot Reach External APIs
Ensure the `.env` file has valid API Keys and Docker host networking is working:
```bash
cat .env  # Confirm keys are not placeholders
```

### 8. Image Generation Failed
- Ensure `DASHSCOPE_API_KEY` is correctly configured (in `.env` or `application.yml`)
- Ensure your Alibaba Cloud Bailian account has access to Tongyi Wanxiang and sufficient credits
- Ensure network access to `dashscope.aliyuncs.com`
- Check application logs for specific error messages

### 9. Where are Generated Images Stored?
By default, images are stored locally in the `./uploads/` directory, organized by date folders.
Switch to Alibaba Cloud OSS, MinIO, or FastDFS by changing `storage.type`.
Local images are accessible via the `/images/**` static resource mapping.

### 10. How to View Image Generation History?
- API: `GET /api/image/records` to get all records for the current user
- Database: `SELECT * FROM image_records WHERE user_id = ? ORDER BY create_time DESC`

## References

- [Spring AI Official Documentation](https://spring.io/projects/spring-ai)
- [Spring Security Official Documentation](https://spring.io/projects/spring-security)
- [Ollama Official Website](https://ollama.com)
- [Spring Boot Official Documentation](https://spring.io/projects/spring-boot)

## License

This project is for learning and demonstration purposes only.

## Author

ylyan2015
