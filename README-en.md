# Spring AI Demo - Multi-Model Intelligent Chat Application

An intelligent chat application example based on Spring Boot 3.4.1 and Spring AI 1.0.0, supporting multiple AI models (Ollama, OpenAI, DeepSeek, etc.) with built-in user authentication, RAG knowledge base, SSE streaming output, AI image generation (Tongyi Wanxiang), multi-storage service, **AI Short Video Studio / Manga-Drama Pipeline** (idea → storyboard → character-consistent images → real image-to-video → multi-voice dubbing → BGM/SFX → FFmpeg compose with multi-ratio export), **Commentary Studio** (asset stitching + copyright guardrails) and **Reference Asset Library** (booru web search / LLM matching / IP-Adapter reference images), Docker one-click deployment, and more.

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
- ✅ **Spring Boot Actuator** - Built-in health checks and operational endpoints (only health/info exposed after security hardening)
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
- ✅ **Multiple Image Generation Backends** - Eight sources switchable via config or UI: DashScope Tongyi Wanxiang (default), local SD/FLUX (SD WebUI protocol), Zhipu CogView, SiliconFlow, OpenAI, Volcano Ark Seedream (the middle four share one OpenAI-compatible implementation), wan2.7 reference-image consistency, local ComfyUI SD1.5+LoRA+IP-Adapter; automatic retry on failure
- ✅ **Character Cards & Consistency** - LLM designs characters before storyboarding (name / English feature table / personality / voice / LoRA / trigger word); reference-image upload + script-level style lock; cloud wan2.7 (<=9 reference images) or local IP-Adapter (weight 0.7) locks character identity
- ✅ **Real Image-to-Video (i2v)** - Cloud wan2.7-i2v / local ComfyUI Wan2.2 (zero API cost, assets never leave the machine) dual routes; script-level continuity NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME; mixed real-clip + still-image motion compose
- ✅ **Candidate Draws & Inpainting** - Per shot image N=3 / video N=2 candidates ranked by quality score, human selection writes back the main asset; wan2.7 bbox inpaint results become candidates without overwriting the main image
- ✅ **Multi-Voice Dubbing & Post Enhancement** - Dialogue uses character-card voices, narration a documentary male voice (rate configurable); layered SFX mix (voice 100% / BGM 15% / SFX 30%); optional Real-ESRGAN upscale + RIFE interpolation checkboxes
- ✅ **Video Restyle** - Live-action footage → wan2.7-videoedit anime / American comic / watercolor style transfer, independent async tasks never touching the main pipeline
- ✅ **Reference Asset Library** - Upload / directory scan (char_/scene_ naming convention) / booru dual-source web search (three security gates + re-storage into own storage + traceable sourceNote); LLM auto-match recommendations, shot-bound reference images consumed by IP-Adapter/wan2.7 (first 2 per shot, characters first)
- ✅ **Commentary Studio** - /commentary-studio standalone page, format-separated script management, asset clip stitching + TTS pre-probe duration alignment + attribution watermark + insert ratio <=20% copyright guardrail + async HD export
- ✅ **End-Card Shots** - endCard=true skips image generation, local FFmpeg solid-color + text rendering (Chinese fontfile), zero-cost closing CTA

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
5. **(Optional) FFmpeg >= 4.3** - For video composition/export in the AI Short Video Studio (including ffprobe). Defaults to bare names on the system PATH; override with `video.ffmpeg-path` / `video.ffprobe-path` or `FFMPEG_PATH` / `FFPROBE_PATH` env vars; xfade transitions need >= 4.3, BGM mixing needs >= 4.4
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

1. **Register**: Enter username, password (uppercase + lowercase + digits, 6-50 chars), confirm password, and the 4-char image captcha
2. **Login**: Enter username, password, and the image captcha

> **Security Note**: Passwords are RSA-encrypted before transmission (RSA-OAEP + SHA-256). Server stores BCrypt hashes. The captcha answer is stored only in the session and never returned by the API; login is rate-limited to 5 failures per username+IP within 60 seconds (429 when exceeded); the session ID is rotated on successful login (session-fixation protection).

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
- 🎬 **Shot Editing** - Full-field editing (scene description / narration / prompt / camera movement / duration); add, delete shots and drag-sort; camera movement is honored (static = no motion, push-in = zoom in, pull-back = zoom out, pan = horizontal move), with zoompan supersampling to eliminate per-frame truncation jitter
- 🎨 **Prompt Enhancement** - Single-shot or batch LLM optimization converting Chinese scene descriptions into high-quality English image prompts; 8 built-in visual styles (cinematic realism / anime / watercolor / cyberpunk / Pixar 3D / vintage / Chinese painting / fantasy)
- 🖼️ **Parallel Image Generation** - Eight sources one-click switching: Tongyi Wanxiang (default) / local SD/FLUX / Zhipu CogView / SiliconFlow / OpenAI / Volcano Ark Seedream (the middle four via one OpenAI-compatible implementation; local models auto-apply SD1.5/SDXL/FLUX style presets) / wan2.7 reference-image consistency / local ComfyUI SD1.5+LoRA+IP-Adapter; SSE real-time progress bar, automatic retry, per-shot regeneration
- ✅ **Image Quality Evaluation** (optional) - Multimodal LLM scores clarity/relevance/aesthetics (0-10); low-scoring images are automatically regenerated (<=2 retries, best score kept); falls back to rule-based evaluation without a multimodal API; quality badge shown on shot cards (enable via `image.quality.enabled`, off by default)
- 🤖 **Agent Toolification** (Phase 3 slice 1) - Six core capabilities (storyboard generation / prompt enhancement / image generation / quality evaluation / video export / BGM recommendation) wrapped as Spring AI tools (FunctionToolCallback), with a tool-list introspection endpoint, laying the foundation for director-agent orchestration
- 🎬 **AI Director Chat** (Phase 3 slice 2) - ChatClient with the six tools mounted performs automatic ReAct orchestration (think → call tool → observe → continue); a built-in chat panel on the studio page; video export is protected by a human confirmation gate (frontend confirm button / explicit `confirmedTools` in the request); conversation history is isolated in memory per user + session (max 20 messages, cleared on restart)
- 🎭 **Multi-Agent Orchestration** (Phase 3 slice 3) - Four specialist agents (screenwriter / artist / audio / editor, each with its own system prompt + tool subset) delegated to the director as "specialist-as-tool"; a "one-click pipeline" runs asynchronous orchestration (submit → poll → confirm to resume) on a dedicated thread pool with explicit SecurityContext propagation; tasks are in-memory (max 100) with per-user ownership checks
- 🧑‍🎨 **Character Cards & Consistent Images** (Phase 4) - Script generation auto-produces character cards (name / English feature table / personality / voice / LoRA / trigger word); reference-image upload + script-level style lock; wan2.7 consumes <=9 reference images, local ComfyUI routes via IP-Adapter (weight 0.7) / LoRA / ControlNet templates
- 🎞️ **Real Image-to-Video** (Phase 4/5) - Cloud wan2.7-i2v or local ComfyUI Wan2.2 (zero cost); continuity modes chain the previous shot's last frame into the next shot's first frame; real clips use ffprobe-measured duration in the mixed compose
- 🎲 **Candidate Draws & Inpainting** (Phase 4) - Per-shot multi-candidate comparison modal with human selection; bbox inpaint fixes hands/faces, results become candidates
- 🎙️ **Multi-Voice Dubbing & Post** (Phase 4) - Dialogue TTS per line with character-card voices, narration documentary male voice (rate configurable); export checkboxes for SFX / upscale / interpolation
- 🖌️ **Video Restyle Panel** (Phase 4) - Upload 2-10s live-action clips → anime / American comic / watercolor restyle, independent tasks polled every 3s
- 🗂️ **Reference Asset Library Panel** (2026-11-17) - Upload / scan ingest (CHARACTER/SCENE/INSERT categories), booru web search (English tags work best; join multiple tags with `+` for AND; spaces become underscores within a single tag), checkbox ingest (own storage + attribution), LLM match recommendation chips one-click adopt, shot reference binding (first 2 consumed per shot at generation, characters first; only comfyui/wan27 generators consume reference images)
- 🔚 **End-Card Shots** (2026-11-17) - Check endCard on a shot to skip image generation; local solid-color + text rendering (follow / next-episode CTA), font via `video.endcard.font`
- 🎵 **BGM Scoring** - Built-in music library categorized by mood (uplifting / warm / tense / epic, etc., 5 pre-generated royalty-free tracks), with AI recommendation based on script theme and custom upload; automatically volume-reduced with fade in/out during export
- 📝 **Subtitle Burn/Export** - Toggle "Burn Subtitles" during export to hard-burn narration into frames (social-media mute-friendly); subtitle language selectable: Chinese / English / bilingual (LLM translation, narration audio stays Chinese); standalone SRT export and single-frame subtitle preview supported
- 📲 **Social Platform Export** - Pick Douyin/Bilibili/Xiaohongshu from the platform dropdown (spec auto-applied with duration hints); batch three-platform (9:16/16:9/1:1) parallel export with ZIP package download (covers + manifest included); one-click cover frame extraction from exported video
- 📺 **Preview & Export** - 480p quick preview (with watermark) for fast confirmation; 720p/1080p HD export supports 16:9/9:16/1:1 ratios; export history with re-download

