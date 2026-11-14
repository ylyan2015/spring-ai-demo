# Spring AI Demo - Multi-Model Intelligent Chat Application

An intelligent chat application example based on Spring Boot 3.4.1 and Spring AI 1.0.0, supporting multiple AI models (Ollama, OpenAI, DeepSeek, etc.) with built-in user authentication, RAG knowledge base, SSE streaming output, AI image generation (Tongyi Wanxiang), multi-storage service, **AI Short Video Studio** (idea → storyboard → parallel image generation → BGM → FFmpeg compose with multi-ratio export), Docker one-click deployment, and more.

## Project Overview

This project demonstrates how to integrate the Spring AI framework into a Spring Boot application, with flexible switching between different AI model providers, including locally deployed Ollama models and online API services (OpenAI, DeepSeek, etc.). It also provides a complete user authentication system, RAG knowledge base with document upload and context augmentation, AI image generation with multi-storage management, and an end-to-end AI short video production pipeline, ensuring data privacy and security.

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
- ✅ **AI Short Video Studio** - Idea → LLM-generated storyboard (editable/drag-sortable) → LLM prompt enhancement (8 visual styles) → parallel image generation (SSE real-time progress) → 480p quick preview / 720p/1080p HD export
- ✅ **Multi-Ratio Video Export** - 16:9 landscape (Bilibili) / 9:16 portrait (TikTok/Douyin) / 1:1 square (Xiaohongshu); preview with watermark, formal exports without, export history traceable
- ✅ **One-Click Social Media Export** - Six platform presets (Douyin/Bilibili/Xiaohongshu/Kuaishou/WeChat Channels/YT Shorts: ratio + resolution + recommended-duration check), cover frame extraction, brand logo corner badge, batch three-platform export + ZIP package download
- ✅ **BGM Scoring** - Built-in music library (categorized by mood, 5 pre-generated royalty-free tracks) + LLM smart recommendation + custom upload; auto volume reduction, fade in/out, short-track looping
- ✅ **Subtitle System** - Narration auto-converted to timed subtitles (timeline precisely aligned with transitions); SRT standalone export and ASS hard-burn into frames; customizable style (font / size / color / outline / position); multilingual subtitles (Chinese / English / bilingual, LLM translation)
- ✅ **Video Template System** - 8 built-in style templates (product showcase / storytelling / knowledge / travel / food / emotional quotes / tech / fitness), one-click apply to generate styled scripts, save custom templates
- ✅ **Multiple Image Generation Backends** - DashScope Tongyi Wanxiang (cloud) and local Stable Diffusion / FLUX (SD WebUI protocol) switching via config, with automatic retry on failure

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
- **FFmpeg** - Video composition engine (Ken Burns effect + xfade transitions + BGM mixing + multi-ratio cropping)
- **Edge TTS** - Narration voiceover (Python CLI, optional; automatically skipped when not installed)
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
5. **(Optional) FFmpeg >= 4.3** - For video composition/export in the AI Short Video Studio (including ffprobe). Paths are configured via `video.ffmpeg-path` / `video.ffprobe-path` (overridable with `FFMPEG_PATH` / `FFPROBE_PATH` env vars), no system PATH dependency; xfade transitions need >= 4.3, BGM mixing needs >= 4.4
6. **(Optional) Edge TTS** - For short video narration: `pip install edge-tts` (automatically skipped when not installed); the Python interpreter is specified by `video.python-command` (overridable with the `PYTHON_CMD` env var)
7. **(Optional) PostgreSQL + pgvector extension** - For RAG persistent storage

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

#### AI Short Video Studio

Visit http://localhost:8080/video-studio to use it (login required):

Complete workflow: **Idea → Storyboard → Prompt Enhancement → Parallel Image Generation (SSE real-time progress) → Quick Preview / BGM Scoring / Multi-Ratio HD Export**

