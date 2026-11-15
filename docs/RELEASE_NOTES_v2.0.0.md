<!--
GitHub Release 发布信息（本注释不属于正文，复制时可跳过）：
- Tag: v2.0.0（仓库已有 v1.0.0 初始稳定版 tag，勿复用）
- Title: v2.0.0 - AI Video Studio & Multi-Agent Orchestration with Security Hardening
- 正文：以下英文段 + 中文段（可按仓库惯例只保留英文段）
-->

## v2.0.0 - AI Video Studio & Multi-Agent Orchestration with Security Hardening

### Project Overview

The second stable release turns the chat demo into a full AI short-video production platform: idea → storyboard → parallel image generation → narration/BGM → FFmpeg composition → multi-platform export, now drivable end-to-end by a Director Agent with four specialist agents. It also ships a comprehensive security hardening pass (5 Critical + 10 Major findings from a full-repository audit, all fixed and regression-tested) and several breaking API changes — hence the major version bump from v1.0.0.

### Core Feature List

1. **AI Short Video Studio (idea → published MP4)**
   - Storyboard generation (DeepSeek/Ollama, structured output) with 8 visual style presets and template-guided prompts
   - Script editor: shot CRUD, drag-and-drop reordering, save/load, AI prompt polishing
   - Parallel image generation (DashScope Wanxiang / local SD WebUI & FLUX) with SSE progress, auto-retry and optional multimodal quality scoring with low-score regeneration
   - FFmpeg composition: Ken Burns motion, xfade transitions, multi-resolution & multi-ratio (16:9 / 9:16 / 1:1 …), watermark/Logo overlay, cover frame capture
   - Subtitles: SRT/ASS with xfade-aligned timeline, zh/en/bilingual, burn-in; Edge TTS narration; BGM library (scan/upload/LLM recommendation) with fade-in/out mixing
   - Social export: six platform presets, export history, ZIP batch download (videos + covers + manifest)
   - Video templates: 8 system presets + per-user custom templates
2. **Multi-Agent Orchestration (Phase 3)**
   - Six service tools + read-only introspection endpoint (`GET /api/video/agent/tools`)
   - Director Agent: ReAct tool-calling chat with a human confirmation gate on `exportVideo`
   - Four specialists (screenwriter / art / audio / editing) delegated as tools, each with its own prompt and tool subset
   - One-click pipeline: async orchestration with SecurityContext propagation, per-user task ownership, state machine (RUNNING / COMPLETED / AWAITING_CONFIRMATION / FAILED) and confirm-to-resume
3. **Platform Enhancements since v1.0.0**
   - RAG knowledge base (in-memory or PgVector) with document upload/delete; Markdown export (single answer & whole session); SSE streaming chat; desktop notifications; AI image generation with local/OSS/MinIO storage backends
4. **Robustness & Portability**
   - FFmpeg/ffprobe/Edge-TTS probes now enforce real process timeouts (output redirection + `waitFor(timeout)` + force-kill); batch image generation has a total timeout with a cancellation flag so late results never overwrite FAILED state
   - LLM calls moved out of long DB transactions; context-compression destructive writes wrapped in a single transaction
   - ffmpeg/ffprobe/python default to bare names on PATH (overridable via `FFMPEG_PATH` / `FFPROBE_PATH` / `PYTHON_CMD`), with startup availability warnings

### Breaking Changes

1. **`GET /api/chat/send` removed** (CSRF vector). Use `POST /api/chat/send` (now returns the real `sessionId`) or `POST /api/chat/stream`.
2. **Legacy one-shot video endpoints removed**: `POST /api/video/generate`, `/generate-image`, `/generate-all-images`, `/compose` (were unauthenticated and SSRF-prone). Replaced by modular APIs: `/api/video/scripts`, `/api/video/images`, `/api/video/export/*`, `/api/video/bgm`, `/api/video/subtitle`, `/api/video/templates`.
3. **Captcha is now an image**: `/api/auth/captcha` returns `{ "image": "data:image/png;base64,…" }`; the plaintext answer is only included when `auth.captcha.debug-text=true` (default `false`, dev/test only).
4. **H2 Console disabled by default** (`spring.h2.console.enabled: false`) and `/h2-console/**` is no longer publicly permitted.
5. **Actuator reduced to `health,info`**; all other endpoints require authentication.
6. **Docker compose requires `POSTGRES_PASSWORD`** in `.env` (no hardcoded default; startup refuses without it); PostgreSQL port 5432 is no longer published to the host.
7. **Tool paths default to PATH lookup** instead of machine-specific absolute paths (see upgrade notes).
8. **Ownership/permission errors on chat endpoints now return 400** uniformly (previously 500 on some paths); `/stream` emits an `error` SSE event instead of failing the request.