> **Note**: Video composition requires FFmpeg (defaults to bare names on the system PATH; override via `video.ffmpeg-path` / `video.ffprobe-path` or `FFMPEG_PATH` / `FFPROBE_PATH` env vars); narration requires Edge TTS (`pip install edge-tts`, silent video when not installed; interpreter via `video.python-command` / `PYTHON_CMD`); local image generation supports two routes: SD WebUI-compatible services (default `http://127.0.0.1:7860`) and the native ComfyUI workflow protocol (default `http://127.0.0.1:8188`, calibrate per docs/comfyui/ templates); for commentary/review creation use the standalone entry http://localhost:8080/commentary-studio (Commentary Studio). Preset BGM tracks are programmatically synthesized royalty-free chord pads; regenerate them with `tools/generate-preset-bgm.ps1`, or replace with real royalty-free music named `category__name.mp3` in `data/bgm/`.

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
│   │   ├── WebMvcConfig.java           # Web MVC config (static resource mapping for local storage)
│   │   ├── ImageGeneratorConfig.java   # Image generator wiring (eight sources + wan27/comfyui reference capability)
│   │   ├── VideoGeneratorConfig.java   # Video generator wiring (none/dashscope-wan/comfyui)
│   │   └── BooruConfig.java            # booru web-search HttpClient (redirects disabled) + provider registry
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
│   │   ├── VideoTemplateController.java # Video studio: template list/apply/custom (module H)
│   │   ├── VideoAgentController.java   # Video studio: agent introspection / director chat / multi-agent orchestration (Phase 3 slices 1/2/3)
│   │   ├── StoryCharacterController.java # Video studio: character card CRUD / reference image / voice list (Phase 4)
│   │   ├── ShotCandidateController.java # Video studio: candidate draws / selection / inpainting (Phase 4)
│   │   ├── VideoGenerationController.java # Video studio: real i2v single-shot / SSE / generator switching (Phase 4/5)
│   │   ├── VideoRestyleController.java # Video studio: restyle independent tasks (Phase 4)
│   │   ├── MediaAssetController.java   # Video studio: asset upload / scan / list / delete (Track B + asset library)
│   │   ├── BooruAssetController.java   # Video studio: booru search / preview proxy / ingest (asset library)
│   │   └── AssetMatchController.java   # Video studio: LLM asset match recommendations (asset library)
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
│   │   ├── VideoExportService.java     # Video studio: preview/HD export/history/ZIP package/async export
│   │   ├── VideoTemplateService.java   # Video studio: template seed/apply/custom (module H)
│   │   ├── StoryCharacterService.java  # Video studio: character cards / reference images / voices (Phase 4)
│   │   ├── ShotCandidateService.java / CandidateGenerationService.java # Video studio: candidate selection / draw generation (Phase 4)
│   │   ├── VideoGenerationService.java # Video studio: real i2v orchestration / continuity / last-frame write-back (Phase 4/5)
│   │   ├── VideoRestyleService.java    # Video studio: restyle async tasks (Phase 4)
│   │   ├── SfxService.java             # Video studio: SFX library / LLM recommend / layered mix (Phase 4)
│   │   ├── MediaAssetService.java      # Video studio: asset upload / trim / dual-dir scan (Track B + asset library)
│   │   ├── BooruSearchService.java     # Video studio: booru three gates / preview proxy / re-storage ingest (asset library)
│   │   ├── AssetMatchService.java      # Video studio: LLM asset matching + keyword fallback (asset library)
│   │   └── LoginRateLimiter.java       # Login rate limiting (5 failures / 60s per username+IP)
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
│   │   ├── VideoTemplate.java          # Video template entity (module H)
│   │   ├── StoryCharacter.java         # Character card entity (voice/LoRA/trigger word/reference image, Phase 4)
│   │   ├── ShotCandidate.java          # Candidate draw entity (Phase 4)
│   │   ├── RestyleTask.java            # Video restyle task entity (Phase 4)
│   │   ├── SfxTrack.java               # SFX track entity (Phase 4)
│   │   └── MediaAsset.java             # Asset library entity (kind/category/sourceNote, Track B + asset library)
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
│   ├── Storyboard.java / Shot.java    # Storyboard DTOs (Phase 1; Shot carries dialogue/shot size/endCard/reference asset fields)
│   ├── VisualStyle.java               # 8 visual style presets (with negative prompts)
│   ├── PlatformPreset.java           # Social platform preset enum (six platforms)
│   ├── ImageGenerationProgress.java   # SSE progress event
│   ├── CharacterDesign.java / TtsLine.java # Character design DTO / per-line TTS DTO (Phase 4)
│   ├── ShotPromptAssembler.java       # Single prompt assembly point (style lock/shot size/character features/trigger words, Phase 4/5)
│   ├── VideoScriptService.java        # Script generation (Phase 1; characters before storyboard)
│   ├── VideoComposeService.java       # FFmpeg compose + ComposeOptions (multi-ratio/watermark/BGM mix/subtitle burn)
│   ├── subtitle/                     # Module F: subtitle system
│   │   ├── SubtitleService.java        # SRT/ASS generation + xfade-aligned timeline + multilingual translation
│   │   ├── SubtitleStyle.java         # Subtitle style (font/size/color/outline/position)
│   │   └── SubtitleCue.java           # Subtitle cue (text + start/end ms)
│   ├── quality/                      # Module J: image quality evaluation
│   │   ├── ImageQualityService.java   # Multimodal scoring + rule fallback
│   │   └── ImageQualityScore.java     # Score DTO (3 dimensions 0-10 + overall average)
│   ├── agent/                        # Phase 3 slices 1/2/3: toolification + director + multi-agent orchestration
│   │   ├── AgentToolRequests.java     # Tool input records (@ToolParam descriptions, incl. DelegateTask)
│   │   ├── VideoAgentToolFactory.java # FunctionToolCallback wrappers over existing services
│   │   ├── ConfirmationGateToolCallback.java # Human confirmation gate (blocks unconfirmed exportVideo)
│   │   ├── DirectorAgentService.java  # Director agent (ChatClient + tools ReAct + session history)
│   │   ├── SpecialistAgentFactory.java # Four specialist agents + delegateToXxx tools (slice 3)
│   │   └── AgentOrchestrationService.java # Async orchestration (SecurityContext propagation + task registry + confirm resume)
│   └── generator/                     # Image/video generation abstraction (strategy + factory)
│       ├── ImageGenerator.java / GeneratedImage.java / VideoGenerator.java / GeneratedVideo.java
│       ├── DashScopeImageGenerator.java  # Cloud Tongyi Wanxiang
│       ├── Wan27ImageGenerator.java   # wan2.7 reference consistency / bbox inpaint (Phase 4)
│       ├── OpenAiCompatibleImageGenerator.java # Generic impl for Zhipu/SiliconFlow/OpenAI/Ark
│       ├── LocalSdImageGenerator.java   # Local SD WebUI / FLUX
│       ├── LocalModelStylePreset.java   # SD1.5/SDXL/FLUX style presets (module G)
│       ├── ComfyUiClient.java / ComfyUiImageGenerator.java # Local ComfyUI SD1.5+LoRA+IP-Adapter (Phase 5)
│       ├── ComfyUiVideoGenerator.java / DashScopeWanVideoGenerator.java / NoneVideoGenerator.java # Three i2v routes
│       ├── EndCardImageRenderer.java    # End-card local text rendering (2026-11-17)
│       ├── ReferenceImageResolver.java / LoraResolver.java / LoraSpec.java # Reference fallback chain / LoRA routing
│       ├── BooruProvider.java / SafebooruOrgProvider.java / DanbooruSafeProvider.java # booru dual-source adapters
│       ├── ImageSizeMapper.java         # Aspect ratio → per-provider size enum mapping
│       └── ImageGeneratorFactory.java / VideoGeneratorFactory.java # Config-driven + runtime switching
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
│   ├── templates/video-studio.html     # AI Short Video Studio frontend (full workflow + asset library/character/candidate/restyle panels)
│   ├── templates/commentary-studio.html # Commentary Studio frontend (Track B: asset stitching/attribution watermark/ratio badge)
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
  embedding-model: ollama    # Embedding provider: ollama (local) / openai / dashscope (Tongyi cloud); config + restart
  # Cloud vectorization (embedding-model=dashscope): DeepSeek offers no embedding API;
  # use dashscope (reuses DASHSCOPE_API_KEY) to run online mode without local Ollama;
  # vectors from different providers are incompatible — clear old vectors and re-upload after switching
  dashscope:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v4
    dimensions: 768          # must match rag.pgvector.dimensions (pgvector mode)
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
  generator: dashscope               # Image source: dashscope (default) / local / zhipu / siliconflow / openai / ark
  # Four OpenAI-compatible images providers (one generic implementation): keys via env vars (empty = unavailable in UI);
  # negative prompts appended to prompt (protocol has no negative param); sizes mapped by aspect ratio to each
  # provider's enum (empty fixed-sizes = pass-through); url/b64_json responses auto-handled, temp URLs downloaded and re-stored
  compat:
    zhipu:
      api-key: ${ZHIPU_API_KEY:}        # Zhipu BigModel; cogview-3-flash free tier
      model: cogview-3-flash
    siliconflow:
      api-key: ${SILICONFLOW_API_KEY:}  # SiliconFlow; Kolors free tier / FLUX.1-schnell
      model: Kwai-Kolors/Kolors
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: gpt-image-1
    ark:
      api-key: ${ARK_API_KEY:}          # Volcano Ark; Seedream strong Chinese semantics/text rendering
      model: doubao-seedream-4-0-250828
  local:                               # Local Stable Diffusion WebUI / ComfyUI config
    base-url: http://127.0.0.1:7860    # Local service API address
    model: stable-diffusion            # Local model name
    steps: 25                          # Sampling steps
    cfg-scale: 7.0                     # Prompt guidance (CFG)
    timeout: 120                       # Local generation timeout (seconds)
    style-prefix: ""                   # Style prefix override (empty = auto built-in preset by model name: SD1.5/SDXL/FLUX)
    extra-negative-prompt: ""          # Negative prompt override (empty = auto built-in preset by model name)
  wan27:                               # Phase 4: wan2.7 reference-image consistency (reuses DASHSCOPE_API_KEY, empty key = unavailable)
    model: wan2.7-image                # <=9 reference images; bbox inpaint uses the same model
    timeout-seconds: 120
  comfyui:                             # Phase 5: local ComfyUI SD1.5+LoRA+IP-Adapter storyboards (zero cloud, measured ~8s/image)
    workflow-plain: docs/comfyui/sd15_storyboard.template.json          # Plain prompt template for LoRA-less characters
    workflow-lora: docs/comfyui/sd15_lora_storyboard.template.json      # Character LoRA template
    workflow-canny: docs/comfyui/sd15_lora_canny.template.json          # ControlNet camera lock (gated)
    workflow-ipadapter: docs/comfyui/sd15_ipadapter.template.json       # Reference-image template (IP-Adapter plus_sd15 + ViT-H, calibrate locally)
    workflow-ipadapter-lora: docs/comfyui/sd15_ipadapter_lora.template.json  # IP-Adapter+LoRA combo (manual calibration needed, auto-degrades when absent)
    ipadapter-weight: 0.7              # Reference strength sweet spot 0.65-0.85; too high copies the reference like a sticker
    controlnet-enabled: false          # When enabled, character reference goes through canny camera lock (+~10s/image)
    timeout-seconds: 120               # base-url reuses video.comfyui.base-url
    poll-interval-ms: 1000
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
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}   # FFmpeg executable (bare name via PATH by default; absolute path when not on PATH; >=4.3 required)
  ffprobe-path: ${FFPROBE_PATH:ffprobe} # ffprobe path (used to probe BGM duration)
  python-command: ${PYTHON_CMD:python}  # Python interpreter running edge-tts (absolute path recommended on Windows to avoid the Microsoft Store alias stub)
  compose-timeout-minutes: 8        # Timeout per FFmpeg command (minutes); >=8 recommended for 1080p multi-shot
  motion:
    zoom-amount: 0.06                # Camera-motion zoom amount; smaller = steadier
    supersample-factor: 4            # zoompan supersampling cap (anti-jitter); adaptive to resolution, intermediate long edge <=3840: 1080p→2x / 720p→3x / 480p→4x; 1 = off
  watermark:                        # Social export: brand watermark
    logo-path: ""                    # Logo corner image path (transparent PNG, width <=300px recommended, bottom-right; empty = disabled)
  subtitle:                          # Module F: subtitle burning
    font-name: Microsoft YaHei       # Subtitle font (must be installed; Linux/Docker can use Source Han Sans CN)
  generator: none                    # Real i2v source: none (default still-image motion) / dashscope-wan (cloud) / comfyui (local zero-cost)
  workers: 2                         # i2v parallel threads; recommend 1 for the local comfyui route (single-GPU serial queue)
  retry-attempts: 1                  # i2v failure retry (billed per second — cost red line)
  candidates:                        # Phase 4: candidate draw counts (lower to 1 when cost-sensitive; one-click orchestration never draws)
    image-count: 3
    video-count: 2
  wan:                               # Cloud wan2.7-i2v (reuses DASHSCOPE_API_KEY)
    model: wan2.7-i2v                # First/last frame; wan2.6-i2v first frame only
    resolution: 720P                 # Cost baseline: 720P / 5s / low-medium motion
    timeout-seconds: 600
    poll-interval-ms: 5000
  comfyui:                           # Phase 5: local ComfyUI Wan2.2 i2v (zero API cost, assets never leave the machine)
    base-url: http://127.0.0.1:8188
    workflow-path: docs/comfyui/wan22_i2v_5b.template.json  # Placeholder template; override with a calibrated ComfyUI export (COMFYUI_WORKFLOW)
    fps: 24                          # Frame count auto-aligned to 4n+1; 720P→1280×704 / 480P→832×480
    timeout-seconds: 2700            # 8-9s long shots can take ~30min on a single GPU; prefer 1.5-2.5s short cuts
    poll-interval-ms: 3000
  tts:                               # Narration/dialogue voices and rates (edge-tts rate offsets)
    narration-voice: zh-CN-YunyangNeural  # Documentary male voice (override via TTS_NARRATION_VOICE)
    narration-rate: "+15%"           # Narration speed-up tier (measured ~5.5 chars/sec); shot duration >= chars / chars-per-sec + 0.6s
    dialogue-rate: "+0%"             # Dialogue no faster than narration, or it turns into an announcer
  commentary:                        # Track B: Commentary Studio (/commentary-studio)
    max-insert-ratio: 0.2            # Copyright guardrail: reject export when insert-shot duration / total exceeds the limit
    attribution-font: ""             # Attribution watermark Chinese font file (e.g. C:/Windows/Fonts/msyh.ttc)
    import-dir: ""                   # Asset batch scan dir (epNN_label_name.mp4/png convention; empty = disabled)
  assets:                            # Reference asset library (2026-11-17)
    reference-import-dir: ""         # char_Name_desc.png / scene_Name_desc.jpg convention scan dir (empty = disabled)
    booru:
      allowed-hosts: safebooru.org,safebooru.donmai.us,cdn.donmai.us  # Image host allowlist (https + exact match + redirects disabled)
      default-provider: safebooru    # Primary source: safebooru / danbooru-safe
  endcard:                           # End-card shots: endCard=true skips image gen, local solid-color + text
    font: C:/Windows/Fonts/msyh.ttc  # Chinese requires a fontfile (Linux/Docker: Noto Sans CJK)
    color: black                     # Background color (lavfi color expression)
  sfx:
    enabled: false                   # SFX recommend/mix master switch (also needs the export "SFX" checkbox)
  realesrgan-path: ""                # Real-ESRGAN ncnn-vulkan absolute path (empty = skip upscale)
  realesrgan-model: realesrgan-x4plus-anime  # x4plus-anime recommended for anime style
  rife-path: ""                      # RIFE ncnn-vulkan absolute path (empty = skip interpolation; depends on the upscale frame-extraction chain)
  rife-model: ""                     # RIFE model dir (e.g. rife-v4.6)
  restyle:                           # Phase 4: video restyle (reuses DASHSCOPE_API_KEY, empty key = unavailable)
    model: wan2.7-videoedit
    timeout-seconds: 600
    poll-interval-ms: 5000

