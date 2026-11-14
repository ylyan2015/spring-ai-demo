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
{ "success": true, "image": "data:image/png;base64,iVBORw0KGgo..." }
```

> The captcha is a 4-character alphanumeric image with noise lines; the answer is stored only in the session and never returned (anti-brute-force). Single-use, case-insensitive.
> Only when the dev flag `auth.captcha.debug-text=true` is enabled does the response additionally include the plaintext `captcha` field (for e2e automation; keep disabled in production).

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

> Login rate limiting: at most 5 failures per username+IP within 60 seconds (wrong captcha and wrong password both count); exceeding returns **429**. The session ID is rotated on successful login (session-fixation protection).

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

> `sessionId` always returns the real session ID (a new session is created and its ID returned when omitted).

---

### 2.3 Send Message (GET - Legacy, Removed)

> **Removed in v2.0.0**: GET endpoints with side effects can be triggered cross-site (CSRF vector). Use the POST in 2.2 for non-streaming, or 2.1 for streaming.

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

> Owner-only access; cross-user / anonymous / unknown session returns 400 `{success:false, message}`.

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

> Global switch, **login required** (anonymous gets 401, preventing malicious switches affecting all users).

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

## VI. Image Generation Endpoints (Login Required)

### 6.1 Generate Image

Calls Alibaba Cloud Bailian Tongyi Wanxiang model (wan2.6-t2i) to generate images, automatically saved to the configured storage service.

**Request:**
```bash
curl -X POST http://localhost:8080/api/image/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "A cute orange cat napping in the sun",
    "size": "1024x1024",
    "n": 1
  }'