### Security Hardening Checklist

- Image captcha + login rate limiting (username+IP sliding window, 5 failures/min → HTTP 429)
- Session fixation protection: session ID changed on successful login/registration
- IDOR protection: every chat session read/write enforces user ownership; cross-user access → 400
- Model/generator switching requires login (anonymous global-toggle DoS closed)
- Stored XSS closed: Markdown rendered through locally vendored DOMPurify 3.2.6; error branches use `textContent`; code-block language whitelist + escaping
- H2 Console closed (previously empty-password `permitAll` → unauthenticated RCE risk)
- Legacy unauthenticated video controller deleted (unauthorized SSRF via `/compose`)
- Actuator sensitive endpoints (env/loggers/…) no longer exposed
- `/images/**` anonymously readable by design (UUID filenames unguessable; required by server-side compose self-download)
- RAG in-memory document deletion now also removes stored vectors
- API keys masked in logs; outbound OpenAI-compatible calls have connect/read timeouts
- docker-compose credentials fully environment-driven; strong DB password mandatory; DB port not exposed
- Production logging at INFO; `data/chatdb.trace.db` removed from git tracking

### Upgrade Notes from v1.0.0

1. **API clients**: migrate `GET /api/chat/send` → `POST`; replace legacy video endpoints with the modular APIs above; render the captcha from the base64 `image` field; handle 400 for ownership errors and 429 with backoff. The bundled web UI is already updated — only custom frontends/integrations need changes.
2. **Login automation/tests**: set `auth.captcha.debug-text=true` (dev profiles only) to keep receiving the plaintext captcha; never enable it in production.
3. **H2 Console**: if you need it for local debugging, set `spring.h2.console.enabled: true` in `application-h2.yml` **and** access it with a logged-in session (anonymous access is no longer permitted).
4. **Monitoring**: only `/actuator/health` and `/actuator/info` remain public; re-authenticate or relocate any scrapers hitting other actuator endpoints.
5. **Docker**: add a strong `POSTGRES_PASSWORD` to `.env` before `docker compose up`; connect to PostgreSQL via the app network or `docker exec` (5432 is no longer mapped to the host).
6. **FFmpeg/Python**: ensure `ffmpeg`/`ffprobe` (≥4.3 for xfade, ≥4.4 for BGM mixing) and `python` (+`edge-tts`) are on PATH, or set `FFMPEG_PATH`/`FFPROBE_PATH`/`PYTHON_CMD` (or `video.*` config) to absolute paths. Missing tools only warn at startup; video features degrade gracefully.
7. **Database**: no manual migration needed — JPA `ddl-auto: update` creates the new tables (`story_scripts`, `story_shots`, `image_prompt_optimizations`, `video_exports`, `bgm_tracks`, `video_templates`) automatically; back up your H2/PostgreSQL data before upgrading as usual.

### Documentation Improvements

- README and API_TEST fully synchronized in Chinese & English (captcha image flow, removed endpoints, login requirements, new config defaults, security notes)
- VIDEO_STUDIO_PLAN records the complete release audit and fix history (5 Critical + 10 Major + minors, plus pre-commit re-review)

### Tech Stack

Java 17 | Spring Boot 3.4.1 | Spring AI 1.0.0 | Maven
Spring Data JPA | Thymeleaf | H2 / PostgreSQL + PgVector
FFmpeg (≥4.3) | Edge TTS | DOMPurify 3.2.6 (local vendor)
RSA Encryption | BCrypt Password Hashing

### Verification

- 148 automated tests green (30 added during the hardening & re-review passes; 1 network-dependent TTS case skipped by default)
- 21 real end-to-end smoke checks ALL PASS over real HTTP + local storage + DeepSeek + FFmpeg (login → studio → script → images → preview/export → batch download), covering the previously untested frontend-contract and self-download paths