sfx:
  library-path: ./data/sfx          # SFX library dir; filename format category__name.mp3,
                                    # categories: wind/rain/footsteps/heartbeat, auto-scanned on startup

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
- **Local ComfyUI dual protocol**: Both the image side (SD1.5+LoRA+IP-Adapter) and the video side (Wan2.2 i2v) use the native workflow protocol (/upload/image → /prompt → /history → /view) with template placeholder substitution, decoupled from any specific workflow; when ComfyUI is down or templates are uncalibrated, available=false auto-degrades without affecting clean startup; after a restart generators fall back to the default source — re-switch in the frontend dropdown
- **Reference-image consumption gate**: Shot-bound reference assets (referenceAssetIds) are consumed only by comfyui (when the IP-Adapter template is in place) and wan27 generators; at most the first 2 per shot (character reference images first, CHARACTER before SCENE); binding/rebinding never invalidates old images — it only affects the next generation; INSERT assets are barred from the generation chain (Commentary Studio stitching only)
- **booru three security gates**: ① https + exact host allowlist + redirects disabled; ② preview/ingest trust only provider+postId with a server-side re-query, never client URLs; ③ download guards 10s connect / 30s read / <=20MB / image/*; rating re-checked at all three entries (search/preview/ingest, safe/g/general only); ingested bytes are re-stored into own storage with sourceNote recording the source page + rating + tags; external links never enter the generation chain
- **TTS rate & duration arithmetic**: Narration defaults to YunyangNeural +15% (measured ~5.5 chars/sec); shot duration should be >= chars / chars-per-sec + 0.6s; recompute the whole film after changing rates; the Commentary Studio compose additionally pre-probes TTS and raises effective duration so narration is never cut
- **End-card shots**: Shots with endCard=true bypass the image provider; EndCardImageRenderer renders solid-color + text locally via FFmpeg; Chinese requires the `video.endcard.font` font file, otherwise text is silently not rendered

#### RAG Storage Modes

| Mode | Config Value | Use Case | Data Persistence |
|------|-------------|----------|-----------------|
| Memory | `memory` (default) | Dev/testing | Cleared on restart |
| PgVector | `pgvector` | Production | Survives restarts |

**Switch to PgVector persistent mode:**

1. Ensure PostgreSQL has pgvector extension installed
2. Activate `postgresql` profile (auto-sets `rag.store-type: pgvector`)
3. Configure embedding dimensions (default 768: nomic-embed-text and dashscope text-embedding-v4; openai text-embedding-3-small is 1536 and must be adjusted accordingly)

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
| `/api/auth/captcha` | GET | Get captcha image (4-char alphanumeric, answer session-only) |
| `/api/auth/register` | POST | User registration |
| `/api/auth/login` | POST | User login |
| `/api/auth/logout` | POST | Logout |
| `/api/auth/user` | GET | Get current user info |

### Chat Endpoints (Login Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat/stream` | POST | Stream message (SSE) |
| `/api/chat/send` | POST | Send message (non-streaming) |
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
| `/api/model/switch` | POST | Switch model (login required) |
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
| `/api/video/scripts/{id}/style` | PUT | Script-level style lock (Phase 4) |
| `/api/video/scripts/{id}/continuity` | PUT | Continuity mode NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME (Phase 4) |
| `/api/video/scripts/commentary/import` | POST | Import commentary JSON (format=commentary, Track B) |
| `/api/video/scripts/{id}/shots/{shotId}/asset` | POST | Bind asset to shot (clip / key visual, Track B) |
| `/api/video/scripts/{scriptId}/asset-matches` | POST | LLM asset match recommendations (not persisted; confirm via shot referenceAssetIds) |
| `/api/video/characters` | GET/POST | Character card list / create (incl. loraName/triggerWord, Phase 4) |
| `/api/video/characters/{id}` | PUT/DELETE | Update / delete character card |
| `/api/video/characters/{id}/reference-image` | POST | Upload character reference image (multipart) |
| `/api/video/characters/voices` | GET | TTS voice list |
| `/api/video/candidates` | GET | Shot candidate list (scriptId+shotId) |
| `/api/video/candidates/generate` | POST | Candidate draws (IMAGE N=3 / VIDEO N=2) |
| `/api/video/candidates/{id}/select` | POST | Human selection writes back the main asset |
| `/api/video/images/inpaint` | POST | Bbox inpainting (results become candidates) |
| `/api/video/videos/shot/{scriptId}/{shotId}` | POST | Single-shot real i2v generation (Phase 4/5) |
| `/api/video/videos/script/{scriptId}/stream` | GET | SSE batch i2v progress |
| `/api/video/videos/generators` | GET | Video generator list (none/dashscope-wan/comfyui) |
| `/api/video/videos/generator` | PUT | Switch video generator at runtime |
| `/api/video/restyle` | POST/GET | Submit restyle (multipart file+style) / my task list |
| `/api/video/restyle/{id}` | GET | Poll restyle task status |
| `/api/video/assets` | POST/GET | Asset upload (file/name/tags/category/trim) / list (filter by category) |
| `/api/video/assets/{id}` | DELETE | Delete asset (ownership check) |
| `/api/video/assets/scan` | POST | Commentary asset dir scan (epNN_label_name convention) |
| `/api/video/assets/scan-reference` | POST | Reference asset dir scan (char_/scene_ convention, idempotent dedup) |
| `/api/video/assets/booru/search` | GET | booru web search (q joins multiple tags with +; provider; page 0-4, 20 per page) |
| `/api/video/assets/booru/preview` | GET | Server-side thumbnail proxy (provider+postId, gated byte fetch) |
| `/api/video/assets/booru/ingest` | POST | Ingest searched asset (re-storage into own storage + sourceNote attribution) |
| `/api/video/export/hd/async` | POST | Async HD export (returns jobId, Track B) |
| `/api/video/export/jobs/{id}` | GET | Async export job status (RUNNING/SUCCESS/FAILED) |
| `/api/video/export/batch-download` | POST | Batch three-platform export + ZIP package download |
| `/api/video/templates` | GET | Video template list (8 built-in + custom) |
| `/api/video/templates/{id}` | GET/DELETE | Template detail / delete |
| `/api/video/templates/{id}/apply` | POST | One-click apply template to generate a script |
| `/api/video/templates/custom` | POST | Save script as custom template |
| `/api/video/agent/tools` | GET | Agent tool list (name/description/input schema, Phase 3 slice 1) |
| `/api/video/agent/chat` | POST | AI director chat (ReAct tool orchestration; exportVideo requires confirmedTools, Phase 3 slice 2) |
| `/api/video/agent/chat/{sessionId}` | DELETE | Clear director conversation history (Phase 3 slice 2) |
| `/api/video/agent/orchestrate` | POST | One-click pipeline: submit a multi-agent async orchestration task (Phase 3 slice 3) |
| `/api/video/agent/orchestrate/{taskId}` | GET | Query orchestration task status (polling, Phase 3 slice 3) |
| `/api/video/agent/orchestrate/{taskId}/confirm` | POST | Confirm to resume: pass the exportVideo gate (Phase 3 slice 3) |

> Video Studio page: http://localhost:8080/video-studio ; Commentary Studio: http://localhost:8080/commentary-studio (see [API_TEST-en.md](API_TEST-en.md) Section VII and the trailing Phase sections)

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
| `app` | Local build (Dockerfile) | Spring AI app, multi-stage build, JRE 17 Alpine runtime; the image bundles FFmpeg, Python+edge-tts (venv), Noto CJK fonts and preset BGM — full video studio features work out of the box |
| `postgres` | `pgvector/pgvector:pg16` | PostgreSQL + pgvector extension, RAG vector persistence |
| `ollama` | `ollama/ollama` | Local AI model service, optionally enabled |

Services communicate over the internal `ai-net` network. The app accesses middleware via service names (`postgres:5432`, `ollama:11434`).

- **Health Check**: postgres has a `pg_isready` health check; app waits for database readiness before starting
- **Data Persistence**: Named volumes `pgdata`, `ollama-data`, `appdata` (preset BGM/runtime data) and `uploads` (generated files) ensure data survives container rebuilds
- **Profile Control**: ollama service uses `profiles: [ollama]`, not started by default — requires `--profile ollama` to explicitly enable
- **Secure Runtime**: App runs as non-root user `appuser` inside the container
- **Video Studio Dependencies**: The image ships FFmpeg (>=6.x with xfade/libass) and edge-tts; compose injects `FFMPEG_PATH`/`FFPROBE_PATH`/`PYTHON_CMD` automatically; the docker profile switches the subtitle font to `Noto Sans CJK SC`; set `DASHSCOPE_API_KEY` in `.env` (image generation / quality evaluation) and a strong `POSTGRES_PASSWORD` (required, no hardcoded default)

## FAQ

### 1. Ollama Connection Failed
Ensure `ollama serve` is running and models are pulled.

### 2. API Call Failed
Check API Key, network connection, and account balance.

### 3. AI Doesn't Remember Conversations
Ensure consistent `sessionId` usage across requests.

### 4. RAG Document Upload Failed
- Confirm file size ≤ 10MB
- Ensure embedding model is available: `ollama pull nomic-embed-text`, or switch to cloud embedding via `rag.embedding-model: dashscope` (Tongyi text-embedding-v4, reuses DASHSCOPE_API_KEY, no local Ollama) or `openai`; restart to apply, clear old vectors and re-upload after switching

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

### 13. How to Use Local Stable Diffusion / FLUX / ComfyUI for Video Frames?

1. **SD WebUI route**: Start a local SD WebUI (or OllamaDiffuser-compatible service) and verify it responds at `http://127.0.0.1:7860`; set `image.generator: local` or switch via the frontend dropdown
2. **ComfyUI route (native workflow protocol, supported)**: Start ComfyUI (default `http://127.0.0.1:8188`), export a calibrated workflow from your machine per the docs/comfyui/ reference templates and override `image.comfyui.workflow-*`; set `image.generator: comfyui` or switch via the dropdown; supports SD1.5 + character LoRA, IP-Adapter reference images (weight 0.7) and ControlNet camera lock (gated)
3. Local generation natively supports negative prompts; the SD WebUI route auto-applies style presets by model name (SD1.5/SDXL/FLUX), giving more controllable styles with zero API cost; customize via `image.local.style-prefix` / `image.local.extra-negative-prompt`
4. Same on the video side: `video.generator: comfyui` runs local Wan2.2 i2v (zero API cost), calibrate `video.comfyui.workflow-path`; recommend `video.workers: 1` on a single GPU

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

### 16. How to Use booru Web-Search Assets? Why Do Multi-Tag Searches Return Nothing?

- Search English tags in the studio "Reference Asset Library" panel (e.g. `hatsune_miku`), then "Ingest Selected" re-stores them into your own storage (sourceNote automatically records the source page / rating / tags)
- **Multi-tag semantics**: spaces become underscores (word segmentation within a single tag, e.g. `black hair`→`black_hair`); join multiple tags with `+` for AND (e.g. `1girl+black_hair`); more specific tags yield fewer results — on zero results, drop tags first, then switch source (provider=danbooru-safe)
- Thumbnails go through the server-side proxy (direct browser connections to the sources often fail); occasional thumbnail load failures are usually transient upstream rate limits — just retry
- Ingest supports only CHARACTER/SCENE reference categories; non-safe ratings and non-allowlisted hosts are always rejected (three gates, see Configuration Guide)

### 17. How to Improve Character Consistency (Keep the Protagonist's Face Stable)?

- **Cloud route**: `image.generator: wan27` + character-card reference images (<=9); the reference fallback chain handles local/oss storage automatically
- **Local route**: `image.generator: comfyui` + character LoRA (fill loraName/triggerWord on the character card); without LoRA use the IP-Adapter reference template (calibrate `sd15_ipadapter.template.json` locally; weight defaults to 0.7 — too high copies it like a sticker, too low loses resemblance)
- Shot-bound reference assets are consumed automatically at generation (first 2 per shot, characters first); note that generators fall back to the default source after a restart — re-switch; pure text-to-image sources like the default zhipu do not consume reference images
- For strict composition/camera requirements enable `image.comfyui.controlnet-enabled: true` (canny camera lock, +~10s/image); fix broken hands/faces with bbox inpaint (results become candidates for human selection)

## References

- [Spring AI Official Documentation](https://spring.io/projects/spring-ai)
- [Spring Security Official Documentation](https://spring.io/projects/spring-security)
- [Ollama Official Website](https://ollama.com)
- [Spring Boot Official Documentation](https://spring.io/projects/spring-boot)

## License

This project is for learning and demonstration purposes only.

## Author

ylyan2015


## Phase 4 Slice 1: Character Cards & Image Consistency (2026-11-15)

- Character cards auto-generated with storyboard (name / English feature table / personality / voice); /api/video/characters CRUD + reference-image upload + GET /voices.
- Script-level style lock: PUT /api/video/scripts/{id}/style; shots gain shot size (CLOSE/MEDIUM/LONG) and character links.
- New wan27 image generator (DashScope wan2.7-image, up to 9 reference images, URL/base64); switch via image.generator=wan27. Reference fallback chain: public URL -> presigned -> local base64 -> text+feature-table degradation.
- Config: image.wan27.model (default wan2.7-image), image.wan27.timeout-seconds (120); reuses DASHSCOPE_API_KEY (empty key = unavailable, clean-start safe).
- Agent: screenwriter gains generateCharacters tool (7 tools).
- Smoke: mvn test -Dtest=Wan27RealSmokeTest -Dwan27=true (skipped by default).


## Phase 4 Slice 2: Real Image-to-Video & Frame Continuity (2026-11-15)

- Real i2v: new DashScope wan2.7-i2v video generator (async submit + polling, first/last frame media, mp4 re-storage); switch via video.generator=dashscope-wan. Default none keeps still-image motion compose (existing exports unchanged frame-by-frame).
- Continuity modes: script-level NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME (PUT /api/video/scripts/{id}/continuity); PREV_LAST_FRAME chains previous shot's last frame as next first frame (sequential); GENERATED_LAST_FRAME extends a last frame via wan27 from the next shot's prompt (skipped for the final shot).
- Compose upgrade: mixed real-clip + still-image composition; real clips use ffprobe-measured duration without zoompan; xfade total = sum(durations) - (n-1)*0.8; i2v READY writes back durationSeconds and extracts/re-stores the last frame.
- Config: video.generator (none/dashscope-wan), video.workers (default 2), video.retry-attempts (default 1), video.wan.model/base-url/resolution/timeout-seconds/poll-interval-ms; reuses DASHSCOPE_API_KEY (empty key = unavailable).
- First-frame URL fallback: public URL -> presigned for non-local storage -> local storage returns 400 with guidance to switch to oss/minio.
- Agent: artist gains generateShotVideo tool (8 tools).
- Smoke: mvn test -Dtest=WanI2vRealSmokeTest -Di2v=true (skipped by default).


## Phase 4 Slice 3: Multi-Candidate Draws & Inpainting (2026-11-15)

- Candidate draws: per shot image N=3 / video N=2 generated sequentially (video.candidates.image-count/video-count configurable; lower to 1 for cost), quality-score ranked, best score provisionally written back as main asset (UI flags "pending selection"); human select is the final decision. One-click orchestration never draws (N=1).
- Inpainting: wan2.7-image bbox editing (bbox_list absolute pixels; relative 0-1 coords auto-converted); inpaint results become candidates, never overwrite the main image. Unsupported generator / out-of-bounds rect returns 400.
- Endpoints: /api/video/candidates (list / generate / select), /api/video/images/inpaint; frontend candidate comparison modal + inpaint rect dialog.
- Agent: artist gains inpaintImage tool (9 tools); it may recommend/execute inpainting, selection stays with the user.
- Smoke: mvn test -Dtest=Wan27CandidateSmokeTest -Dwan27=true (skipped by default).


## Phase 4 Slice 4: Multi-Voice Dubbing & Post Enhancement (2026-11-15)

- Dialogue/narration split: shots gain a dialogue field linked to a speaking character (auto-produced by the script LLM, editable in UI); TTS synthesizes per line: dialogue uses the character card voice, narration keeps zh-CN-YunxiNeural; lines concat within a shot, silence-padded concat across shots. Without dialogue, behavior is byte-identical to before.
- Subtitles include dialogue: cue text = dialogue + narration (dialogue first), effective for zh/en/bilingual burn-in and SRT export.
- Layered SFX mix: 4 built-in royalty-free tracks (wind/rain/footsteps/heartbeat, synthesized via tools/generate-preset-sfx.ps1 lavfi, auto-registered by scanning data/sfx at startup); one batched LLM call recommends a category per shot with keyword fallback on failure/unconfigured; mix layers voice 100% / BGM 15% / SFX 30% (amix normalize=0 prevents voice halving), adelay positions each track at its shot start.
- Upscale/interpolate export enhancement: Real-ESRGAN 2x upscale + RIFE interpolation (frame count doubles, output frame rate doubles too 30->60fps to keep duration; extract frames -> upscale -> interpolate -> re-encode keeping audio); unconfigured tools skip silently, failures warn and skip without blocking export; HD export only — preview/platform/Agent exports unchanged.
- New export params: POST /api/video/export/hd accepts optional upscale/interpolate/sfx (all default false); frontend action bar gains three checkboxes, shot editor gains dialogue + speaking character.
- Config: video.sfx.enabled (default false), sfx.library-path (./data/sfx), video.realesrgan-path/model (default realesrgan-x4plus-anime), video.rife-path/model (empty = skip); download the enhancers from Real-ESRGAN/RIFE ncnn-vulkan releases; CPU runs are slow, Docker image does not bundle them.


## Phase 4 Slice 5: Video Restyle Output Mode (2026-11-15)

- Video restyle: upload live-action footage (mp4/mov, 2-10s, <=100MB) -> wan2.7-videoedit style transfer (anime / American comic / watercolor presets) -> independent output mode; consistency is locked by the real footage, main pipeline untouched.
- Independent tasks: RestyleTask entity (VideoExport.script_id NOT NULL legacy constraint cannot be relaxed; restyle has no script ownership); async submit + polling + result re-storage, SecurityContext propagation idiom same as orchestration; failures only mark the task FAILED.
- Endpoints: POST /api/video/restyle (multipart file+style), GET /api/video/restyle/{id}, GET /api/video/restyle (my tasks); frontend restyle panel polls every 3s (only while non-terminal) + result playback/download.
- Config: video.restyle.model (default wan2.7-videoedit)/timeout-seconds (600)/poll-interval-ms (5000), reuses DASHSCOPE_API_KEY (empty key = unavailable).
- Spike acceptance (2026-11-15 live test): motion/camera preserved + anime stylization passed; billing = input + output duration (720P baseline); video input accepts public URL / oss temp URL only, no base64 -> local storage FAILED with oss/minio guidance; Kling transform is a 2-OPT alternative (requires KLING_API_KEY, not implemented this phase).
- Smoke: mvn test -Dtest=VideoRestyleRealSmokeTest -Drestyle=true (skipped by default; local storage verifies the error path).


## Phase 5 Slice 1: Local ComfyUI Image-to-Video (Zero-Cost Route) (2026-11-15)

- Local i2v: new ComfyUiVideoGenerator (type=comfyui) drives a local ComfyUI (Wan2.2 TI2V-5B, Apache 2.0, commercial-use OK): zero API cost, no rate limits, assets never leave the machine; switch via video.generator=comfyui or the frontend dropdown (auto-listed by /generators).
- Template-agnostic: workflow JSON placeholder substitution ({{FIRST_FRAME}}/{{PROMPT}}/{{FRAMES}}/{{WIDTH}}/{{HEIGHT}}/{{FPS}}/{{SEED}}); reference template docs/comfyui/wan22_i2v_5b.template.json; after Phase 0 setup, export a calibrated workflow from ComfyUI and override (video.comfyui.workflow-path / COMFYUI_WORKFLOW).
- Local-storage closed loop: resolveMediaUrl gains a local×comfyui relative-path pass-through branch; the generator fetches first-frame bytes same-origin (127.0.0.1:server.port), bypassing the public-URL/oss-minio constraint; local×dashscope-wan behavior unchanged.
- Protocol: /upload/image -> /prompt -> /history/{id} -> /view; frame count auto-aligned to 4n+1; resolution tiers 720P->1280x704 (machine-measured) / 480P->832x480; template locked to VAEDecodeTiled (plain VAEDecode hangs on this machine) + core CreateVideo/SaveVideo.
- Config: video.comfyui.base-url (default http://127.0.0.1:8188)/workflow-path/fps (24)/timeout-seconds (1800)/poll-interval-ms (3000); isAvailable=false when ComfyUI is down or the workflow is unconfigured — clean startup unaffected, default none unchanged. workflow-path relative paths resolve against the startup CWD; set an absolute path (COMFYUI_WORKFLOW) for packaged deployments.
- Smoke: mvn test -Dtest=ComfyUiRealSmokeTest -Dcomfyui=true (skipped by default; requires ComfyUI running with a calibrated workflow). Scheduling: video.workers=1 recommended for the comfyui route (single-GPU serial queue); measured 29f~155s/57f~382s/121f~722s — prefer 1.5-2.5s short cuts.

## Phase 5 Slice 3: Local Image Side SD1.5+LoRA Storyboards (Fully Zero-Cloud) (2026-11-15)

- Local storyboards: image-side ComfyUiImageGenerator (type=comfyui), anything-v5 + character LoRA for identity consistency (measured ~8s/image, 22 steps CFG7 euler/normal); image.generator=comfyui or runtime dropdown switch; characters without LoRA use the plain template, legacy sources unchanged verbatim.
- Character cards: loraName/triggerWord fields end-to-end (entity/service/controller/frontend dialog input·refill·clear); trigger words injected via the single assembly point ShotPromptAssembler (only when non-empty).
- LoRA routing: ImageGenerator gains supportsLora/generateWithLora capability flags; LoraResolver picks the first LoRA-bearing character; single dispatch point in generateWithRetry + candidate gacha path routed alike.
- ControlNet camera lock (gated): image.comfyui.controlnet-enabled defaults false; when enabled the character reference image goes through canny (0.4/0.8, strength 0.8, +~10s/image); three templates docs/comfyui/sd15_storyboard / sd15_lora_storyboard / sd15_lora_canny.template.json.
- Config: image.comfyui.workflow-plain/lora/canny (env COMFYUI_IMG_*)/timeout-seconds 120/poll-interval-ms 1000; base-url reuses video.comfyui.base-url; 720 height→704 (measured); SD1.5 uses plain VAEDecode (tiled mandatory for Wan only).
- Smoke: mvn test -Dtest=ComfyUiImageRealSmokeTest -Dcomfyui=true (skipped by default; measured 2/2 green, output 1280x704). Full suite 346 green.

## Phase 5 Track B: Commentary Automation (Commentary Studio) (2026-11-16)

For anime/film commentary & review creation (distinct from the /video-studio AI short-drama auto mode): stitch asset images/video clips + subtitles + narration, with a separate page entry; the two tracks are kept apart.

- Commentary Studio: /commentary-studio (login required); script list separated by format=commentary (legacy AI drama scripts not mixed in); paste/file import of commentary JSON (shots[].insert.attribution -> source attribution).
- Asset library: upload (video optional trim trimStart/trimEnd, ffprobe-measured duration)/list/delete; folder batch scan ingest (reads only the configured dir video.commentary.import-dir; filename convention epNN_label_name.mp4/png -> tags="epNN,label", invalid names skipped).
- Shot asset binding: video asset -> bound as clip (writes back measured duration); image asset -> bound as key visual (auto-decided by kind; stale video invalidated to avoid mixing).
- Auto-align compose: TTS pre-probe per-shot effective duration = max(shot duration, ceil(narration audio measured)), preventing narration being cut by -shortest; real clips shorter than effective duration get -stream_loop -1 loop fill + trim.
- Attribution watermark + copyright guard: insert shots (insertAttribution non-empty) overlay a bottom-left source watermark (Chinese requires video.commentary.attribution-font); pre-export check insert ratio = sum(insert shot dur)/sum(total dur) > video.commentary.max-insert-ratio (default 0.2) rejects; page ratio badge is a client-side estimate (server authoritative).
- Async export: POST /api/video/export/hd/async returns jobId immediately, a background single-thread worker composes, GET /api/video/export/jobs/{id} polls RUNNING/SUCCESS/FAILED; sync /export/hd unchanged.
- Publishing tool: tools/auto-commentary-concat.js (Node) stitches main + insert assets along the script timeline (xfade=0.8) and burns the attribution watermark; main aspect must match the script aspectRatio, drawtext uses an explicit fontfile for Chinese glyphs.
- Config: video.commentary.max-insert-ratio (0.2)/attribution-font (empty)/import-dir (empty). Smoke: mvn test -Dtest=VideoComposeAutoFitTest -Dcommentary=true (real FFmpeg loop, skipped by default).

## Phase 5 Asset Library Slice: Reference Assets & booru Web Search (2026-11-17)

For manga-drama character/scene consistency: closes the loop "find reference image → bind to shot → generate with references"; shares the MediaAsset entity with the Track B commentary asset library but with separated purposes (CHARACTER/SCENE enter the generation chain, INSERT is commentary-stitching only).

- S1 reference ingest: upload gains category (CHARACTER/SCENE/INSERT allowlist, fail-fast); new endpoint POST /api/video/assets/scan-reference scans the configured dir video.assets.reference-import-dir (filename convention char_Name_desc.png / scene_Name_desc.jpg, case-insensitive, multi-segment labels underscore→comma, idempotent dedup).
- S2 shot binding: shots gain referenceAssetIds (threaded through single PUT and batch PUT); write-side triple validation (ownership + IMAGE + category∈{CHARACTER,SCENE}), forged/foreign ids silently dropped; consumer-side ReferenceImageResolver re-validates as the last line of defense; binding/rebinding never invalidates old images/videos (only affects the next generation).
- S3 IP-Adapter reference generation: ComfyUiImageGenerator template routing refs-first (ipadapter+lora combo / ipadapter / plain / lora / lora+canny); reference consumption gate takes the first 2 per shot (character reference images first, CHARACTER before SCENE); when the ipadapter template is absent supportsReferences=false auto-degrades without blocking startup; new templates docs/comfyui/sd15_ipadapter.template.json (locally calibrated, plus_sd15 + ViT-H) / sd15_ipadapter_lora.template.json (combo pending manual calibration); config image.comfyui.workflow-ipadapter/ipadapter-lora/ipadapter-weight (0.7).
- S4 booru web-search ingest: GET /api/video/assets/booru/search (q/provider/page, 20 per page, <=5 pages) / GET preview (server-side gated byte proxy, magic-number sniffing png/jpeg/webp/gif) / POST ingest (provider+postId re-query, bytes re-stored into own storage, sourceNote=source page|rating|tags); dual sources safebooru (primary) / danbooru-safe; three gates: https + exact host allowlist + redirects disabled / never trust client URLs / download guards 10s+30s+<=20MB+image/*; rating re-checked at all three entries (safe/g/general only); policy rejections 400 with pass-through message, internal failures 500 with a fixed message to prevent SQL leakage.
- Auto matching: POST /api/video/scripts/{scriptId}/asset-matches lets the LLM batch-judge shot×asset (name/tags text) relevance, degrading to keyword overlap on failure (source=LLM/FALLBACK); recommendations are not persisted — frontend chips adopt with one click, confirmed via updateShot.referenceAssetIds.
- Frontend: studio asset library panel (upload/scan/web search/checkbox ingest/match chips/binding management); booru search placeholder hints joining multiple tags with +.
- Acceptance: full test gate green; real story (GTA6 thirteen-year wait) production test + browser UI end-to-end verification (search hatsune_miku → ingest → bind → comfyui generation visually inheriting Miku features → HD export playback).

## Production Calibration: End Cards / TTS Rate / ComfyUI Timeout (2026-11-17)

Three calibrations after a full-film trial production with a real story (backward compatible; defaults tuned by measurement):

- End-card shots: shots gain an endCard field; when true the image provider is bypassed and EndCardImageRenderer renders solid-color + text locally via FFmpeg (follow / next-episode CTA); config video.endcard.font (Chinese requires a fontfile) / color; zero API cost, never occupies the generation queue.
- TTS voice & rate: new config video.tts.narration-voice (default zh-CN-YunyangNeural documentary male voice) / narration-rate (+15%, measured ~5.5 chars/sec) / dialogue-rate (+0%); line arithmetic: shot duration >= chars / chars-per-sec + 0.6s; recompute the whole film after rate changes.
- ComfyUI i2v timeout raised: video.comfyui.timeout-seconds 1800→2700 (measured 8s shot 193f ~25min, 9s shot 217f ~31min hitting the old red line); scheduling advice: 1.5-2.5s short cuts as the workhorse, workers=1.
- Fixed (2026-11-18): subtitle timeline drift — compose now rebuilds subtitle cues on effective durations measured from TTS audio / real clips (rebuild baseline is the nominal declared durations, covering both TTS raise and real-clip measured drift).
- Known limitations (deferred to later slices): booru preview returns 500 for nonexistent postIds (should be 404).

## Hook-Driven Scripts: Narrative Hard Constraints + Line-Density Auto-Check (2026-11-18)

P0 retention fundamentals, effective for every script generation path (VideoScriptService system prompt; story scripts / template guidance / asset catalog all inherit it):

- Hook narrative hard constraints: the first shot must throw conflict/suspense within 3 seconds (opening narration <= 15 chars, no scenery buildup or slow intros); an emotional turn every 15-20 seconds (conflict escalation / information reversal / emotional drop); the last shot leaves a cliffhanger feeding the end-card follow CTA (with an end-card shot, the cliffhanger goes in the second-to-last shot).
- Line-density auto-check: after generation each shot is checked against duration >= (narration + dialogue chars) / 5.5 + 0.6s (measured YunyangNeural +15% rate baseline); violations only warn, never block (LLM output cannot be hard-controlled; compose's TTS pre-probing raises durations as the safety net); end-card shots render text locally without TTS and are skipped.
- Tests: VideoScriptServiceHookTest (prompt constraint contract + density arithmetic boundaries / dialogue summing / end-card skip / null safety, mutation-verified).

## Portrait-First: Script-Level Aspect Ratio + Vertical Composition Prompts + Multi-Character Close-Up Split (2026-11-18)

P0 short-video platform adaptation (Douyin / Kuaishou / WeChat Channels vertical feeds); existing landscape scripts behave byte-for-byte unchanged:

- Script-level aspect field: StoryScript gains aspectRatio (whitelist 16:9 / 9:16 / 1:1, null = default landscape); update via PUT /api/video/scripts/{id}/aspect (blank clears); script detail responses gain an aspectRatio field (null surfaces as "16:9").
- Automatic image-size switch: with script aspect 9:16, all four assembly points (single shot / batch / end card / candidates) switch to 720x1280 (overriding image.generation.default-size); other aspects keep the configured size (invalid/missing falls back to 1280x720).
- Vertical composition prompts: at 9:16 the prompt assembly point (ShotPromptAssembler) injects centered subject / vertical framing; multi-character shots (>= 2 characters) are forced into a single-character close-up (single character close-up, only one person in frame, replacing the original shot size to avoid SD1.5 multi-person face collapse); single-character / empty shots keep their original shot size.
- Export aspect fallback: preview / HD / async export requests without aspectRatio automatically inherit the script aspect (explicit request wins; both null keeps legacy 16:9); a 9:16 script exports 720x1280 by default.
- Tests: ShotPromptAssemblerPortraitTest / ImageGenerationPortraitSizeTest / StoryScriptServiceAspectTest / VideoExportAspectFallbackTest (26 cases, four mutations each independently red-verified); full gate 602 green.

### Script Self-Refinement: Generate-Evaluate-Generate (2026-11-18)

After the first-draft storyboard, two rounds of "four-expert critic panel (director / screenwriter / artist / musician chained in one prompt, question-only) -> rewrite under the original hard constraints" iteratively improve hooks, dialogue density, imagePrompt quality and audio-visual timing.
- Config `video.script.refine-rounds` (default 2, 0 = off); each round adds 2 LLM calls, total latency and tokens roughly 3-5x of direct output. Frontend wait budget: `video.script.client-timeout-ms` (default 900000ms).
- Dedicated script model `video.script.model` (default `deepseek-flash` for cost — decided 2026-11-18, v4-pro costs ~3-5x; set env `VIDEO_SCRIPT_MODEL=deepseek-v4-pro` to upgrade for quality; deepseek profile only; blank = follow global chat model; thinking mode is on by default on the V4 line), per-call read timeout `video.script.read-timeout-seconds` defaults to 300s.
- Graceful degradation: any critique/rewrite call failure or unparseable output keeps the current best draft; refinement never breaks generation.
- Field note & wiring fix (2026-11-18): the studio page now calls script generation (and template apply) through postLong with an AbortController, budget injected server-side from `video.script.client-timeout-ms` (default 900s); on timeout it prompts "server still refining, refresh the list later" instead of a false failure. The e2e driver switched to node:http to lift the built-in fetch 300s cap (verified: client held a 580s response with no early disconnect). Async endpoint may follow in a later slice.
- Tests: VideoScriptServiceRefineTest / ModelServiceScriptClientTest (11 cases, 7 mutations each independently red-verified) + VideoStudioPageRenderTest long-wait wiring assertions + VideoScriptDefaultsTest config-default pin; full gate 621 green.