- 📝 **Script Management** - Enter a creative idea to generate a multi-shot storyboard; scripts are persisted for later editing, renaming, and deleting; optional video templates (8 built-in styles + custom) generate styled scripts in one click, scripts can be saved as templates
- 🎬 **Shot Editing** - Full-field editing (scene description / narration / prompt / camera movement / duration); add, delete shots and drag-sort
- 🎨 **Prompt Enhancement** - Single-shot or batch LLM optimization converting Chinese scene descriptions into high-quality English image prompts; 8 built-in visual styles (cinematic realism / anime / watercolor / cyberpunk / Pixar 3D / vintage / Chinese painting / fantasy)
- 🖼️ **Parallel Image Generation** - One-click switching between cloud Tongyi Wanxiang and local Stable Diffusion / FLUX (local models auto-apply SD1.5/SDXL/FLUX style presets); SSE real-time progress bar, automatic retry, per-shot regeneration
- ✅ **Image Quality Evaluation** (optional) - Multimodal LLM scores clarity/relevance/aesthetics (0-10); low-scoring images are automatically regenerated (<=2 retries, best score kept); falls back to rule-based evaluation without a multimodal API; quality badge shown on shot cards (enable via `image.quality.enabled`, off by default)
- 🎵 **BGM Scoring** - Built-in music library categorized by mood (uplifting / warm / tense / epic, etc., 5 pre-generated royalty-free tracks), with AI recommendation based on script theme and custom upload; automatically volume-reduced with fade in/out during export
- 📝 **Subtitle Burn/Export** - Toggle "Burn Subtitles" during export to hard-burn narration into frames (social-media mute-friendly); subtitle language selectable: Chinese / English / bilingual (LLM translation, narration audio stays Chinese); standalone SRT export and single-frame subtitle preview supported
- 📲 **Social Platform Export** - Pick Douyin/Bilibili/Xiaohongshu from the platform dropdown (spec auto-applied with duration hints); batch three-platform (9:16/16:9/1:1) parallel export with ZIP package download (covers + manifest included); one-click cover frame extraction from exported video
- 📺 **Preview & Export** - 480p quick preview (with watermark) for fast confirmation; 720p/1080p HD export supports 16:9/9:16/1:1 ratios; export history with re-download