---

## v2.0.0 - AI 短视频工作室与多 Agent 编排（含安全加固）

### 项目概述

第二个稳定版将聊天演示升级为完整的 AI 短视频生产平台：创意 → 分镜脚本 → 并行生图 → 配音/BGM → FFmpeg 合成 → 多平台导出，并可由导演 Agent 携四专家 Agent 端到端自动驱动。本版本同时完成全库安全审查加固（5 Critical + 10 Major 全部修复并回归验证），包含若干破坏性 API 变更，故自 v1.0.0 升主版本。

### 核心功能清单

1. **AI 短视频工作室（创意 → 成品 MP4）**
   - 分镜脚本生成（DeepSeek/Ollama 结构化输出），8 种视觉风格预设与模板引导提示词
   - 脚本编辑器：镜头增删改、拖拽排序、保存/加载、AI 提示词润色
   - 并行图片生成（DashScope 通义万相 / 本地 SD WebUI & FLUX），SSE 进度、自动重试、可选多模态质量评分与低分重生成
   - FFmpeg 合成：Ken Burns 动效、xfade 转场、多分辨率多比例（16:9 / 9:16 / 1:1 …）、水印/Logo 角标、封面截帧
   - 字幕：SRT/ASS 与 xfade 对齐时间轴、中/英/双语、烧录；Edge TTS 旁白；BGM 库（扫描/上传/LLM 推荐）淡入淡出混音
   - 社媒导出：六平台预设、导出历史、ZIP 批量下载（视频+封面+manifest）
   - 视频模板：8 个系统预置 + 用户自定义
2. **多 Agent 编排（Phase 3）**
   - 6 个服务工具 + 只读内省端点（`GET /api/video/agent/tools`）
   - 导演 Agent：ReAct 工具调用对话，`exportVideo` 设人工确认闸门
   - 编剧/美术/音频/剪辑四专家以「专家即工具」委托，各自独立提示词与工具子集
   - 一键成片：异步编排并传播 SecurityContext、任务按用户归属、状态机（RUNNING / COMPLETED / AWAITING_CONFIRMATION / FAILED）与确认续跑
3. **v1.0.0 以来的平台增强**
   - RAG 知识库（内存或 PgVector）文档上传/删除；Markdown 导出（单条/整会话）；SSE 流式聊天；桌面通知；AI 图像生成与 local/OSS/MinIO 多存储
4. **健壮性与可移植性**
   - FFmpeg/ffprobe/Edge-TTS 探测强制真实进程超时（输出重定向 + `waitFor(timeout)` + 强杀）；批量生图总超时配取消标志，迟到结果不再覆盖 FAILED 状态
   - LLM 调用移出长事务；上下文压缩的破坏性多写包入单事务
   - ffmpeg/ffprobe/python 默认裸名走 PATH（可用 `FFMPEG_PATH` / `FFPROBE_PATH` / `PYTHON_CMD` 覆盖），启动时缺失仅告警

### 破坏性变更

1. **移除 `GET /api/chat/send`**（CSRF 载体）。改用 `POST /api/chat/send`（返回真实 `sessionId`）或 `POST /api/chat/stream`。
2. **移除遗留一次性视频端点**：`POST /api/video/generate`、`/generate-image`、`/generate-all-images`、`/compose`（原无登录校验且存在 SSRF）。由分模块 API 承接：`/api/video/scripts`、`/api/video/images`、`/api/video/export/*`、`/api/video/bgm`、`/api/video/subtitle`、`/api/video/templates`。
3. **验证码图片化**：`/api/auth/captcha` 返回 `{ "image": "data:image/png;base64,…" }`；明文答案仅在 `auth.captcha.debug-text=true`（默认 `false`，仅供开发/测试）时附带。
4. **H2 Console 默认关闭**（`spring.h2.console.enabled: false`），且 `/h2-console/**` 不再匿名放行。
5. **Actuator 仅暴露 `health,info`**，其余端点需鉴权。
6. **Docker compose 强制 `POSTGRES_PASSWORD`**（`.env` 必填，无硬编码默认值，未设置拒绝启动）；5432 端口不再映射到宿主机。
7. **工具路径默认走 PATH**，取代机器特定绝对路径（见升级注意事项）。
8. **聊天端点越权/权限错误统一返回 400**（部分路径原为 500）；`/stream` 改发 `error` SSE 事件而非请求失败。