```

**Request Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `prompt` | string | Yes | - | Image description prompt |
| `size` | string | No | `1024x1024` | Image size (e.g. 1024x1024, 720x1280) |
| `n` | int | No | `1` | Number of images (1~4) |
| `quality` | string | No | - | Image quality (standard/hd) |
| `style` | string | No | - | Image style (realistic/illustration) |
| `sessionId` | string | No | - | Associated chat session ID |

**Response:**
```json
{
  "success": true,
  "message": "Image generated successfully",
  "data": {
    "imageUrl": "/images/2026/07/05/uuid_filename.png",
    "fileId": "2026/07/05/uuid_filename.png",
    "fileName": "ai_image_1234567890.png",
    "storageType": "local",
    "prompt": "A cute orange cat napping in the sun",
    "fileSize": 102400,
    "width": 1024,
    "height": 1024,
    "createTime": "2026-07-05T10:30:00",
    "sessionId": null
  }
}
```

> **Note**: `imageUrl` is a publicly accessible image URL (via `/images/**` static resource mapping for local storage).
> Requires `DASHSCOPE_API_KEY` environment variable or `spring.ai.dashscope.api-key` in `application.yml`.

---

### 6.2 Get Image Generation History

**Request:**
```bash
curl http://localhost:8080/api/image/records
```

**Response:**
```json
{
  "success": true,
  "records": [
    {
      "id": 1,
      "prompt": "A cute orange cat napping in the sun",
      "imageUrl": "/images/2026/07/05/uuid_filename.png",
      "fileId": "2026/07/05/uuid_filename.png",
      "fileName": "ai_image_1234567890.png",
      "storageType": "local",
      "fileSize": 102400,
      "width": 1024,
      "height": 1024,
      "sessionId": null,
      "createTime": "2026-07-05T10:30:00"
    }
  ],
  "count": 1
}
```

---

### 6.3 Get Image Generation Records by Session

**Request:**
```bash
curl http://localhost:8080/api/image/records/your-session-id
```

**Response:**
```json
{
  "success": true,
  "records": [],
  "count": 0
}
```

---

## VII. AI Short Video Studio Endpoints (Login Required)

Complete workflow: **Idea → Storyboard → Prompt Enhancement → Parallel Image Generation (SSE progress) → Quick Preview / BGM Scoring / Multi-Ratio HD Export**

Frontend page: http://localhost:8080/video-studio

> Video composition requires FFmpeg (including ffprobe; paths configured via `video.ffmpeg-path` / `video.ffprobe-path`, no PATH dependency, >=4.3 required). Narration requires Edge TTS (`pip install edge-tts`; the Python interpreter is specified by `video.python-command`; automatically skipped when not installed).

### 7.1 Generate Storyboard Script

Generates a multi-shot storyboard from a creative idea via LLM and persists it (optional `templateId` applies a video template, see 7.10):

```bash
curl -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea": "A heartwarming story about a ginger cat's daily life"}'

# Generate with a video template (style guidance injected into the System Prompt)
curl -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea": "A heartwarming story about a ginger cat", "templateId": 2}'
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "title": "A Ginger Cat's Day",
    "theme": "Heartwarming",
    "status": "DRAFT",
    "shots": [
      {
        "id": 11, "shotOrder": 1,
        "sceneDescription": "Morning sunlight streams through the window, a ginger cat curled up asleep on a blanket",
        "cameraMovement": "Slow push-in", "durationSeconds": 5,
        "narration": "The first ray of morning sunlight",
        "imagePrompt": "A ginger cat sleeping on a blanket in morning sunlight...",
        "imageUrl": null, "imageStatus": "PENDING"
      }
    ]
  }
}
```

---

### 7.2 Script List / Detail / Update / Delete

```bash
# List scripts (current user's only)
curl http://localhost:8080/api/video/scripts

# Script detail (with all shots)
curl http://localhost:8080/api/video/scripts/1

# Rename
curl -X PUT http://localhost:8080/api/video/scripts/1 \
  -H "Content-Type: application/json" \
  -d '{"title": "New Title"}'

# Delete script (with shots)
curl -X DELETE http://localhost:8080/api/video/scripts/1
```

**List response:**
```json
{ "success": true, "data": [ { "id": 1, "title": "A Ginger Cat's Day", "shotCount": 5, "status": "DRAFT" } ] }
```

---

### 7.3 Shot Management (CRUD / Reorder)

```bash
# Add shot (appended at the end)
curl -X POST http://localhost:8080/api/video/scripts/1/shots \
  -H "Content-Type: application/json" \
  -d '{"sceneDescription":"New shot","narration":"","imagePrompt":"","cameraMovement":"Static","durationSeconds":5}'

# Update shot (any fields; changing imagePrompt auto-clears the old image and resets to PENDING)
curl -X PUT http://localhost:8080/api/video/scripts/1/shots/11 \
  -H "Content-Type: application/json" \
  -d '{"durationSeconds": 6, "narration": "Updated narration"}'

# Delete shot
curl -X DELETE http://localhost:8080/api/video/scripts/1/shots/11

# Drag-sort shots (shotIds is the full ordered ID list)
curl -X PUT http://localhost:8080/api/video/scripts/1/shots/reorder \
  -H "Content-Type: application/json" \
  -d '{"shotIds": [13, 11, 12]}'
```

> Note: when imagePrompt changes, the server automatically clears imageUrl/imageFileId and resets imageStatus to PENDING; images must be regenerated.

---

### 7.4 Prompt Enhancement (8 Visual Styles)

```bash
# Get style list
curl http://localhost:8080/api/video/enhance-prompt/styles

# Enhance a single prompt (Chinese scene description → high-quality English prompt)
curl -X POST http://localhost:8080/api/video/enhance-prompt \
  -H "Content-Type: application/json" \
  -d '{"sceneDescription": "A ginger cat lounging on a windowsill at sunset", "style": "CINEMATIC_REALISM"}'

# Batch enhance all shots' prompts (calls LLM per shot; time-consuming)
curl -X POST http://localhost:8080/api/video/enhance-prompt/script/1 \
  -H "Content-Type: application/json" \
  -d '{"style": "ANIME"}'
```

**Styles:** `CINEMATIC_REALISM`, `ANIME`, `WATERCOLOR`, `CYBERPUNK`, `PIXAR_3D`, `VINTAGE`, `CHINESE_PAINTING`, `FANTASY` (Chinese names also accepted).

**Single enhancement response:**
```json
{
  "success": true,
  "data": {
    "enhancedPrompt": "A ginger cat lounging on a windowsill at golden hour, warm cinematic lighting...",
    "negativePrompt": "text, watermark, blurry, low quality",
    "style": "CINEMATIC_REALISM",
    "styleName": "Cinematic Realism"
  }
}
```

---

### 7.5 Image Generation (Single / Batch SSE / Generator Management)

```bash
# Generate image for a single shot (style optional; local SD generator appends negative prompt and auto-applies model style presets)
curl -X POST http://localhost:8080/api/video/images/shot/1/11 \
  -H "Content-Type: application/json" \
  -d '{"style": "CINEMATIC_REALISM"}'

# List image generators (with availability detection)
curl http://localhost:8080/api/video/images/generators

# Switch generator at runtime (type: dashscope / local)
curl -X PUT "http://localhost:8080/api/video/images/generator?type=local"
```

**Batch parallel generation (SSE; native EventSource supports GET only):**

```
GET /api/video/images/script/1/stream?style=CINEMATIC_REALISM
Accept: text/event-stream
```

**SSE event types:**

| Event | data payload |
|-------|--------------|
| `START` | `{totalShots, message}` |
| `SHOT_START` | `{shotId, shotOrder, currentShotIndex, message}` |
| `SHOT_COMPLETE` | `{shotId, imageUrl, qualityScore, completedShots, failedShots, totalShots}` |
| `SHOT_FAILED` | `{shotId, completedShots, failedShots, message}` |
| `COMPLETE` | `{completedShots, failedShots, message}` |
| `ERROR` | `{message}` |

> Parallelism is controlled by `image.generation.parallelism` (default 3); failures retry automatically (`image.generation.retry-attempts`).
>
> **Quality evaluation (module J, off by default)**: With `image.quality.enabled: true`, each image is scored by a multimodal LLM on clarity/relevance/aesthetics (0-10); scores below `image.quality.threshold` (default 6.0) trigger regeneration (<= `image.quality.max-retries` attempts, default 2; feedback merged into the negative prompt, best-scoring image kept). Without a multimodal API key or on call failure, it falls back to resolution-based rule evaluation; evaluation errors never block generation. The score is written to the shot's `qualityScore` field (null when disabled) and returned with single-shot responses, script details, and SSE `SHOT_COMPLETE`. The local generator additionally auto-applies SD1.5/SDXL/FLUX style presets based on `image.local.model` (overridable via `image.local.style-prefix` / `image.local.extra-negative-prompt`).

---

### 7.6 Video Preview & Export

```bash
# 480p quick preview (low resolution + watermark, fast turnaround)
curl -X POST http://localhost:8080/api/video/export/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "16:9", "bgmId": null, "subtitle": true}'

# HD export (720p/1080p, optional BGM and subtitle burning; subtitle language: zh/en/bilingual)
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "resolution": "1080p", "aspectRatio": "9:16", "bgmId": 2, "subtitle": true, "subtitleLanguage": "bilingual", "withLogo": true}'

# Export history (with failure reasons, re-downloadable)
curl http://localhost:8080/api/video/exports/1
```

**Request parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `scriptId` | long | Yes | - | Script ID |
| `resolution` | string | No | `720p` | HD export resolution (720p/1080p); preview is fixed 480p |
| `aspectRatio` | string | No | `16:9` | Aspect ratio (16:9 / 9:16 / 1:1) |
| `bgmId` | long | No | null | BGM to use; null means no music |
| `subtitle` | boolean | No | false | Burn subtitles into frames (automatically ignored when no narration) |
| `subtitleLanguage` | string | No | `zh` | Subtitle language: `zh` original / `en` English (LLM translation) / `bilingual` Chinese+English; narration audio stays Chinese |
| `withLogo` | boolean | No | false | Overlay brand logo corner badge (silently ignored when `video.watermark.logo-path` is unconfigured) |

**Resolution × ratio → output size:**

| Resolution \ Ratio | 16:9 Landscape (Bilibili) | 9:16 Portrait (TikTok) | 1:1 Square (Xiaohongshu) |
|--------------------|---------------------------|------------------------|-------------------------|
| 480p (preview) | 854x480 | 480x854 | 480x480 |
| 720p | 1280x720 | 720x1280 | 720x720 |
| 1080p | 1920x1080 | 1080x1920 | 1080x1080 |

> - All shots must be in READY state before exporting
> - Preview adds a semi-transparent "PREVIEW" watermark with crf 28; formal exports have no watermark with crf 23
> - Script status automatically becomes EXPORTED after a successful HD export
> - Pipeline: image download → Ken Burns + xfade → Edge TTS narration (if available) → BGM mixing (optional: 15% volume + 2s fade-in / 3s fade-out) → logo corner badge (optional) → subtitle burning (optional) → storage

**Response (data.video):**
```json
{
  "videoUrl": "/images/2026/11/07/video_1730966400000.mp4",
  "fileSizeBytes": 2512345,
  "width": 1080, "height": 1920,
  "totalDuration": 21,
  "hasNarration": true, "hasAudio": true, "hasBgm": true, "hasSubtitle": true
}
```

---

### 7.7 BGM Management (Library / AI Recommendation / Upload)

```bash
# BGM list (built-in + current user's uploads; filter with ?category=happy)
curl http://localhost:8080/api/video/bgm

# AI-recommend BGM (LLM analyzes theme sentiment and matches a category track)
curl "http://localhost:8080/api/video/bgm/recommend?theme=heartwarming%20cat%20daily%20life"

# Upload custom BGM (mp3/wav/ogg/m4a/flac)
curl -X POST http://localhost:8080/api/video/bgm/upload \
  -F "file=@./my-music.mp3" \
  -F "name=My Score" \
  -F "category=happy"
```

**Recommendation response:**
```json
{
  "success": true,
  "data": {
    "category": "calm",
    "reason": "A heartwarming cat story suits calm, soothing music",
    "available": true,
    "bgm": { "id": 2, "name": "peaceful piano", "category": "calm", "durationSeconds": 60 }
  }
}
```

> **Built-in music library**: Place royalty-free tracks named `category__name.mp3` into `./data/bgm/`
> (e.g. `happy__sunny_morning.mp3`); auto-registered on startup. Categories: `happy` / `sad` / `epic` / `calm` / `tense`.
> The project ships 5 programmatically synthesized royalty-free chord pads (one per category, ~42s); regenerate with `tools/generate-preset-bgm.ps1` or replace with real tracks.

---

### 7.8 Subtitle System (SRT Export / Preview / Multilingual)

Auto-generates timed subtitles from shot narrations (timeline precisely aligned with xfade transitions: switching at transition midpoints, adjacent cues seamless and non-overlapping); multilingual support: `zh` original / `en` English (LLM translation) / `bilingual` two-line Chinese+English:

```bash
# Generate SRT subtitle file (uploaded to storage + content returned, ready for social platforms)
curl -X POST http://localhost:8080/api/video/subtitle/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1}'

# Generate English subtitles (LLM-translated narration; falls back to original on failure)
curl -X POST http://localhost:8080/api/video/subtitle/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "language": "en"}'

# Render a single-frame subtitle preview image (first shot image + target ratio + first cue; language optional)
curl -X POST http://localhost:8080/api/video/subtitle/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "9:16", "language": "bilingual", "style": {"fontSize": 28, "position": "bottom"}}'
```

**generate response:**
```json
{
  "success": true,
  "data": {
    "srtUrl": "/images/2026/11/07/script_1_subtitle_1730966400000.srt",
    "srtContent": "1\n00:00:00,000 --> 00:00:03,600\nThe first ray of morning sunlight\n\n...",
    "cueCount": 5,
    "language": "zh"
  }
}
```

**preview optional style parameters:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `fontName` | string | `Microsoft YaHei` (configurable via `video.subtitle.font-name`) | Font name, must be installed |
| `fontSize` | int | `24` | Font size (1280x720 coordinate system, auto-scaled) |
| `fontColor` | string | `#FFFFFF` | Font color (#RRGGBB) |
| `outlineColor` | string | `#000000` | Outline color |
| `outlineWidth` | int | `2` | Outline width (pixels) |
| `position` | string | `bottom` | Position: `bottom` / `center` / `top` |

**preview response:**
```json
{ "success": true, "data": { "previewUrl": "/images/2026/11/07/subtitle_preview_1730966400000.png", "width": 720, "height": 1280 } }
```

> Subtitle burning happens during export (the `subtitle` + `subtitleLanguage` parameters in 7.6, FFmpeg subtitles filter + libass); the frontend workflow also provides a "Burn Subtitles" toggle, a subtitle language dropdown (Chinese/English/bilingual), plus "Export SRT" and "Subtitle Preview" buttons. Translations are cached in memory per source text, so repeated exports of the same narration do not re-call the LLM.

---

### 7.9 Social Media Export (Platform Presets / Cover Frame)

#### Platform Preset List

`GET /api/video/export/platforms` returns the six-platform specs:

| name | displayName | Ratio | Resolution | Recommended Duration |
|------|-------------|-------|------------|----------------------|
| `DOUYIN` | Douyin (TikTok CN) | 9:16 | 1080x1920 | <=60s |
| `BILIBILI` | Bilibili | 16:9 | 1920x1080 | unlimited |
| `XIAOHONGSHU` | Xiaohongshu (RED) | 1:1 | 1080x1080 | <=60s |
| `KUAISHOU` | Kuaishou | 9:16 | 1080x1920 | <=60s |
| `WEIXIN_VIDEO` | WeChat Channels | 16:9 | 1920x1080 | unlimited |
| `YOUTUBE_SHORTS` | YouTube Shorts | 9:16 | 1080x1920 | <=60s |

#### Export by Platform

```bash
curl -X POST http://localhost:8080/api/video/export/platform \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "platform": "DOUYIN", "bgmId": 2, "subtitle": true, "subtitleLanguage": "zh", "withLogo": false}'
```

- `platform` accepts the enum name (case-insensitive) or the Chinese display name (e.g. `"抖音"`)
- Resolution is fixed to the 1080p tier; exceeding a platform's recommended duration is **not blocked** — the response carries a `durationWarning` hint
- Response data: exportHd result + `platform` spec object (+ `durationWarning`)

#### Generate Cover Frame

```bash
# Extract the middle frame from the latest successful export (default)
curl -X POST http://localhost:8080/api/video/cover/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1}'

# Specify an export record and a frame time
curl -X POST http://localhost:8080/api/video/cover/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "exportId": 12, "timeOffset": 3.5}'
```

**Response:**
```json
{ "success": true, "data": { "coverUrl": "/images/2026/11/08/video_cover_1731000000000.jpg", "timeOffsetSeconds": 10.5, "exportId": 12 } }
```

> The cover URL is written back to the export record (a thumbnail is shown in export history); batch multi-platform export is implemented by the frontend calling `/export/platform` in parallel — API users can invoke it concurrently themselves; afterwards use the ZIP package endpoint below to download everything at once.

#### Batch Package Download (ZIP)

```bash
# Package multiple export records' videos (+ existing covers) into a streaming ZIP download
curl -X POST http://localhost:8080/api/video/export/batch-download \
  -H "Content-Type: application/json" \
  -d '{"exportIds": [12, 13, 14]}' \
  -o video_exports.zip
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `exportIds` | long[] | Yes | Export record IDs, 1-10 items, current user's successful exports only |

- Response: `application/zip` stream (`Content-Disposition: attachment`); validation failures return a JSON error
- ZIP contents: `{index}_{resolution}_{aspect}_{exportId}.mp4` (e.g. `01_1080p_9x16_12.mp4`) + matching covers (if any) + `manifest.txt` (resolution/aspect/duration/BGM/subtitle language)
- Frontend: "Package Download" buttons are available in both the batch export result area and the export history area

---

### 7.10 Video Templates (Module H: Preset Styles / Apply / Custom)

On startup the system idempotently seeds 8 built-in templates: Product Showcase / Storytelling / Knowledge / Travel / Food / Emotional Quotes / Tech / Fitness. Applying a template injects its style guidance (stylePrefix + recommended shot count) into the script-generation System Prompt.

```bash
# Template list (system built-in + current user's custom)
curl http://localhost:8080/api/video/templates

# Template detail
curl http://localhost:8080/api/video/templates/1

# Apply a template to generate a storyboard (response data matches the script structure in 7.1)
curl -X POST http://localhost:8080/api/video/templates/1/apply \
  -H "Content-Type: application/json" \
  -d '{"idea": "A promotional short for wireless earbuds"}'

# Save a custom template
curl -X POST http://localhost:8080/api/video/templates/custom \
  -H "Content-Type: application/json" \
  -d '{"name": "My Healing Style", "category": "story", "description": "Warm and healing", "shotCount": 5, "stylePrefix": "warm healing cinematic style, soft light", "defaultBgmCategory": "calm"}'

# Delete a custom template (system built-in templates cannot be deleted)
curl -X DELETE http://localhost:8080/api/video/templates/9
```

**Template fields:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Template name (required) |
| `category` | string | Category: product/story/knowledge/travel/food/emotion/tech/fitness/other |
| `description` | string | Template description (frontend selection hint) |
| `shotCount` | int | Recommended shot count (default 5) |
| `stylePrefix` | string | Style guidance (English, injected into the script-generation System Prompt) |
| `defaultBgmCategory` | string | Recommended BGM category (happy/sad/epic/calm/tense) |
| `defaultVoice` | string | Recommended TTS voice (reserved) |
| `isSystem` | boolean | Whether system built-in (read-only; built-in templates cannot be deleted) |

> Custom templates are visible/deletable only by their creator; the frontend new-script area provides a template dropdown, and the script header provides "Save as Template" (shot count + current visual style).

---

### 7.11 Agent Tool List (Phase 3 Slice 1: Service Toolification)

Six core capabilities are wrapped as LLM-callable tools via Spring AI `FunctionToolCallback` (`video/agent/VideoAgentToolFactory`); this endpoint provides tool introspection (name/description/input JSON Schema). The tool execution entry point is the director-agent chat in 7.12:

```bash
# Tool list (read-only introspection, anonymously accessible; login checks at tool execution are enforced by the service layer)
curl http://localhost:8080/api/video/agent/tools
```

**Registered tools:**

| Tool name | Purpose | Key inputs |
|-----------|---------|------------|
| `generateStoryboard` | Generate and persist a storyboard script (optional template) | `idea` (required), `templateId` (optional) |
| `enhancePrompt` | Chinese scene description -> English image prompt | `sceneDescription`, `style` (optional) |
| `generateImage` | Single-shot image generation (with retry / optional quality evaluation) | `scriptId`, `shotId`, `style` (optional) |
| `evaluateImage` | Three-dimension image quality scoring (0-10) | `imageUrl` (only this service's storage relative paths, e.g. /images/…; external URLs rejected, SSRF protection), `prompt` |
| `exportVideo` | Video composition export (preview/HD) | `scriptId`, `preview`, `resolution`, `aspectRatio`, `bgmId`, `subtitle`, `subtitleLanguage`, `withLogo` |
| `recommendBgm` | Theme/sentiment-based BGM recommendation | `theme` |

**Response example (single element of the data array):**
```json
{
  "name": "generateStoryboard",
  "description": "根据创意想法生成分镜脚本并保存，返回脚本ID与镜头列表；可选模板ID按模板风格生成",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"idea\":{\"type\":\"string\",\"description\":\"...\"},...}}"
}
```

> Tools execute synchronously within the request thread (login identity available); execution failures return `{success:false, message}` instead of throwing, so the LLM can perceive failures and adjust its strategy. Tools reuse the same service logic as the existing REST endpoints (ownership checks / retries / quality evaluation behave identically).

---

### 7.12 Director Agent Chat (Phase 3 Slice 2: ReAct + Human Confirmation)

`DirectorAgentService` uses `ChatClient.mutate()` to mount the director system prompt and the six tools above; Spring AI's built-in tool-calling loop is ReAct (think → call tool → observe → continue). `exportVideo` is protected by the `ConfirmationGateToolCallback` human confirmation gate. The studio page has a built-in "AI Director" chat panel at the bottom (with confirm buttons).

**Request parameters (POST /api/video/agent/chat, login Cookie required):**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `message` | string | Yes | User message (blank rejected) |
| `sessionId` | string | No | Session ID; auto-generated when omitted and returned in the response — carry it in subsequent requests to continue the context |
| `confirmedTools` | string[] | No | Tool names explicitly confirmed for this request (e.g. `["exportVideo"]`); effective only for the current request |

```bash
# Round 1: plain chat / creative instruction
curl -X POST http://localhost:8080/api/video/agent/chat \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"message":"Make a heartwarming cat daily-life short video, 3 shots"}'

# Clear conversation history
curl -X DELETE http://localhost:8080/api/video/agent/chat/{sessionId} -b "JSESSIONID=..."
```

**Response example (with pending confirmations):**
```json
{
  "success": true,
  "data": {
    "reply": "Storyboard generated (ID: 5, 3 shots). Next: generate images per shot… export requires your confirmation.",
    "sessionId": "b7b55c50-…",
    "pendingConfirmations": [
      { "tool": "exportVideo", "input": "{\"scriptId\":5,\"preview\":true,…}", "reason": "导出视频将执行 FFmpeg 合成（耗时数分钟）" }
    ]
  }
}
```

**Confirmation flow (two rounds):**
1. User asks to export → the agent's `exportVideo` call is intercepted by the gate; the tool returns `{success:false, requiresConfirmation:true}`; the agent relays the operation and parameters and asks for confirmation; the response's `pendingConfirmations` is non-empty and the frontend renders a "Confirm exportVideo" button
2. User clicks confirm → the frontend resends the same message with `confirmedTools:["exportVideo"]` → the gate passes and the tool actually executes (preview/HD composition); the agent relays the result

```bash
# Round 2: explicit confirmation
curl -X POST http://localhost:8080/api/video/agent/chat \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"message":"Confirm export","sessionId":"b7b55c50-…","confirmedTools":["exportVideo"]}'
```

> Notes: only `exportVideo` is gated (takes minutes); creative-loop tools such as generateImage are not gated. Conversation history is in-memory (cleared on restart), isolated per userId:sessionId, capped at 20 messages; execution is synchronous in the request thread — multi-round tool calls can make a single request slow (client timeout >= 120s recommended). Not logged in / blank message returns 400 `{success:false,message}`.

---

### 7.13 Multi-Agent Orchestration (Phase 3 Slice 3: Specialist Split + Async Pipeline)

Four specialist agents — screenwriter / artist / audio / editor (`SpecialistAgentFactory`, each with its own system prompt + tool subset) — are delegated to the director as "specialist-as-tool"; `AgentOrchestrationService` runs the one-click pipeline asynchronously on a dedicated thread pool, capturing the SecurityContext at submission and explicitly propagating it to worker threads (login-dependent tools work inside async threads).

**Specialist responsibilities (tool subsets mapped to the existing 6 tools; TTS/mixing are embedded in the exportVideo pipeline, no extra tools):**

| Specialist | Delegate tool | Tool subset |
|------------|---------------|-------------|
| Screenwriter | `delegateToScreenwriter` | generateStoryboard, enhancePrompt |
| Artist | `delegateToArtist` | generateImage, evaluateImage |
| Audio | `delegateToAudio` | recommendBgm |
| Editor | `delegateToEditor` | exportVideo (with confirmation gate) |

**Endpoints (login Cookie required, service-layer checks):**

| Endpoint | Description |
|----------|-------------|
| `POST /api/video/agent/orchestrate` | Submit a task, body `{idea (required), templateId (optional)}`, immediately returns `{taskId, status:RUNNING}` |
| `GET /api/video/agent/orchestrate/{taskId}` | Poll status: `{taskId, status, reply, pendingConfirmations, error}` |
| `POST /api/video/agent/orchestrate/{taskId}/confirm` | Only valid in AWAITING_CONFIRMATION (atomic CAS transition; concurrent/duplicate confirms take effect only once); resumes with confirmedTools=[exportVideo]; the resume replays the original task + previous reply + pending operation inputs, ensuring the confirmed operation is exactly what gets executed |

**State machine:** `RUNNING → COMPLETED / FAILED`; when the gate triggers: `RUNNING → AWAITING_CONFIRMATION →(confirm)→ RUNNING → COMPLETED / FAILED`. Tasks are in-memory (cleared on restart), capped at 100, with per-userId ownership checks (cross-user access returns 400).

```bash
# 1. Submit one-click pipeline
curl -X POST http://localhost:8080/api/video/agent/orchestrate \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"idea":"A heartwarming cat daily-life short, 3 shots"}'
# → {"success":true,"data":{"taskId":"cea1b607-…","status":"RUNNING",…}}

# 2. Poll (3s interval recommended)
curl http://localhost:8080/api/video/agent/orchestrate/cea1b607-… -b "JSESSIONID=..."

# 3. Resume when awaiting confirmation
curl -X POST http://localhost:8080/api/video/agent/orchestrate/cea1b607-…/confirm -b "JSESSIONID=..."
```

**Response example (AWAITING_CONFIRMATION):**
```json
{
  "success": true,
  "data": {
    "taskId": "ad2c6556-…",
    "status": "AWAITING_CONFIRMATION",
    "reply": "Script and images are ready. Export will run FFmpeg composition (takes minutes), please confirm…",
    "pendingConfirmations": [ { "tool": "exportVideo", "input": "{\"scriptId\":1,\"preview\":true,…}", "reason": "…" } ],
    "error": null
  }
}
```

> Gate behavior in the delegation chain: specialists have no confirmedTools, so the editor's exportVideo is always intercepted; the specialist relays it and the director asks the user for confirmation; on confirm-resume the director calls its own exportVideo directly (confirmed, gate passes) to finish the export. The studio's "AI Director" panel provides a "One-click Pipeline" button (3s polling after submission, confirm buttons rendered when awaiting confirmation). Multi-round delegation is slow (minutes); async execution never blocks the HTTP request. Not logged in / blank idea / cross-user / wrong state all return 400.

---

## VIII. Complete Testing Workflow

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

### Step 5: Image Generation
```bash
# Generate image (requires DASHSCOPE_API_KEY configured)
curl -X POST http://localhost:8080/api/image/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"A cute orange cat napping in the sun","size":"1024x1024","n":1}'

# View image generation history
curl http://localhost:8080/api/image/records
```

### Step 6: AI Short Video Workflow (full pipeline)
```bash
# 1. Generate storyboard script
SCRIPT_ID=$(curl -s -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea":"A heartwarming story about a ginger cat"}' | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)

# 2. Batch enhance prompts (optional, time-consuming)
curl -X POST http://localhost:8080/api/video/enhance-prompt/script/$SCRIPT_ID \
  -H "Content-Type: application/json" -d '{"style":"CINEMATIC_REALISM"}'

# 3. Batch parallel image generation (SSE real-time progress)
curl -N "http://localhost:8080/api/video/images/script/$SCRIPT_ID/stream?style=CINEMATIC_REALISM"

# 4. 480p quick preview
curl -X POST http://localhost:8080/api/video/export/preview \
  -H "Content-Type: application/json" \
  -d "{\"scriptId\":$SCRIPT_ID,\"aspectRatio\":\"16:9\",\"bgmId\":null}"

# 5. HD export (optional BGM)
BGM_ID=$(curl -s "http://localhost:8080/api/video/bgm" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d "{\"scriptId\":$SCRIPT_ID,\"resolution\":\"1080p\",\"aspectRatio\":\"16:9\",\"bgmId\":$BGM_ID}"

# 6. View export history
curl http://localhost:8080/api/video/exports/$SCRIPT_ID
```

---

## IX. Testing with Postman

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
| 23 | Generate Image | POST | `{{base_url}}/api/image/generate` | `{"prompt":"A cute cat","size":"1024x1024","n":1}` |
| 24 | Get Image Records | GET | `{{base_url}}/api/image/records` | - |
| 25 | Get Image Records By Session | GET | `{{base_url}}/api/image/records/{{session_id}}` | - |
| 26 | Generate Story Script | POST | `{{base_url}}/api/video/scripts/generate` | `{"idea":"A heartwarming cat story"}` |
| 27 | List Video Scripts | GET | `{{base_url}}/api/video/scripts` | - |
| 28 | Get Script Detail | GET | `{{base_url}}/api/video/scripts/{{script_id}}` | - |
| 29 | Enhance Prompt | POST | `{{base_url}}/api/video/enhance-prompt` | `{"sceneDescription":"A cat at sunset","style":"CINEMATIC_REALISM"}` |
| 30 | Enhance Script Prompts | POST | `{{base_url}}/api/video/enhance-prompt/script/{{script_id}}` | `{"style":"ANIME"}` |
| 31 | Generate Shot Image | POST | `{{base_url}}/api/video/images/shot/{{script_id}}/{{shot_id}}` | `{"style":"CINEMATIC_REALISM"}` |
| 32 | Batch Images (SSE) | GET | `{{base_url}}/api/video/images/script/{{script_id}}/stream?style=CINEMATIC_REALISM` | - |
| 33 | List BGM | GET | `{{base_url}}/api/video/bgm` | - |
| 34 | Export Preview | POST | `{{base_url}}/api/video/export/preview` | `{"scriptId":1,"aspectRatio":"16:9"}` |
| 35 | Export HD | POST | `{{base_url}}/api/video/export/hd` | `{"scriptId":1,"resolution":"1080p","aspectRatio":"9:16","bgmId":1}` |
| 36 | Export History | GET | `{{base_url}}/api/video/exports/{{script_id}}` | - |
| 37 | Generate SRT | POST | `{{base_url}}/api/video/subtitle/generate` | `{"scriptId":1}` |
| 38 | Subtitle Preview | POST | `{{base_url}}/api/video/subtitle/preview` | `{"scriptId":1,"aspectRatio":"16:9"}` |
| 39 | List Platforms | GET | `{{base_url}}/api/video/export/platforms` | - |
| 40 | Export for Platform | POST | `{{base_url}}/api/video/export/platform` | `{"scriptId":1,"platform":"DOUYIN","subtitle":true}` |
| 41 | Generate Cover | POST | `{{base_url}}/api/video/cover/generate` | `{"scriptId":1}` |
| 42 | List Agent Tools | GET | `{{base_url}}/api/video/agent/tools` | - |
| 43 | Director Agent Chat | POST | `{{base_url}}/api/video/agent/chat` | `{"message":"Make a cat daily-life short video","sessionId":"optional","confirmedTools":["exportVideo"]}` |
| 44 | Clear Director Chat | DELETE | `{{base_url}}/api/video/agent/chat/{{agent_session_id}}` | - |
| 45 | Submit Orchestration | POST | `{{base_url}}/api/video/agent/orchestrate` | `{"idea":"A heartwarming cat daily-life short","templateId":null}` |
| 46 | Orchestration Status | GET | `{{base_url}}/api/video/agent/orchestrate/{{task_id}}` | - |
| 47 | Confirm Orchestration | POST | `{{base_url}}/api/video/agent/orchestrate/{{task_id}}/confirm` | - |

---

## X. Access H2 Database Console

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

-- View image generation records
SELECT * FROM image_records ORDER BY create_time DESC;

-- Video studio: story scripts and shots
SELECT id, title, theme, status FROM story_scripts;
SELECT id, script_id, shot_order, image_status FROM story_shots ORDER BY script_id, shot_order;

-- Video studio: prompt optimizations / exports / BGM
SELECT * FROM image_prompt_optimizations ORDER BY id DESC LIMIT 10;
SELECT id, script_id, resolution, aspect_ratio, status FROM video_exports ORDER BY created_at DESC;
SELECT id, name, category, is_built_in, user_id FROM bgm_tracks;
```

---

## XI. Configuration Guide

### 11.1 Image Generation Configuration

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key}  # Get from Alibaba Cloud Bailian Platform
```

### 11.2 Storage Configuration

```yaml
storage:
  type: local  # local / oss / minio / fastdfs
  local:
    base-path: ./uploads
    access-path: /images
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

### 11.3 Model Configuration

```yaml
spring:
  profiles:
    active: deepseek,openai,h2  # Combine: ollama/openai/deepseek + h2/postgresql
```

### 11.4 RAG Configuration

```yaml
rag:
  embedding-model: ollama    # embedding: ollama / openai / dashscope (Tongyi cloud); config + restart
  store-type: memory         # memory (in-memory) or pgvector (persistent)
  dashscope:                 # used when embedding-model=dashscope (DeepSeek has no embedding API; cloud vectorization via Tongyi)
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v4
    dimensions: 768          # must match pgvector.dimensions; vectors differ across providers — clear and re-upload after switching
  pgvector:
    dimensions: 768          # nomic-embed-text / text-embedding-v4 dimensions (openai is 1536)
    initialize-schema: true  # auto-create tables
```

### 11.5 Context Length

```yaml
chat:
  max-context-messages: 10   # Retain last 10 conversation rounds
```

### 11.6 AI Short Video Studio Configuration

```yaml
image:
  generator: dashscope               # Image source: dashscope (cloud Tongyi Wanxiang) / local (local SD/FLUX)
  local:                              # Local SD WebUI config
    base-url: http://127.0.0.1:7860
    steps: 25
    cfg-scale: 7.0
    timeout: 120                      # Local generation timeout (seconds)
    style-prefix: ""                  # Style prefix override (empty = built-in preset by model name: SD1.5/SDXL/FLUX)
    extra-negative-prompt: ""         # Negative prompt override (empty = built-in preset by model name)
  generation:
    parallelism: 3                     # Parallel generation count (avoid cloud API rate limits)
    retry-attempts: 1                 # Retry attempts on failure
    retry-delay-ms: 2000              # Retry delay (ms)
    total-timeout-seconds: 300        # Batch generation total timeout (seconds)
    default-size: 1280x720           # Default image size
  quality:                            # Module J: image quality evaluation (off by default)
    enabled: false                    # When enabled, low-scoring images are regenerated
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI-compatible multimodal endpoint
    api-key: ${DASHSCOPE_API_KEY:}    # Empty = rule-based fallback
    model: qwen-vl-max                # Multimodal evaluation model
    threshold: 6.0                    # Passing threshold (0-10)
    max-retries: 2                    # Max retries for low scores

video:
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}   # FFmpeg executable path (absolute path recommended on Windows; >=4.3 required)
  ffprobe-path: ${FFPROBE_PATH:ffprobe} # ffprobe path (BGM duration probing)
  python-command: ${PYTHON_CMD:python}  # Python interpreter running edge-tts (absolute path recommended on Windows)
  compose-timeout-minutes: 8        # Timeout per FFmpeg command (minutes)
  watermark:                        # Social export: brand watermark
    logo-path: ""                    # Logo corner image path (transparent PNG, width <=300px recommended; empty = disabled)
  subtitle:                          # Module F: subtitle burning
    font-name: Microsoft YaHei       # Subtitle font (must be installed; Linux/Docker can use Source Han Sans CN)

bgm:
  library-path: ./data/bgm          # Built-in music library dir, filename format category__name.mp3
                                    # (e.g. happy__sunny_morning.mp3), auto-scanned on startup
```

---

## XII. Important Notes

1. **Login Requirement**: Chat and document endpoints require login; returns 401/403 if unauthenticated
2. **Session Ownership**: Each session bound to its creator; users can only manage their own
3. **Password Security**: RSA-encrypted transmission, BCrypt hash storage
4. **Captcha**: 4-char random alphanumeric image (answer stored only in session), single-use, case-insensitive; login rate limit: max 5 failures per username+IP within 60s (429 when exceeded)
5. **SSE Streaming**: Use `POST /api/chat/stream` for real-time streaming responses
6. **RAG Knowledge Base**: Upload documents, then set `ragEnabled: true` in chat requests
7. **Context Limit**: Default 10 conversation rounds; use compression to free space
8. **Model Presets**: Saved per-user per-model, auto-applied during conversations
9. **File Upload**: Max 10MB, supports TXT/PDF/MD formats
10. **Database Persistence**: H2 file mode stored in `./data/chatdb.mv.db`
11. **Image Generation**: Requires `DASHSCOPE_API_KEY` configuration, images auto-stored to configured storage service
12. **Multi-Storage Switching**: Switch between local/OSS/MinIO/FastDFS via `storage.type`, no code changes needed
13. **Video Studio Dependencies**: Video composition requires FFmpeg (with ffprobe); paths are configured via `video.ffmpeg-path` / `video.ffprobe-path` (defaults fall back to PATH, overridable via `FFMPEG_PATH`/`FFPROBE_PATH` env vars), version >=4.3 (xfade) / >=4.4 (BGM mixing) required; narration requires `pip install edge-tts`, with the interpreter specified by `video.python-command` (overridable via `PYTHON_CMD`; automatically skipped when missing, silent video output)
14. **SSE Batch Generation**: Use native EventSource on `GET /api/video/images/script/{id}/stream` (EventSource is GET-only); event types in 7.5
15. **Prompt Changes**: Updating a shot's imagePrompt clears old image references; images must be regenerated before video export
16. **BGM Permissions**: Users can only use built-in BGM and their own uploads; export automatically lowers BGM volume (~ -16dB) with fade in/out
17. **Export Prerequisites**: All shots must be in READY state, otherwise the response lists which shots are not ready
18. **Subtitle Burning**: Requires the configured CJK font installed (default Microsoft YaHei); the subtitle parameter is automatically ignored when no narration exists; burning happens during export, no separate call needed
19. **Platform Export**: Presets are fixed to the 1080p tier; exceeding the recommended duration only warns without blocking; the cover defaults to the middle frame of the latest successful export and is written back to the record
20. **Image Quality Evaluation**: `image.quality.enabled` defaults to false (generation flow unchanged when off); when enabled, low scores trigger <=2 retries keeping the best score, returned via the shot's qualityScore field; falls back to rule-based evaluation without a multimodal API, and evaluation errors never block generation
21. **Agent Tools**: `GET /api/video/agent/tools` is introspection-only, no execution (anonymously readable, following the existing /api/video/** policy); the tool execution entry point is the director chat in 7.12, with login checks at execution enforced by the service layer; tools run synchronously in the request thread — SecurityContext propagation is only needed for async orchestration
22. **Director Agent**: `POST /api/video/agent/chat` requires login (service-layer check, anonymous gets 400); only `exportVideo` has a human confirmation gate, and `confirmedTools` is effective only for the current request (no server-side pending token); conversation history is in-memory, cleared on restart, capped at 20 messages; multi-round tool calls can be slow — client timeout >= 120s recommended
23. **Multi-Agent Orchestration**: the `/orchestrate` endpoints require login; tasks are in-memory, cleared on restart, capped at 100 (eviction re-checks status atomically — running tasks are never evicted), with per-userId ownership checks; async worker threads explicitly propagate the SecurityContext (login identity available inside tools); confirm is only valid in AWAITING_CONFIRMATION with CAS guarding against concurrent double execution, and the resume carries the original task plus pending operation inputs; specialist delegation is stateless (taskDescription must carry full context); the evaluateImage tool only accepts storage relative paths (resolved same-origin via `agent.tools.self-base-url`); the frontend polls every 3s — minute-level pipeline duration is normal
24. **v2.0.0 Security Hardening**: H2 Console disabled by default; Actuator exposes only health/info (others require authentication); `/images/**` anonymously readable (UUID filenames unguessable; required by compose server-side self-download); model/generator switching requires login; all chat session read/write endpoints enforce ownership; Markdown rendering sanitized with DOMPurify; legacy `POST /api/video/generate|generate-image|generate-all-images|compose` and `GET /api/chat/send` removed; Docker deployments must set the PostgreSQL password via `POSTGRES_PASSWORD` in `.env`

---

## XIII. FAQ

**Q: Why do chat endpoints return 401?**
A: Login first via `/api/auth/login` and ensure cookies are saved.

**Q: How to consume SSE stream in frontend?**
A: Use `fetch` + `ReadableStream` (recommended for POST), or `EventSource` (GET only).

**Q: RAG context enhancement not working?**
A: Ensure documents are uploaded, `ragEnabled` is `true`, and the embedding model is available: ollama needs `ollama pull nomic-embed-text`; to avoid local Ollama set `rag.embedding-model: dashscope` (Tongyi cloud, reuses DASHSCOPE_API_KEY) or `openai`, restart to apply; after switching providers, clear old vectors and re-upload documents.

**Q: How to switch AI models?**
A: Web UI (recommended), API `POST /api/model/switch`, or config file.

**Q: Need to restart after switching models?**
A: No, switching takes effect immediately via Web UI or API.

**Q: How do model parameter presets work?**
A: Once saved, custom parameters auto-apply to all conversations with that model for the user.

**Q: Is the RSA public key the same every time?**
A: No, the RSA key pair is regenerated on each application startup.

**Q: Image generation returns 500 error?**
A: Ensure `DASHSCOPE_API_KEY` is correctly configured and your account has sufficient credits. Set it in the `.env` file.

**Q: How to access the generated images?**
A: In local storage mode, images are accessible via `/images/date/filename` (e.g. `/images/2026/07/05/uuid_filename.png`).
When switched to OSS/MinIO, `imageUrl` returns the corresponding cloud storage access address.

**Q: How to switch storage type?**
A: Change `storage.type` in `application.yml`: `local` → `oss` / `minio` / `fastdfs`. No restart required.

**Q: Video export reports FFmpeg errors or timeouts?**
A: Verify the executables pointed to by `video.ffmpeg-path` / `video.ffprobe-path` work (defaults fall back to PATH): `ffmpeg -version`; version >=4.3 required (xfade transitions) — old 4.2 builds bundled with apps like EVCapture cannot be used. Multi-shot 1080p composition is slow by nature; the timeout is controlled by `video.compose-timeout-minutes` (default 8). BGM mixing requires ffmpeg >= 4.4. If narration fails, check that the interpreter pointed to by `video.python-command` has edge-tts installed (on Windows bare `python` may be hijacked by the Microsoft Store alias stub — use an absolute path).

**Q: Narration volume drops after BGM mixing?**
A: The backend uses `amix normalize=0` to keep narration at original volume; BGM is fixed at 15% (~ -16dB) so it never overpowers narration. Try a softer track if the mix feels off.

**Q: How to add built-in BGM?**
A: Place royalty-free tracks named `category__name.mp3` into `./data/bgm/` (e.g. `calm__peaceful_piano.mp3`) and restart to auto-register; or use the frontend "Upload" button / `POST /api/video/bgm/upload`.

**Q: Why is SSE batch image generation a GET endpoint?**
A: Native browser EventSource supports GET only, so the batch endpoint is `GET /api/video/images/script/{id}/stream` with style passed via the `?style=` query parameter.

**Q: How are 9:16 portrait exports cropped?**
A: Each image is first `scale + crop`-ed to the target ratio (center-cropped preserving aspect ratio), then the Ken Burns effect is applied — 16:9 source images exported as 9:16 video are never distorted.

**Q: How is the subtitle timeline computed?**
A: Each cue starts at the previous transition's midpoint and ends at the next transition's midpoint (switching in sync with xfade transitions; adjacent cues are seamless and non-overlapping); the actual video duration has transition overlaps deducted, keeping subtitles strictly aligned with the picture.

**Q: Garbled text or wrong font after subtitle burning?**
A: The subtitle font must be installed (default Microsoft YaHei); on Linux/Docker set `video.subtitle.font-name` to `Source Han Sans CN` / `Noto Sans CJK SC`, and confirm with `POST /api/video/subtitle/preview`.

**Q: How do I batch-export for multiple platforms?**
A: The frontend "Batch Three Platforms" button issues three parallel calls to `POST /api/video/export/platform` (Douyin/Bilibili/Xiaohongshu); API users can invoke the endpoint concurrently themselves (one platform per call). FFmpeg encoding is CPU-intensive — keep concurrency to 2-3.

**Q: How to enable automatic image quality evaluation?**
A: Set `image.quality.enabled: true` (reuses DASHSCOPE_API_KEY by default, via the qwen-vl-max multimodal endpoint). After generation, images are scored on clarity/relevance/aesthetics; scores below the threshold (default 6.0) trigger <=2 retries keeping the best score; shot cards show a quality badge. Without an API key it falls back to resolution-based rule evaluation; keep the default false to leave the generation flow completely unaffected.