> **Note**: Video composition requires FFmpeg (paths configured via `video.ffmpeg-path` / `video.ffprobe-path`, no PATH dependency); narration requires Edge TTS (`pip install edge-tts`, silent video when not installed); local image generation requires an SD WebUI-compatible service (default `http://127.0.0.1:7860`). Preset BGM tracks are programmatically synthesized royalty-free chord pads; regenerate them with `tools/generate-preset-bgm.ps1`, or replace with real royalty-free music named `category__name.mp3` in `data/bgm/`.

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
│   │   ├── BgmController.java          # Video studio: BGM list/recommend/upload
│   │   ├── SubtitleController.java    # Video studio: SRT export/subtitle preview
│   │   ├── SocialMediaExportController.java # Video studio: platform export/cover frame
│   │   ├── ChatController.java         # Chat controller (REST API + SSE)
│   │   ├── DocumentController.java     # Knowledge base document controller
│   │   ├── ImageController.java        # Image generation controller (REST API)
│   │   ├── ImageGenerationController.java  # Video studio: image gen/SSE progress/generator switching
│   │   ├── ImagePromptOptimizerController.java # Video studio: prompt enhancement
│   │   ├── ModelController.java        # Model management (switch/param presets)
│   │   ├── ModelService.java           # Model service (dynamic switch/presets)
│   │   ├── PageController.java         # Page controller (Web routing)
│   │   ├── StoryScriptController.java  # Video studio: storyboard/shot CRUD/reorder
│   │   ├── TimeZoneController.java     # Timezone controller (IP geolocation)
│   │   ├── VideoExportController.java  # Video studio: preview/HD export/history/ZIP package
│   │   └── VideoTemplateController.java # Video studio: template list/apply/custom (module H)
│   ├── service/
│   │   ├── AuthService.java            # Auth service (register/login logic)
│   │   ├── BgmService.java             # Video studio: BGM library/LLM recommend/upload
│   │   ├── CaptchaService.java         # Captcha service
│   │   ├── ChatService.java            # Chat service (business logic/compression)
│   │   ├── ImageService.java           # Image generation service (DashScope API + storage)
│   │   ├── ImageGenerationService.java # Video studio: parallel generation + SSE + retry + quality retry
│   │   ├── ImagePromptOptimizerService.java # Video studio: LLM prompt enhancement
│   │   ├── RagService.java             # RAG service (parse/vectorize/search)
│   │   ├── StoryScriptService.java     # Video studio: script generate/edit/reorder
│   │   ├── VideoExportService.java     # Video studio: preview/HD export/history/ZIP package
│   │   └── VideoTemplateService.java   # Video studio: template seed/apply/custom (module H)
│   ├── entity/
│   │   ├── Bgm.java                    # BGM entity (video module D)
│   │   ├── Conversation.java           # Conversation entity (bound to userId)
│   │   ├── ImagePromptOptimizer.java   # Prompt optimization record entity (module B)
│   │   ├── ImageRecord.java            # Image generation record entity
│   │   ├── Message.java                # Message entity
│   │   ├── ModelParamPreset.java       # Model parameter preset entity
│   │   ├── RagDocument.java            # RAG document metadata entity (persistent)
│   │   ├── StoryScript.java / StoryShot.java # Script/shot entities (module A)
│   │   ├── User.java                   # User entity
│   │   ├── VideoExport.java            # Video export record entity (module C)
│   │   └── VideoTemplate.java          # Video template entity (module H)
│   ├── exception/
│   │   ├── ImageGenerationException.java # Image generation exception
│   │   └── StorageException.java         # Storage exception
│   └── repository/
│       ├── BgmRepository.java          # BGM data access (module D)
│       ├── ConversationRepository.java
│       ├── ImagePromptOptimizerRepository.java # Prompt optimization data access (module B)
│       ├── ImageRecordRepository.java  # Image generation record data access
│       ├── MessageRepository.java
│       ├── ModelParamPresetRepository.java
│       ├── RagDocumentRepository.java
│       ├── StoryScriptRepository.java / StoryShotRepository.java # Script/shot data access (module A)
│       ├── UserRepository.java
│       ├── VideoExportRepository.java  # Export record data access (module C)
│       └── VideoTemplateRepository.java # Video template data access (module H)
├── src/main/java/com/github/ylyan2015/springaidemo/video/
│   ├── Storyboard.java / Shot.java    # Storyboard DTOs (Phase 1)
│   ├── VisualStyle.java               # 8 visual style presets (with negative prompts)
│   ├── PlatformPreset.java           # Social platform preset enum (six platforms)
│   ├── ImageGenerationProgress.java   # SSE progress event
│   ├── VideoScriptService.java        # Script generation (Phase 1)
│   ├── VideoComposeService.java       # FFmpeg compose + ComposeOptions (multi-ratio/watermark/BGM mix/subtitle burn)
│   ├── VideoController.java           # Legacy one-shot workflow API (kept for compat)
│   ├── subtitle/                     # Module F: subtitle system
│   │   ├── SubtitleService.java        # SRT/ASS generation + xfade-aligned timeline + multilingual translation
│   │   ├── SubtitleStyle.java         # Subtitle style (font/size/color/outline/position)
│   │   └── SubtitleCue.java           # Subtitle cue (text + start/end ms)
│   ├── quality/                      # Module J: image quality evaluation
│   │   ├── ImageQualityService.java   # Multimodal scoring + rule fallback
│   │   └── ImageQualityScore.java     # Score DTO (3 dimensions 0-10 + overall average)
│   └── generator/                     # Image generation abstraction (strategy + factory)
│       ├── ImageGenerator.java / GeneratedImage.java
│       ├── DashScopeImageGenerator.java  # Cloud Tongyi Wanxiang
│       ├── LocalSdImageGenerator.java   # Local SD WebUI / FLUX
│       ├── LocalModelStylePreset.java   # SD1.5/SDXL/FLUX style presets (module G)
│       └── ImageGeneratorFactory.java   # Config-driven + runtime switching
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
│   ├── templates/video-studio.html     # AI Short Video Studio frontend (complete workflow UI)
│   ├── application.yml                 # Main configuration
│   ├── application-h2.yml              # H2 database config
│   ├── application-postgresql.yml      # PostgreSQL + PgVector config
│   ├── application-ollama.yml          # Ollama model config
│   ├── application-openai.yml          # OpenAI model config
│   ├── application-deepseek.yml        # DeepSeek model config
│   └── application-docker.yml          # Docker deployment config
├── pom.xml                             # Maven configuration
├── tools/generate-preset-bgm.ps1       # Preset BGM generation script (FFmpeg-synthesized royalty-free music)
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