### 安全加固清单

- 图片验证码 + 登录限速（用户名+IP 滑动窗口，5 次失败/分钟 → HTTP 429）
- 会话固定防护：登录/注册成功后更换会话 ID
- IDOR 防护：聊天会话全部读写强制用户归属校验，跨用户访问 → 400
- 模型/生成器切换需登录（关闭匿名全局开关 DoS）
- 存储型 XSS 关闭：Markdown 经本地 vendor DOMPurify 3.2.6 消毒；错误分支 textContent；代码块 language 白名单+转义
- H2 Console 关闭（原空密码 permitAll 存在未授权 RCE 风险）
- 删除遗留无鉴权视频控制器（`/compose` 未授权 SSRF）
- Actuator 敏感端点（env/loggers 等）不再暴露
- `/images/**` 匿名可读为设计行为（UUID 文件名不可枚举；compose 服务端自下载依赖）
- RAG 内存文档删除同步清除向量
- 日志 API Key 脱敏；OpenAI 兼容出站调用带连接/读取超时
- docker-compose 凭据全环境变量化；数据库强密码必填；数据库端口不暴露
- 生产日志 INFO；`data/chatdb.trace.db` 移出 git 跟踪

### 从 v1.0.0 升级注意事项

1. **API 调用方**：`GET /api/chat/send` 迁移为 POST；遗留视频端点替换为上述分模块 API；验证码改渲染 base64 `image` 字段；归属错误按 400 处理、429 需退避重试。自带 Web UI 已同步更新，仅自定义前端/集成需改造。
2. **登录自动化/测试**：开发环境可设 `auth.captcha.debug-text=true` 继续获取明文验证码；生产严禁开启。
3. **H2 Console**：本地调试需在 `application-h2.yml` 设 `spring.h2.console.enabled: true`，**并以登录态会话访问**（不再允许匿名）。
4. **监控**：仅 `/actuator/health` 与 `/actuator/info` 保持公开；抓取其他 actuator 端点的采集器需加鉴权或改道。
5. **Docker**：`docker compose up` 前在 `.env` 设置强 `POSTGRES_PASSWORD`；PostgreSQL 经应用网络或 `docker exec` 连接（5432 不再映射宿主机）。
6. **FFmpeg/Python**：确保 `ffmpeg`/`ffprobe`（xfade 需 ≥4.3、BGM 混音需 ≥4.4）与 `python`（+`edge-tts`）在 PATH 中，或用 `FFMPEG_PATH`/`FFPROBE_PATH`/`PYTHON_CMD`（或 `video.*` 配置）指定绝对路径；缺失仅启动告警，视频功能优雅降级。
7. **数据库**：无需手工迁移——JPA `ddl-auto: update` 自动创建新表（`story_scripts`、`story_shots`、`image_prompt_optimizations`、`video_exports`、`bgm_tracks`、`video_templates`）；升级前照例备份 H2/PostgreSQL 数据。

### 文档改进

- README 与 API_TEST 中英文全量同步（验证码图片流程、移除端点、登录要求、新配置默认值、安全说明）
- VIDEO_STUDIO_PLAN 完整记录 release 审查与修复历史（5 Critical + 10 Major + Minor，含提交前复审）

### 技术栈

Java 17 | Spring Boot 3.4.1 | Spring AI 1.0.0 | Maven
Spring Data JPA | Thymeleaf | H2 / PostgreSQL + PgVector
FFmpeg（≥4.3）| Edge TTS | DOMPurify 3.2.6（本地 vendor）
RSA 加密 | BCrypt 密码哈希

### 验证

- 148 个自动化测试全绿（加固与复审期间新增 30 个；1 个联网 TTS 用例默认跳过）
- 21 项真实端到端冒烟 ALL PASS（真实 HTTP + local 存储 + DeepSeek + FFmpeg：登录 → 工作室 → 建脚本 → 生图 → 预览导出 → 批量下载），覆盖此前无测试的前端契约与服务端自下载链路