### AI Short Video Studio Configuration

Configuration for the video workflow (script → images → BGM → export) is centralized in `application.yml`:

```yaml
image:
  generator: dashscope               # Image source: dashscope (cloud) / local (local SD/FLUX)
  local:                               # Local Stable Diffusion WebUI / ComfyUI config
    base-url: http://127.0.0.1:7860    # Local service API address
    model: stable-diffusion            # Local model name
    steps: 25                          # Sampling steps
    cfg-scale: 7.0                     # Prompt guidance (CFG)
    timeout: 120                       # Local generation timeout (seconds)
    style-prefix: ""                   # Style prefix override (empty = auto built-in preset by model name: SD1.5/SDXL/FLUX)
    extra-negative-prompt: ""          # Negative prompt override (empty = auto built-in preset by model name)
  generation:
    parallelism: 3                     # Parallel generation count (avoid cloud API rate limits)
    retry-attempts: 1                 # Retry attempts on failure
    retry-delay-ms: 2000              # Retry delay (ms)
    total-timeout-seconds: 300        # Batch generation total timeout (seconds)
    default-size: 1280x720           # Default image size (16:9 landscape)
  quality:                             # Module J: image quality evaluation (off by default)
    enabled: false                     # When enabled, low-scoring images are regenerated (flow unchanged when off)
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI-compatible multimodal endpoint
    api-key: ${DASHSCOPE_API_KEY:}     # Multimodal API key (empty = rule-based fallback)
    model: qwen-vl-max                 # Multimodal evaluation model
    threshold: 6.0                     # Passing threshold (0-10)
    max-retries: 2                     # Max retries for low scores (best-scoring image kept)

video:
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}   # FFmpeg executable path (absolute path recommended on Windows, no PATH dependency; >=4.3 required)
  ffprobe-path: ${FFPROBE_PATH:ffprobe} # ffprobe path (used to probe BGM duration)
  python-command: ${PYTHON_CMD:python}  # Python interpreter running edge-tts (absolute path recommended on Windows to avoid the Microsoft Store alias stub)
  compose-timeout-minutes: 8        # Timeout per FFmpeg command (minutes); >=8 recommended for 1080p multi-shot
  watermark:                        # Social export: brand watermark
    logo-path: ""                    # Logo corner image path (transparent PNG, width <=300px recommended, bottom-right; empty = disabled)
  subtitle:                          # Module F: subtitle burning
    font-name: Microsoft YaHei       # Subtitle font (must be installed; Linux/Docker can use Source Han Sans CN)

bgm:
  library-path: ./data/bgm          # Built-in music library dir; filename format category__name.mp3
                                    # (e.g. happy__sunny_morning.mp3), categories: happy/sad/epic/calm/tense,
                                    # auto-scanned on startup; user uploads stored under uploads/ subdirectory
```

**Key Notes:**

- **Video composition requires FFmpeg**: paths are specified by `video.ffmpeg-path` / `video.ffprobe-path` (defaults fall back to `ffmpeg`/`ffprobe` in PATH; overridable via `FFMPEG_PATH`/`FFPROBE_PATH` env vars); exports fail with a clear error when missing/misconfigured. **Version >= 4.3 required** (xfade transitions), BGM mixing needs >= 4.4 (amix normalize); old builds bundled with apps like EVCapture (~4.2) lack xfade and cannot be used
- **Narration requires Edge TTS**: `pip install edge-tts`; the invocation command is specified by `video.python-command` (overridable via `PYTHON_CMD`; on Windows bare `python` may be hijacked by the Microsoft Store "app execution alias" stub, so an absolute interpreter path is recommended); automatically skipped when not installed (silent video)
- **BGM music library**: Place royalty-free tracks named `category__name.mp3` into `./data/bgm/` for auto-registration, or upload via the frontend button
- **Subtitles**: Timeline precisely aligned with xfade transitions; font must be installed (default Microsoft YaHei, configurable via `video.subtitle.font-name`); burning uses the FFmpeg subtitles filter (libass, included in common builds)
- **Export ratios**: 16:9 landscape (Bilibili) / 9:16 portrait (TikTok/Douyin) / 1:1 square (Xiaohongshu); preview is fixed 480p + watermark, formal exports 720p/1080p without watermark
- **Platform export**: Presets automatically map to 1080p resolution; cover defaults to the middle frame of the latest successful export; the logo corner badge requires `video.watermark.logo-path` and `withLogo: true` in the export request
- **Local model style presets**: Local generation auto-applies built-in SD1.5/SDXL/FLUX style prefixes and negative prompts based on `image.local.model` (FLUX gets no negative prompt); override via `image.local.style-prefix` / `image.local.extra-negative-prompt`; applies only to the local generator, cloud DashScope is unaffected
- **Image quality evaluation**: With `image.quality.enabled: true`, each image is scored by a multimodal LLM (clarity/relevance/aesthetics, 0-10); scores below `threshold` trigger regeneration (<= `max-retries` attempts, feedback merged into the negative prompt, best score kept); falls back to resolution-based rule evaluation when no API key is configured or the call fails; evaluation errors never block generation; scores are returned with shot details and SSE progress, shown as a quality badge in the frontend

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

### AI Short Video Studio Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/video/scripts/generate` | POST | Generate storyboard from a creative idea and persist it |
| `/api/video/scripts` | GET/POST | Script list / save script |
| `/api/video/scripts/{id}` | GET/PUT/DELETE | Script detail (with shots) / rename / delete |
| `/api/video/scripts/{id}/shots` | PUT/POST | Batch update shots / add shot |
| `/api/video/scripts/{id}/shots/{shotId}` | PUT/DELETE | Update shot / delete shot |
| `/api/video/scripts/{id}/shots/reorder` | PUT | Drag-sort shots |
| `/api/video/enhance-prompt` | POST | Single prompt LLM enhancement (8 styles) |
| `/api/video/enhance-prompt/script/{scriptId}` | POST | Batch enhance all shots' prompts |
| `/api/video/enhance-prompt/styles` | GET | Visual style list |
| `/api/video/enhance-prompt/history` | GET | Prompt enhancement history |
| `/api/video/images/shot/{scriptId}/{shotId}` | POST | Generate image for a single shot |
| `/api/video/images/script/{scriptId}/stream` | GET | SSE batch parallel generation (real-time progress) |
| `/api/video/images/generators` | GET | Available image generators (with availability) |
| `/api/video/images/generator` | PUT | Switch image generator at runtime |
| `/api/video/export/preview` | POST | 480p quick preview (watermarked, crf 28, optional BGM/subtitles) |
| `/api/video/export/hd` | POST | HD export (720p/1080p × 16:9/9:16/1:1, optional BGM/subtitle burning/logo) |
| `/api/video/exports/{scriptId}` | GET | Export history (with failure reasons and covers) |
| `/api/video/bgm` | GET | BGM list (built-in + user uploads) |
| `/api/video/bgm/recommend?theme=` | GET | LLM smart BGM recommendation by theme |
| `/api/video/bgm/upload` | POST | Upload custom BGM (multipart) |
| `/api/video/subtitle/generate` | POST | Generate SRT subtitle file (timeline aligned with transitions) |
| `/api/video/subtitle/preview` | POST | Render a single-frame subtitle preview image (optional style override) |
| `/api/video/export/platforms` | GET | Social platform preset list (six-platform specs) |
| `/api/video/export/platform` | POST | One-click export by platform preset (Douyin/Bilibili/Xiaohongshu/Kuaishou/WeChat Channels/YT Shorts) |
| `/api/video/cover/generate` | POST | Extract cover frame from exported video (middle frame by default, configurable time/export record) |
| `/api/video/generate` / `/api/video/compose` | POST | Legacy one-shot workflow API (kept for compat) |

> Video Studio page: http://localhost:8080/video-studio (see [API_TEST-en.md](API_TEST-en.md) Section VII)

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

### 11. How to Add Built-in BGM to the Video Studio?

Place royalty-free music files named `category__name.mp3` into the `./data/bgm/` directory
(e.g. `happy__sunny_morning.mp3`). Supported categories: `happy` / `sad` / `epic` / `calm` / `tense`.
They are auto-registered on startup. You can also click the "Upload" button in the Video Studio page
to add custom BGM (mp3/wav/ogg/m4a/flac supported).

### 12. Video Export Fails with FFmpeg Errors?

- Verify the executables pointed to by `video.ffmpeg-path` / `video.ffprobe-path` exist and run (defaults fall back to PATH): `ffmpeg -version`
- **Version >= 4.3 required** (xfade transition filter); BGM mixing needs >= 4.4 (the `amix normalize` option). Old 4.2 builds bundled with apps like EVCapture lack xfade — install a recent build (e.g. BtbN win64-gpl) and update the configured paths
- Multi-shot composition is slow by nature (1080p may take minutes); the timeout is controlled by `video.compose-timeout-minutes` (default 8)
- If narration fails, check that the interpreter pointed to by `video.python-command` has edge-tts installed (`python -m edge_tts --version`); on Windows bare `python` may be hijacked by the Microsoft Store alias stub (exit code 49) — use an absolute path

### 13. How to Use Local Stable Diffusion / FLUX for Video Frames?

1. Start a local SD WebUI (or OllamaDiffuser-compatible service) and verify it responds at `http://127.0.0.1:7860` (ComfyUI requires an SD WebUI compatibility layer/proxy; the native workflow protocol is not yet supported)
2. Set `image.generator: local` in config, or switch at runtime via the frontend "Image Generator" dropdown
3. Local generation natively supports negative prompts and auto-applies style presets by model name (SD1.5/SDXL/FLUX), giving more controllable styles with zero API cost; customize via `image.local.style-prefix` / `image.local.extra-negative-prompt`

### 14. Garbled Text or Wrong Font After Subtitle Burning?

- Subtitles default to Microsoft YaHei, which must be installed on the system; on Linux/Docker switch to an installed CJK font:
  `video.subtitle.font-name: Source Han Sans CN` (or Noto Sans CJK SC)
- Burning uses the FFmpeg subtitles filter (libass), included in common distribution builds; if libass is missing, install a full FFmpeg build
- You can also customize font/size/color/outline/position via the `style` field of `POST /api/video/subtitle/preview` and preview the result

### 15. How to Enable the Brand Logo Corner Badge and Cover Images?

- **Logo**: Place a transparent PNG (width <=300px recommended) at a local path and set `video.watermark.logo-path: D:/assets/logo.png`,
  then check "Logo" in the frontend or send `"withLogo": true` in the export request; it is silently skipped when unconfigured
- **Cover**: After a successful export, click "Cover" (result area or export history) to extract a frame; the middle frame is used by default,
  or call `POST /api/video/cover/generate` with `timeOffset` (seconds) and `exportId` to specify; the cover URL is written back to the export record

## References

- [Spring AI Official Documentation](https://spring.io/projects/spring-ai)
- [Spring Security Official Documentation](https://spring.io/projects/spring-security)
- [Ollama Official Website](https://ollama.com)
- [Spring Boot Official Documentation](https://spring.io/projects/spring-boot)

## License

This project is for learning and demonstration purposes only.

## Author

ylyan2015
