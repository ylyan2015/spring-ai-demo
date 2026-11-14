# AI 短视频生成器 — 项目计划

> **项目代号**：video-studio（spring-ai-demo 子模块）
> **定位**：AI 驱动的短视频自动生成工具，面向社交媒体投放
> **技术栈**：Spring Boot 3.4 + Java 17 + Spring AI 1.0 + DeepSeek/Ollama + DashScope/本地SD + FFmpeg + Edge TTS
> **创建日期**：2026-11-07
> **最后更新**：2026-11-14（Phase 2 全部完成；Phase 3 全部完成：切片1 服务 Tool 化 + 切片2 导演 Agent + 切片3 多 Agent 编排：6 工具 + 内省 + ReAct 对话 + 确认闸门 + 四专家委托 + 异步流水线）
> **2026-11-14 FFmpeg 落地**：本机安装 `D:/Program Files/ffmpeg`（≥4.3，含 xfade/libass/libx264），可执行文件路径改为 `video.ffmpeg-path` / `video.ffprobe-path` 配置项。同时修复 `VideoComposeService` 三处只有真跑 FFmpeg 才会暴露的缺陷：xfade 输出标签被写在滤镜名之前（解析成第三个输入）、zoompan 的 z 表达式误用不存在的 `t` 变量（应为 `on` 帧号）、多镜头命令缺 `-t` 导致 `-loop 1` 无限输入永不收尾。回归测试见 `VideoComposeFfmpegTest`。
> **2026-11-14 旁白配音打通**：本机已有 Python 3.14.6（无需重装），补装 edge-tts 7.3.1；`video.python-command` 指向解释器绝对路径后，edge-tts 合成 → apad 补静音拼接 → 音画合并全链路跑通，产物含 aac 音轨。联网用例默认跳过，`-Dtts=true` 执行。
> **2026-11-14 S5 部署加固**：Docker 镜像补齐短视频运行依赖——Dockerfile 加装 ffmpeg/python3+edge-tts（venv）/font-noto-cjk，预置 BGM 入镜像（.dockerignore 放行 data/bgm/）；compose 补 DASHSCOPE_API_KEY 与 FFMPEG_PATH/FFPROBE_PATH/PYTHON_CMD 注入、appdata/uploads 命名卷；docker profile 字幕字体自动切 Noto Sans CJK SC。本机无 Docker，镜像构建验证待有 Docker 的环境执行。

---

## 一、项目概述

将 AI 能力串联成一条短视频生产流水线：输入一句创意想法 → LLM 生成分镜脚本（可自动/可手动编辑） → AI 生成每个镜头的画面（支持多种风格、可本地可云端） → FFmpeg 合成视频（含字幕、转场、配音、BGM） → 多平台适配导出 → 输出成品短视频，投放各社交媒体。

### 核心流水线（增强版）

```
创意想法 (文本)
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 1: 脚本生成                                            │
│  ┌──────────────┐   ┌───────────────┐   ┌────────────────┐   │
│  │ LLM 生成      │->│ 提示词优化     │->│ 脚本编辑器      │   │
│  │ DeepSeek/    │   │ 风格预设      │   │ 增删改/拖拽    │   │
│  │ Ollama 本地   │   │ 负面提示词    │   │ 排序/保存      │   │
│  └──────────────┘   └───────────────┘   └────────────────┘   │
│  输出: Storyboard JSON (title, theme, shots[])                 │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 2: 素材生成                                            │
│  ┌──────────────┐   ┌───────────────┐   ┌────────────────┐   │
│  │ 图片生成      │   │ 旁白配音       │   │ BGM 配乐       │   │
│  │ DashScope    │   │ Edge TTS      │   │ 音乐库/AI推荐  │   │
│  │ 本地 SD/FLUX │   │ 多角色声线    │   │ 音量混合       │   │
│  │ 多风格预设   │   │               │   │                │   │
│  └──────────────┘   └───────────────┘   └────────────────┘   │
│  支持并行生成 + SSE 进度推送 + 失败自动重试                      │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 3: 视频合成与导出                                       │
│  ┌──────────────┐   ┌───────────────┐   ┌────────────────┐   │
│  │ FFmpeg 合成   │   │ 字幕烧录      │   │ 多平台导出     │   │
│  │ Ken Burns    │   │ SRT 生成      │   │ 16:9/9:16/1:1 │   │
│  │ 转场特效     │   │ 硬字幕/样式   │   │ 水印/封面      │   │
│  │ 音视频合并   │   │               │   │                │   │
│  └──────────────┘   └───────────────┘   └────────────────┘   │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
                    成品短视频 (MP4)
                    可直接投放社交媒体
```

### 1.1 社交媒体平台适配目标

| 平台 | 比例 | 分辨率 | 时长限制 | 特殊要求 |
|------|------|--------|---------|----------|
| 抖音/TikTok | 9:16 | 1080x1920 | 15s-10min | 竖屏、大字幕、前3s吸引 |
| B站 | 16:9 | 1920x1080 | 无限制 | 横屏、高清、封面图 |
| 小红书 | 3:4 / 1:1 | 1080x1440 / 1080x1080 | ≤60s | 方形/竖屏、精致封面 |
| 快手 | 9:16 | 1080x1920 | 15s-10min | 竖屏、接地气风格 |
| 视频号 | 16:9 / 9:16 | 1920x1080 | ≤30min | 横竖均可 |
| YouTube Shorts | 9:16 | 1080x1920 | ≤60s | 竖屏、英文字幕 |

---

## 二、已完成模块（Phase 1）

### 2.1 已实现的功能

| 功能 | 文件 | 状态 |
|------|------|------|
| 分镜脚本生成 | `video/VideoScriptService.java` | ✅ 完成 |
| 结构化输出 (BeanOutputConverter) | `video/Storyboard.java`, `video/Shot.java` | ✅ 完成 |
| 单张图片生成 | `service/ImageService.java` (已有) | ✅ 复用 |
| 批量图片生成 | `video/VideoController.java` `/generate-all-images` | ✅ 完成 |
| FFmpeg 视频合成 | `video/VideoComposeService.java` | ✅ 完成 |
| Ken Burns 动效 | `VideoComposeService.composeVideoWithFfmpeg()` | ✅ 完成 |
| 交叉淡入淡出转场 | `VideoComposeService` xfade filter | ✅ 完成 |
| Edge TTS 旁白配音 | `VideoComposeService.generateTtsAudio()` | ✅ 完成 |
| 音视频合并 | `VideoComposeService.mergeVideoAndAudio()` | ✅ 完成 |
| 前端页面 | `templates/video-studio.html` | ✅ 完成 |
| 进度条 + 视频播放器 | `video-studio.html` | ✅ 完成 |

### 2.2 已有文件清单

```
src/main/java/com/github/ylyan2015/springaidemo/video/
├── Storyboard.java              # 分镜脚本 DTO (title, theme, shots)
├── Shot.java                    # 单个镜头 DTO (order, sceneDescription, cameraMovement, durationSeconds, narration, imagePrompt)
├── VideoScriptService.java      # 脚本生成 + 图片生成调度
├── VideoComposeService.java     # FFmpeg 视频合成 + TTS 配音 + 音视频合并
└── VideoController.java         # REST API (generate, generate-image, generate-all-images, compose)

src/main/resources/templates/
└── video-studio.html            # 前端页面
```

### 2.3 已有 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/video/generate` | 输入创意 → 生成分镜脚本 + 第一张图 |
| POST | `/api/video/generate-image` | 为单个镜头生成图片 |
| POST | `/api/video/generate-all-images` | 批量生成所有镜头图片 |
| POST | `/api/video/compose` | 合成视频（图片+时长+旁白 → MP4） |

---

## 三、待实现模块（Phase 2 — MVP 增强）

### 3.0 实现进度总览（2026-11-07 更新）

| 模块 | 状态 | 核心实现文件 | API 前缀 |
|------|------|--------------|----------|
| A. 脚本编辑器 | ✅ 已实现 | `entity/StoryScript.java`、`entity/StoryShot.java`、`service/StoryScriptService.java`、`controller/StoryScriptController.java` | `/api/video/scripts` |
| B. 提示词优化器 | ✅ 已实现 | `video/VisualStyle.java`（8 种风格预设）、`entity/ImagePromptOptimizer.java`、`service/ImagePromptOptimizerService.java`、`controller/ImagePromptOptimizerController.java` | `/api/video/enhance-prompt` |
| C. 视频预览与导出 | ✅ 已实现 | `entity/VideoExport.java`、`service/VideoExportService.java`、`controller/VideoExportController.java`；`VideoComposeService` 扩展 `ComposeOptions`（分辨率/多比例裁剪/水印/BGM 混音） | `/api/video/export/*`、`/api/video/exports/*` |
| D. 背景音乐 | ✅ 已实现 | `entity/Bgm.java`、`service/BgmService.java`（音乐库扫描 + LLM 推荐 + 上传）、`controller/BgmController.java`；混音集成在 `VideoComposeService.mixBgmIntoVideo()`；预置 5 首合成免版权 BGM（`tools/generate-preset-bgm.ps1` 生成，`data/bgm/`） | `/api/video/bgm` |
| E. 并行图片生成 | ✅ 已实现 | `video/generator/*`（DashScope/本地 SD + 工厂）、`video/ImageGenerationProgress.java`、`service/ImageGenerationService.java`（并行 + SSE 进度 + 重试）、`controller/ImageGenerationController.java` | `/api/video/images` |
| F. 字幕系统 | ✅ 已实现 | `video/subtitle/SubtitleService.java`（SRT/ASS 生成 + xfade 对齐时间轴 + 多语言 zh/en/bilingual）、`video/subtitle/SubtitleStyle.java`（字体/字号/颜色/描边/位置）、`controller/SubtitleController.java`；烧录集成在 `VideoComposeService.burnSubtitlesIntoVideo()`（subtitles 滤镜） | `/api/video/subtitle` |
| I. 社交媒体导出 | ✅ 已实现 | `video/PlatformPreset.java`（六平台预设枚举）、`controller/SocialMediaExportController.java`；平台导出映射到已有 `VideoExportService.exportHd()`，封面截帧在 `VideoComposeService.renderVideoCover()`，Logo 角标在 `overlayLogoIntoVideo()`，ZIP 打包下载在 `VideoExportService.prepareBatchZip()/writeBatchZip()` | `/api/video/export/platforms`、`/export/platform`、`/export/batch-download`、`/cover/generate` |
| 前端工作流界面 | ✅ 已实现 | `templates/video-studio.html`（脚本管理/镜头编辑/拖拽排序/SSE 进度/导出面板/BGM 面板/导出历史） | - |
| F. 字幕系统 | ✅ 已实现（见上表） | - | - |
| G. 本地模型集成 | ✅ 已完成 | `video/generator/LocalSdImageGenerator.java`（SD WebUI/FLUX）+ `LocalModelStylePreset.java`（SD1.5/SDXL/FLUX 内置风格预设，配置可覆盖）；ComfyUI 原生协议未实现（需 SD WebUI 兼容层，见模块 G 说明） | - |
| H. 视频模板系统 | ✅ 已实现 | `entity/VideoTemplate.java`、`repository/VideoTemplateRepository.java`、`service/VideoTemplateService.java`（8 个系统预置 + 自定义）、`controller/VideoTemplateController.java`；模板引导注入 `VideoScriptService.generateScript()` System Prompt | `/api/video/templates` |
| I. 社交媒体导出优化 | ✅ 已实现（见上表） | - | - |
| J. 图片质量评估 | ✅ 已完成 | `video/quality/ImageQualityService.java` + `ImageQualityScore.java`（多模态评分 + 规则降级 + 低分重试≤2 次）；默认关闭，`image.quality.enabled` 开启 | - |

> **实现说明**：
> 1. 数据库采用 H2 `ddl-auto: update` 自动建表（项目未启用 Flyway），实际表名：`story_scripts` / `story_shots` / `image_prompt_optimizations` / `video_exports` / `bgm_tracks`
> 2. 模块 A 的实体命名为 `StoryScript` / `StoryShot`（即规划中的 video_project / video_shot）
> 3. BGM 混音策略：音量降至 15%（约 -16dB）、开头 2s 淡入、结尾 3s 淡出（按实际视频时长计算，已扣除 xfade 转场重叠）、短曲自动循环
> 4. 字幕策略（模块 F）：SRT（平台上传用）与 ASS（烧录用，subtitles 滤镜）双格式；字幕时间轴与 xfade 转场精确对齐（转场中点切换，无缝不重叠）；导出时可选烧录（`subtitle` 参数），亦可单独导出 SRT 文件；字体默认微软雅黑，可通过 `video.subtitle.font-name` 配置
> 5. 社媒导出策略（模块 I）：平台预设映射到已有分辨率档位（零新增 FFmpeg 逻辑）；时长超平台推荐上限仅随响应提示不拦截（按实际输出时长计算，已扣除 xfade 重叠）；封面默认截取最近一次成功导出的中间帧并回写导出记录；批量多平台导出由前端 Promise.all 并行调 `/export/platform` 实现（FFmpeg 编码 CPU 密集，后端不引入异步任务表）；Logo 角标路径由 `video.watermark.logo-path` 配置、请求 `withLogo` 可选启用（未配置自动跳过）；ZIP 打包下载（`/export/batch-download`）同步校验归属/状态后流式输出，单次上限 10 条，含视频+已有封面+manifest.txt
> 6. 多语言字幕策略（模块 F，2026-11-14）：`language`/`subtitleLanguage` 参数支持 zh（原文）/ en（LLM 翻译）/ bilingual（原文+英文双行）；翻译一次 LLM 批量调用 + 按原文内存缓存，失败逐条回退原文不阻断；TTS 配音仍为中文原文；导出记录新增 `subtitle_language` 列
> 7. 预置 BGM 素材（模块 D，2026-11-14）：`tools/generate-preset-bgm.ps1` 用 FFmpeg lavfi 合成 5 首免版权和弦垫音（happy/calm/epic/sad/tense 各 1 首，约 42s），文件名匹配 BUILT_IN_PRESETS 自动注册带 mood 标签；可替换为真实免版权音乐
> 8. 视频模板策略（模块 H，2026-11-14）：实际落点遵循项目分层约定 entity/repository/service/controller（未建 video/template 包）；表名 `video_templates`；启动幂等预置 8 个系统模板；应用模板 = stylePrefix+推荐镜头数注入脚本生成 System Prompt；自定义模板仅本人可见可删，系统模板禁删

---

### 模块 A：脚本编辑器 ✅ 已实现（2026-11-07）

> **这是什么**：让用户在页面上直接修改分镜脚本（增删改镜头、编辑提示词、调整时长），修改后重新生成图片或合成视频。
>
> **为什么重要**：当前流程是"一次生成、不能改"，实际使用中用户需要反复调整脚本才能得到满意的结果。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 镜头列表编辑 | 可修改每个镜头的 sceneDescription、narration、imagePrompt、durationSeconds |
| 新增/删除镜头 | 支持在任意位置插入新镜头、删除不满意的镜头 |
| 拖拽排序 | 拖拽调整镜头顺序 |
| 重新生成单个镜头图片 | 修改 imagePrompt 后只重新生成该镜头的图片 |
| AI 润色 | 对单个镜头的 imagePrompt 调用 LLM 优化（"帮我写更好的图像提示词"） |
| 脚本保存/加载 | 将分镜脚本保存到数据库，下次可加载继续编辑 |

#### 数据模型

```sql
-- 视频项目表
CREATE TABLE video_project (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    title       VARCHAR(200),
    theme       VARCHAR(500),
    idea        TEXT,                    -- 原始创意想法
    status      VARCHAR(20) DEFAULT 'DRAFT',  -- DRAFT / GENERATING / READY / EXPORTED
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 镜头表
CREATE TABLE video_shot (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id        BIGINT NOT NULL,
    shot_order        INT NOT NULL,
    scene_description TEXT,
    camera_movement   VARCHAR(100),
    duration_seconds  INT DEFAULT 5,
    narration         TEXT,
    image_prompt      TEXT,
    image_url         VARCHAR(500),       -- 已生成图片的访问地址
    image_file_id     VARCHAR(200),       -- 存储服务中的文件ID
    image_status      VARCHAR(20) DEFAULT 'PENDING',  -- PENDING / GENERATING / READY / FAILED
    FOREIGN KEY (project_id) REFERENCES video_project(id)
);
```

#### 代码生成提示

```
请为 spring-ai-demo 项目生成脚本编辑器模块的代码。

技术栈：Spring Boot 3.4 + Java 17 + Spring Data JPA + H2/PostgreSQL

要求：
1. 创建 VideoProject 实体（对应 video_project 表）
   - 字段: id, userId, title, theme, idea, status(DRAFT/GENERATING/READY/EXPORTED), createdAt, updatedAt
   - 关联: List<VideoShot> shots (OneToMany, cascade=ALL, orphanRemoval=true)

2. 创建 VideoShot 实体（对应 video_shot 表）
   - 字段: id, projectId, shotOrder, sceneDescription, cameraMovement, durationSeconds, narration, imagePrompt, imageUrl, imageFileId, imageStatus(PENDING/GENERATING/READY/FAILED)

3. 创建 VideoProjectRepository 和 VideoShotRepository (JPA)

4. 创建 VideoProjectService：
   - saveProject(userId, storyboard) — 将 Storyboard 保存到数据库
   - getProject(projectId) — 加载项目及其所有镜头
   - listProjects(userId) — 列出用户所有项目
   - updateShot(shotId, updates) — 更新单个镜头
   - reorderShots(projectId, shotIds) — 重新排序镜头
   - addShot(projectId, position, shot) — 在指定位置插入镜头
   - deleteShot(shotId) — 删除镜头
   - deleteProject(projectId) — 删除整个项目

5. 创建 VideoProjectController (RESTful)：
   - POST   /api/video/projects — 保存新项目
   - GET    /api/video/projects — 获取项目列表
   - GET    /api/video/projects/{id} — 获取项目详情（含所有镜头）
   - PUT    /api/video/projects/{id} — 更新项目基本信息
   - DELETE /api/video/projects/{id} — 删除项目
   - PUT    /api/video/projects/{id}/shots — 批量更新镜头
   - POST   /api/video/projects/{id}/shots — 添加镜头
   - DELETE /api/video/projects/{id}/shots/{shotId} — 删除镜头
   - PUT    /api/video/projects/{id}/shots/reorder — 拖拽排序

6. 创建 Flyway 迁移脚本 V2__create_video_project_tables.sql

参考已有代码风格：构造器注入、SLF4J 日志、统一返回格式 {success, message, data}
```

---

### 模块 B：图片提示词优化器 ✅ 已实现（2026-11-07）

> **这是什么**：用户写的 imagePrompt 往往不够好，这个模块用 LLM 把简单的场景描述转化为高质量的图像生成提示词。
>
> **为什么重要**：图片质量直接决定视频质量，而好的 prompt 是高质量图片的前提。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 自动优化 | 脚本生成时自动将 imagePrompt 优化为专业级提示词 |
| 手动润色 | 用户在编辑器中点击"优化提示词"按钮，LLM 改写 |
| 风格预设 | 支持选择视觉风格（写实/动漫/水彩/赛博朋克/电影质感等） |
| 负面提示词 | 自动生成 negative prompt 排除不想要的元素 |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成图片提示词优化服务。

技术栈：Spring Boot 3.4 + Java 17 + Spring AI 1.0 (ChatClient)

要求：
1. 创建 PromptEnhanceService：
   - enhancePrompt(sceneDescription, style) — 将场景描述优化为图像生成提示词
   - 使用 ModelService.getChatClient() 调用 LLM
   - System Prompt 设计：
     """
     你是一位专业的 AI 图像生成提示词工程师。
     将用户提供的场景描述转化为高质量的英文图像生成提示词。

     优化规则：
     1. 明确主体：谁/什么在画面中心
     2. 环境细节：场景、背景、天气、时间
     3. 光影氛围：光线方向、色温、整体氛围
     4. 构图镜头：视角（俯拍/平视/仰拍）、景别（远景/中景/特写）
     5. 艺术风格：写实/插画/3D渲染/电影质感
     6. 画质关键词：highly detailed, 8k, professional photography, cinematic lighting

     输出格式：一段英文提示词，100-200 词，不要包含任何文字/对白内容。
     如果用户指定了风格，按指定风格优化；否则默认"电影质感写实风格"。
     """

2. 创建风格预设枚举 VisualStyle：
   - CINEMATIC_REALISM ("电影写实", "cinematic, photorealistic, film grain, anamorphic lens")
   - ANIME ("日系动漫", "anime style, cel shading, vibrant colors, Studio Ghibli inspired")
   - WATERCOLOR ("水彩插画", "watercolor painting, soft edges, pastel colors, artistic")
   - CYBERPUNK ("赛博朋克", "cyberpunk, neon lights, dark atmosphere, futuristic")
   - PIXAR_3D ("3D动画", "Pixar style 3D render, cute, vibrant, soft lighting")
   - VINTAGE ("复古胶片", "vintage film, retro colors, 35mm photography, nostalgic")

3. 创建 PromptEnhanceController：
   - POST /api/video/enhance-prompt — 优化单个提示词
     请求: { "sceneDescription": "...", "style": "CINEMATIC_REALISM" }
     返回: { "success": true, "data": { "enhancedPrompt": "...", "negativePrompt": "..." } }

4. 集成到脚本生成流程：
   - 修改 VideoScriptService.generateScript() 的 System Prompt
   - 让 LLM 在生成分镜时直接输出高质量的英文 imagePrompt
   - 或者在生成后自动调用 enhancePrompt 对每个镜头的提示词进行二次优化

参考已有代码风格
```

---

### 模块 C：视频预览与导出 ✅ 已实现（2026-11-07）

> **这是什么**：合成前的快速预览（低分辨率/水印版）+ 合成后的高清导出，支持多种视频比例。
>
> **为什么重要**：全分辨率合成很慢，预览功能让用户快速确认效果再导出。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 快速预览 | 生成 480p 低分辨率预览版（合成速度快） |
| 高清导出 | 生成 1080p / 720p 正式版 |
| 多比例支持 | 16:9（横屏/B站）、9:16（竖屏/抖音）、1:1（方形/小红书） |
| 水印（可选） | 预览版加半透明水印，正式版无水印 |
| 导出历史 | 记录每次导出的视频文件，可重新下载 |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成视频预览与导出模块。

技术栈：Spring Boot 3.4 + Java 17 + FFmpeg (ProcessBuilder)

要求：
1. 创建 VideoExportService：
   - exportPreview(shots, projectId) — 生成 480p 预览版
     FFmpeg 参数调整: -s 854x480 -crf 28 (低质量快速出片)
   - exportHD(shots, projectId, resolution) — 生成高清版
     支持: "720p" (1280x720), "1080p" (1920x1080)
   - exportVertical(shots, projectId) — 生成 9:16 竖屏版
     FFmpeg: 图片需要裁剪/缩放为 720x1280，使用 crop+scale 滤镜

2. 创建 VideoExport 实体：
   - 字段: id, projectId, userId, resolution, aspectRatio, videoUrl, fileSize, duration, status, createdAt

3. 创建 VideoExportController：
   - POST /api/video/export/preview — 生成预览
   - POST /api/video/export/hd — 生成高清版
   - GET  /api/video/exports/{projectId} — 获取导出历史

4. 视频比例处理的 FFmpeg 滤镜：
   - 16:9 → 直接使用 1280x720
   - 9:16 → 先 scale 到 1280x1280，再 crop 到 720x1280
   - 1:1  → 先 scale 到 1280x1280，再 crop 到 720x720

5. 创建 Flyway 迁移脚本 V3__create_video_export_table.sql

参考已有 VideoComposeService 的 FFmpeg 调用模式
```

---

### 模块 D：背景音乐 (BGM) ✅ 已实现（2026-11-07）

> **这是什么**：为视频自动配上背景音乐，支持从音乐库选择或 AI 推荐。
>
> **为什么重要**：BGM 是短视频氛围感的关键要素。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 音乐库 | 内置一批免版权背景音乐（按风格分类：轻快/温馨/紧张/史诗等） |
| 自动推荐 | 根据视频主题/情感自动推荐合适的 BGM |
| 音量混合 | BGM 音量自动降低（-15dB），不抢旁白 |
| 淡入淡出 | BGM 开头淡入、结尾淡出 |
| 自定义上传 | 用户上传自己的音乐文件 |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成背景音乐模块。

技术栈：Spring Boot 3.4 + Java 17 + FFmpeg

要求：
1. 创建 BgmService：
   - listBgm(category) — 列出可用的背景音乐
     音乐文件存放在 src/main/resources/static/bgm/ 目录下
   - recommendBgm(theme) — 根据主题推荐 BGM
     调用 LLM 分析 theme 的情感/风格，匹配预设的 BGM 标签
   - mixAudio(videoPath, bgmPath, narrationPath, outputPath) — 混音
     FFmpeg 滤镜:
     - 旁白: 原音量
     - BGM: 音量降低到 15% (-16dB)，开头 2s 淡入，结尾 3s 淡出
     - amix / amerge 滤镜混合

2. 创建 Bgm 实体：
   - 字段: id, name, filePath, category, mood, duration, isBuiltIn

3. 创建 BgmController：
   - GET  /api/video/bgm — 获取 BGM 列表
   - GET  /api/video/bgm/recommend?theme=xxx — 获取推荐
   - POST /api/video/bgm/upload — 上传自定义 BGM

4. 集成到视频合成流程：
   - 修改 VideoComposeService.compose() 增加 bgmId 参数
   - 在 mergeVideoAndAudio 步骤后，增加 BGM 混音步骤

5. 预置 5-10 首免版权 BGM（可用公开资源）✅（2026-11-14：tools/generate-preset-bgm.ps1 合成 5 首，每分类 1 首，可替换）
   分类: happy, sad, epic, calm, tense

参考已有 FFmpeg 调用模式
```

---

### 模块 E：并行图片生成 ✅ 已实现（2026-11-07，含前端 SSE 进度接入）

> **这是什么**：将逐个生成图片改为并行生成，大幅缩短等待时间。
>
> **为什么重要**：5 个镜头逐个生成可能需要 2-3 分钟，并行可以缩短到 30 秒。

#### 代码生成提示

```
请优化 spring-ai-demo 项目的图片生成逻辑，改为并行执行。

技术栈：Spring Boot 3.4 + Java 17 + CompletableFuture

要求：
1. 修改 VideoController.generateAllImages() 方法：
   - 使用 CompletableFuture.supplyAsync() 并行调用每个镜头的图片生成
   - 使用虚拟线程 (Java 21) 或自定义线程池 (Java 17)
   - 线程池配置: corePoolSize=3, maxPoolSize=5 (避免 DashScope API 限流)

2. 添加进度回调机制：
   - 创建 ImageGenerationProgress 类: totalShots, completedShots, failedShots, currentShotIndex
   - 使用 SSE (Server-Sent Events) 推送进度到前端
   - 前端进度条显示: "正在生成第 3/5 张图片..."

3. 添加重试机制：
   - 单张图片生成失败时自动重试 1 次
   - 重试间隔 2 秒

4. 添加超时控制：
   - 单张图片生成超时: 60 秒
   - 所有图片生成总超时: 5 分钟

5. 修改 video-studio.html 前端：
   - generateAllImages() 函数改为通过 EventSource 监听进度
   - 进度条显示真实百分比而非 indeterminate 动画

参考已有 SSE 实现: ChatController 的 stream 接口
```

---

### 模块 F：字幕系统（新增） ✅ 已实现（2026-11-07）

> **这是什么**：根据每个镜头的 narration 自动生成 SRT 字幕文件，并通过 FFmpeg 字幕烧录功能将字幕硬烧录到视频中。
>
> **为什么重要**：社交媒体短视频几乎都有字幕，很多用户静音观看。字幕是投放社交媒体的必备要素。

#### 功能清单

| 功能 | 说明 |
|------|------|
| SRT 自动生成 | 根据每个镜头的 narration + durationSeconds 自动生成带时间轴的 SRT 字幕文件 |
| 字幕样式定制 | 支持自定义字体、字号、颜色、描边、位置（底部/顶部） |
| FFmpeg 硬烧录 | 使用 `subtitles` 或 `drawtext` 将字幕烧录到视频画面中 |
| 多语言支持 | 中文/英文双语字幕，适配不同平台 ✅（2026-11-14：zh/en/bilingual，LLM 翻译 + 缓存 + 失败回退） |
| 字幕开关 | 用户可选择是否烧录字幕，也可单独导出 SRT 文件 |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成字幕系统模块。

技术栈：Spring Boot 3.4 + Java 17 + FFmpeg (ProcessBuilder)

要求：
1. 创建 SubtitleService：
   - generateSrt(shots) — 根据镜头列表生成 SRT 字幕内容
     每个镜头的 narration 作为字幕文本，根据 durationSeconds 计算时间轴
     注意：SRT 时间格式为 HH:MM:SS,mmm
   - burnSubtitles(videoPath, srtPath, outputPath, style) — FFmpeg 烧录字幕
     使用 drawtext 或 subtitles 迲镜
     支持中文字体（思源黑体/微软雅黑）
   - exportSrt(shots, outputPath) — 单独导出 SRT 文件

2. 创建 SubtitleStyle 配置类：
   - fontSize: 字幕大小（默认 24）
   - fontColor: 字体颜色（默认白色）
   - outlineColor: 描边颜色（默认黑色）
   - outlineWidth: 描边宽度（默认 2）
   - position: 位置（bottom/center/top）
   - fontName: 字体名称

3. 创建 SubtitleController：
   - POST /api/video/subtitle/generate — 生成 SRT 文件
   - POST /api/video/subtitle/preview — 预览字幕效果（截图）

4. 集成到视频合成流程：
   - 修改 VideoComposeService.compose() 增加 subtitle 参数
   - 在最终输出前增加字幕烧录步骤

5. FFmpeg 命令参考：
   使用 drawtext（兼容性好）：
   ffmpeg -i input.mp4 -vf "drawtext=fontfile=/path/to/font.ttf:text='字幕文本':fontcolor=white:fontsize=24:borderw=2:bordercolor=black:x=(w-text_w)/2:y=h-th-40" -c:a copy output.mp4
   使用 subtitles（功能更强，需要 libass）：
   ffmpeg -i input.mp4 -vf "subtitles=subtitles.srt:force_style='FontName=Microsoft YaHei,FontSize=24,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2'" -c:a copy output.mp4

参考已有 VideoComposeService 的 FFmpeg 调用模式
```

---

### 模块 G：本地模型集成（新增） ✅ 已完成（2026-11-14）

> **实现落点**：`video/generator/LocalSdImageGenerator.java`（SD WebUI `/sdapi/v1/txt2img` 协议，兼容 OllamaDiffuser）+ `LocalModelStylePreset.java`（按 `image.local.model` 模型名自动套用 SD1.5/SDXL/FLUX 内置风格前缀与负面词；`image.local.style-prefix` / `image.local.extra-negative-prompt` 可覆盖；负面词去重合并）。回归测试 `LocalModelStylePresetTest`（11 例）。
> **ComfyUI 说明**：原生 ComfyUI 工作流协议（/prompt + /history）未实现；ComfyUI 用户可通过 SD WebUI 兼容层/代理接入，或后续按需扩展。

> **这是什么**：支持使用本地运行的 AI 模型替代云端 API，包括 LLM（Ollama）和图片生成（Stable Diffusion / FLUX）。
>
> **为什么重要**：本地模型无 API 费用、无网络依赖、隐私安全。项目已有 Ollama 集成（ModelService），需要扩展到图片生成领域。

#### 功能清单

| 功能 | 说明 |
|------|------|
| LLM 脚本生成 | 支持切换 DeepSeek / Ollama 本地模型生成分镜脚本（已有 ModelService 支持） |
| 本地图片生成 | 集成 OllamaDiffuser 或 ComfyUI API 实现本地 Stable Diffusion / FLUX 图片生成 |
| 图片生成源切换 | 配置项控制使用云端 DashScope 还是本地 SD，可按需切换 |
| 模型风格适配 | 不同本地模型有不同的风格倾向，提供预设提示词模板 |

#### 架构设计

```
┌─────────────────────────────────────────────────────┐
│                  ImageGenerator (接口)                  │
└───────────────┬────────────────────┬────────────────┘
                │                    │
    ┌───────────▼──────┐   ┌───────▼───────────┐
    │ DashScopeImage   │   │ LocalSdImage       │
    │ Generator        │   │ Generator           │
    │ (云端 API)       │   │ (本地 SD/FLUX)    │
    └──────────────────┘   └───────────────────┘
                │                    │
                └────────┬───────────┘
                         │
              ┌─────────▼──────────┐
              │ ImageGenerator     │
              │ Factory (配置驱动) │
              └────────────────────┘
```

#### 代码生成提示

```
请为 spring-ai-demo 项目集成本地图片生成能力。

技术栈：Spring Boot 3.4 + Java 17 + Ollama Java HTTP API

要求：
1. 创建 ImageGenerator 接口：
   - generate(prompt, width, height, style) -> ImageGenerateResponse

2. 创建 DashScopeImageGenerator（重构现有 ImageService）：
   - 实现 ImageGenerator 接口
   - 保留现有 DashScope API 调用逻辑

3. 创建 LocalSdImageGenerator：
   - 实现 ImageGenerator 接口
   - 通过 HTTP API 调用本地 ComfyUI / Stable Diffusion WebUI
   - 支持配置本地服务地址（如 http://localhost:7860）
   - 也支持通过 Ollama 的实验性图片生成 API（如已集成）

4. 创建 ImageGeneratorFactory：
   - 根据配置 storage.image-generator=dashscope/local 动态注入实现

5. 修改 application.yml 增加配置：
   image:
     generator: dashscope  # dashscope / local
     local:
       base-url: http://localhost:7860
       model: stable-diffusion
       timeout: 120

6. 注意：本地图片生成为可选功能，不影响现有 DashScope 流程

参考已有 ImageService 和 StorageService 的工厂模式设计
```

---

### 模块 H：视频模板系统（新增） ✅ 已实现（2026-11-14）

> **实现落点**：`entity/VideoTemplate.java`（表 `video_templates`）、`repository/VideoTemplateRepository.java`、`service/VideoTemplateService.java`（启动幂等预置 8 个系统模板：产品展示/故事讲述/知识科普/风景旅拍/美食制作/情感语录/科技数码/运动健身）、`controller/VideoTemplateController.java`（GET 列表/详情、POST /{id}/apply、POST /custom、DELETE /{id}）。
> 应用模板 = `buildGuidance()` 将 stylePrefix + 推荐镜头数注入 `VideoScriptService.generateScript(idea, guidance)` 的 System Prompt；`/api/video/scripts/generate` 亦支持可选 `templateId`。
> 前端：新建脚本区模板下拉（含描述/镜头数/配乐风格提示）+ 脚本头部「另存为模板」（镜头数 + 当前视觉风格 promptPrefix）。

> **这是什么**：提供预设的视频风格模板，用户选择模板后自动填充脚本结构、提示词风格、BGM 风格等，降低使用门槛。
>
> **为什么重要**：很多用户不知道怎么写创意想法和提示词，模板可以提供“开箱即用”的创作体验。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 预设模板库 | 内置多种视频风格模板（产品展示/故事讲述/知识科普/风景旅拍/美食制作等） |
| 模板应用 | 选择模板后自动填充 Storyboard 结构（镜头数、时长、风格化提示词前缀） |
| 模板编辑 | 用户可在模板基础上修改，保存为自定义模板 |
| 风格一键切换 | 对已有脚本应用不同风格模板，快速迭代 |

#### 数据模型

```sql
-- 视频模板表
CREATE TABLE video_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,    -- 模板名称
    category        VARCHAR(50),              -- 分类: product/story/knowledge/travel/food
    description     TEXT,                     -- 模板描述
    thumbnail_url   VARCHAR(500),             -- 模板预览图
    shot_count      INT DEFAULT 5,            -- 推荐镜头数
    style_prefix    TEXT,                     -- 风格化提示词前缀
    default_bgm     VARCHAR(50),              -- 推荐 BGM 分类
    default_voice   VARCHAR(50),              -- 推荐 TTS 声音
    is_system       BOOLEAN DEFAULT FALSE,    -- 是否系统内置模板
    user_id         BIGINT,                   -- 创建者（自定义模板）
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 代码生成提示

```
请为 spring-ai-demo 项目生成视频模板系统。

技术栈：Spring Boot 3.4 + Java 17 + Spring Data JPA

要求：
1. 创建 VideoTemplate 实体（对应 video_template 表）
2. 创建 VideoTemplateRepository (JPA)
3. 创建 VideoTemplateService：
   - listTemplates(category) — 列出可用模板
   - applyTemplate(templateId, idea) — 应用模板生成分镜
     将模板的 style_prefix 注入到 VideoScriptService 的 System Prompt
   - saveCustomTemplate(userId, storyboard, name) — 保存自定义模板
4. 创建 VideoTemplateController：
   - GET  /api/video/templates — 获取模板列表
   - POST /api/video/templates/{id}/apply — 应用模板
   - POST /api/video/templates/custom — 保存自定义模板
5. 预置 5-8 个系统模板（硬编码初始化）

参考已有代码风格
```

---

### 模块 I：社交媒体导出优化（新增） ✅ 已实现（2026-11-08）

> **这是什么**：针对不同社交媒体平台的规格要求，优化视频导出参数，包括分辨率、比例、封面图、水印等。
>
> **为什么重要**：不同平台对视频规格要求不同，手动调整很繁琐。一键导出多平台适配版本能大幅提升投放效率。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 平台预设 | 抖音/B站/小红书/快手/视频号/YouTube Shorts 一键预设 |
| 多比例导出 | 同一视频同时导出 16:9、9:16、1:1 三个版本 |
| 智能裁剪 | 横屏转竖屏时智能裁剪画面中心区埏，避免主体被切 |
| 封面图生成 | 自动截取视频精彩帧（第一帧/中间帧）作为封面 |
| 水印叠加 | 可选叠加品牌 Logo 水印（角标/全屏透明） |
| 批量导出 | 一次操作导出多个平台版本，打包下载 ✅（2026-11-14：/export/batch-download ZIP 流式打包，含封面 + manifest） |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成社交媒体导出优化模块。

技术栈：Spring Boot 3.4 + Java 17 + FFmpeg (ProcessBuilder)

要求：
1. 创建 SocialMediaExportService：
   - exportForPlatform(videoPath, platform, outputPath) — 按平台规格导出
     平台参数: DOUYIN(9:16,1080x1920), BILIBILI(16:9,1920x1080), XIAOHONGSHU(1:1,1080x1080) 等
   - smartCrop(videoPath, srcAspect, targetAspect) — 智能裁剪
     FFmpeg: 使用 crop 迲镜，优先保留画面中心区域
   - addWatermark(videoPath, watermarkPath, position, outputPath) — 叠加水印
     FFmpeg: overlay 迲镜
   - generateCover(videoPath, timeOffset, outputPath) — 生成封面图
     FFmpeg: ss + vframes 截取指定时间点的帧

2. 创建 PlatformPreset 枚举：
   - DOUYIN("抖音", 1080, 1920, "9:16", 60)
   - BILIBILI("B站", 1920, 1080, "16:9", 0)
   - XIAOHONGSHU("小红书", 1080, 1080, "1:1", 60)
   - KUAISHOU("快手", 1080, 1920, "9:16", 60)
   - WEIXIN_VIDEO("视频号", 1920, 1080, "16:9", 0)
   - YOUTUBE_SHORTS("YouTube Shorts", 1080, 1920, "9:16", 60)

3. 创建 SocialMediaExportController：
   - POST /api/video/export/platform — 按平台导出
   - POST /api/video/export/batch — 批量导出多平台
   - POST /api/video/cover/generate — 生成封面

4. FFmpeg 命令参考：
   横屏转竖屏（智能裁剪）：
   ffmpeg -i input.mp4 -vf "crop=ih*9/16:ih:(iw-ih*9/16)/2:0,scale=1080:1920" output.mp4
   叠加水印：
   ffmpeg -i input.mp4 -i watermark.png -filter_complex "overlay=10:10" output.mp4
   截取封面：
   ffmpeg -ss 00:00:03 -i input.mp4 -vframes 1 -q:v 2 cover.jpg

参考已有 VideoComposeService 的 FFmpeg 调用模式
```

---

### 模块 J：图片质量评估与重试（新增） ✅ 已实现（2026-11-14）

> **实现落点**：`video/quality/ImageQualityScore.java`（清晰度/相关性/美学三维 0-10 分 + 综合均值 + 反馈）、`video/quality/ImageQualityService.java`（独立 OpenAI 兼容多模态端点，默认 DashScope qwen-vl-max；未配 API Key/调用失败时降级为分辨率规则评估）。
> 集成：`ImageGenerationService.generateWithQualityCheck()` 低分自动重试（≤ `image.quality.max-retries`，默认 2），重试时评分反馈并入负面提示词，始终保留最高分一张；评估异常不阻塞生成。评分写入 `story_shots.quality_score` 列，随镜头详情/SSE SHOT_COMPLETE 返回，前端镜头卡片显示质量徽章。
> 配置：`image.quality.enabled`（默认 false，关闭时流程完全不变）/ base-url / api-key / model / threshold（6.0）/ max-retries。测试：`ImageQualityServiceTest`（13 例）+ `ImageGenerationServiceQualityTest`（5 例）。
> 未实现（YAGNI，后续按需）：跨镜头风格一致性批量检查、内容安全检查、用户“不满意”反馈学习。

> **这是什么**：使用多模态 LLM（或规则引擎）自动评估生成图片的质量，不合格则自动重新生成。
>
> **为什么重要**：AI 生成的图片质量不稳定，有时会出现畸形、模糊、风格不一致等问题。自动评估可以避免人工逐张检查。

#### 功能清单

| 功能 | 说明 |
|------|------|
| 质量评分 | 对生成的图片进行 1-10 分的质量评估 |
| 自动重试 | 评分低于阈值（如 6 分）自动重新生成，最多重试 2 次 |
| 风格一致性检查 | 检查所有镜头图片的风格是否一致 |
| 内容安全检查 | 检测是否包含不适宜的内容 |
| 用户反馈学习 | 用户标记“不满意”的图片，后续优化提示词策略 |

#### 代码生成提示

```
请为 spring-ai-demo 项目生成图片质量评估服务。

技术栈：Spring Boot 3.4 + Java 17 + Spring AI (多模态 LLM)

要求：
1. 创建 ImageQualityService：
   - evaluateQuality(imageUrl, imagePrompt) — 评估图片质量
     使用多模态 LLM（如 GPT-4o / Qwen-VL）分析图片
     评估维度：画质清晰度、与提示词的匹配度、美学质量、风格一致性
     返回: ImageQualityScore (overallScore, detailScores[], feedback)
   - shouldRegenerate(score) — 判断是否需要重新生成
   - batchEvaluate(imageUrls) — 批量评估所有镜头图片的风格一致性

2. 创建 ImageQualityScore DTO：
   - overallScore: int (1-10)
   - clarityScore: int
   - relevanceScore: int
   - aestheticScore: int
   - feedback: String
   - passed: boolean

3. 集成到图片生成流程：
   - 修改 VideoScriptService.generateImageForShot() 增加质量检查
   - 质量不达标自动重试，最多 2 次
   - 重试时在提示词中追加负面约束（基于上次反馈）

4. 如果没有多模态 LLM 可用，提供降级方案：
   - 基于图片尺寸的简单规则检查
   - 基于生成时间的异常检测（太快/太慢可能有问题）

参考已有代码风格
```

---

## 四、后续规划（Phase 3 — Agent 化）

> 这一阶段的目标是将整个视频生成流水线建模为 **Multi-Agent 工作流**，与 nexus-ai 的 Agent 模块深度整合。

> **切片1 实现落点（2026-11-14）✅**：`video/agent/AgentToolRequests.java`（6 个输入 record，@ToolParam 描述进入 JSON Schema）+ `video/agent/VideoAgentToolFactory.java`（FunctionToolCallback 包装 generateStoryboard/enhancePrompt/generateImage/evaluateImage/exportVideo/recommendBgm，异常转 {success:false,message} 供 LLM 感知）+ `controller/VideoAgentController.java`（GET /api/video/agent/tools 内省，只读匿名可读，沿用 /api/video/** permitAll 策略，执行时登录校验由服务层保障）。测试：VideoAgentToolFactoryTest（13）+ VideoAgentControllerTest（2）。
> **API 演进注记**：本章早期代码示例中的 `FunctionCallback` 为 Spring AI 1.0.0-M 系列旧 API，1.0.0 GA 已移除，实际实现用 `org.springframework.ai.tool.function.FunctionToolCallback`（builder 签名已经 javap 核实）。
> **切片2 实现落点（2026-11-14）✅**：`video/agent/ConfirmationGateToolCallback.java`（ToolCallback 装饰器：未确认拦截 exportVideo，返回 requiresConfirmation JSON + 待确认项记入 ToolContext）+ `video/agent/DirectorAgentService.java`（ModelService.getChatClient().mutate() 挂导演 System Prompt 与六工具，Spring AI 内建 tool-calling 循环即 ReAct；历史按 userId:sessionId 内存隔离上限 20 条；同步执行于请求线程）+ `VideoAgentController` 追加 POST /api/video/agent/chat（message/sessionId/confirmedTools）与 DELETE /chat/{sessionId} + 前端工作室「AI 导演」面板（对话 + 确认按钮）。确认为请求级显式放行（confirmedTools 经 ToolContext，无服务端 pending 令牌）。测试：ConfirmationGateToolCallbackTest（5）+ DirectorAgentServiceTest（8）+ VideoAgentControllerChatTest（3），全量 92 绿；真实 DeepSeek 冒烟：纯对话/recommendBgm 工具调用/闸门拦截→确认放行→错误转述/匿名拒绝均通过。
> **切片3 实现落点（2026-11-14）✅**：`video/agent/SpecialistAgentFactory.java`（编剧/美术/音频/剪辑四专家：各自 System Prompt + 工具子集，以 delegateToXxx 「专家即工具」委托给导演，无状态单轮 ReAct；剪辑专家 exportVideo 带闸门）+ `video/agent/AgentOrchestrationService.java`（一键成片异步编排：专用线程池与图片工作池分离，SecurityContext 提交时捕获、工作线程 setContext+finally clearContext；任务表内存态上限 100 按 userId 归属校验；状态机 RUNNING/COMPLETED/AWAITING_CONFIRMATION/FAILED，confirm 携 confirmedTools 续跑）+ 导演工具装配 6+4=10 + 控制器 POST /orchestrate、GET /orchestrate/{taskId}、POST /orchestrate/{taskId}/confirm + 前端「一键流水线」按钮（3s 轮询 + 确认续跑）。规格 4.2 中 editShot/generateTts/mixAudio 等未 Tool 化能力不新增（TTS/混音已内嵌 exportVideo 管线）。测试：SpecialistAgentFactoryTest（7）+ AgentOrchestrationServiceTest（8）+ VideoAgentControllerOrchestrationTest（3）+ 导演新增 1，全量 111 绿；真实 DeepSeek 冒烟：异步流水线（编剧/音频专家真实委托）/SecurityContext 传播/闸门暂停→confirm 续跑/越权与匿名拒绝均 PASS。提交前代码审查修复 2 Major + 3 Minor + 2 Nit（confirm 续跑上下文回放/CAS 防并发确认/evict 原子复检/evaluateImage SSRF 防护/会话历史同步/线程池关闭钩子/会话表上限），新增 7 回归测试全量 118 绿，详见切片3 计划文档审查修复记录。Phase 3 Agent 化全部完成。

### 4.1 Agent 角色设计

```
┌─────────────────────────────────────────────────────────┐
│                    导演 Agent (Orchestrator)              │
│         接收用户创意，拆解任务，协调其他 Agent              │
│         使用 ReAct 模式: 思考 → 行动 → 观察 → 决策        │
└────────┬──────────┬──────────┬──────────┬───────────────┘
         │          │          │          │
    ┌────▼───┐ ┌───▼────┐ ┌──▼───┐ ┌───▼────┐
    │ 编剧   │ │ 美术   │ │ 音频 │ │ 剪辑   │
    │ Agent  │ │ Agent  │ │Agent │ │ Agent  │
    └────────┘ └────────┘ └──────┘ └────────┘
    生成分镜    生成图片    配音+BGM  FFmpeg合成
    脚本       优化提示词   选择音乐   转场+特效
```

### 4.2 每个 Agent 的 Tool 定义

| Agent | 可调用的 Tools |
|-------|---------------|
| 编剧 Agent | `generateStoryboard(idea)` — 调用 LLM 生成分镜 |
| | `enhancePrompt(sceneDescription, style)` — 优化图片提示词 |
| | `editShot(shotId, field, value)` — 修改镜头内容 |
| 美术 Agent | `generateImage(prompt, size)` — 调用 DashScope 生成图片 |
| | `evaluateImage(imageUrl)` — 调用多模态 LLM 评估图片质量 |
| | `regenerateImage(shotId)` — 不满意则重新生成 |
| 音频 Agent | `generateTts(text, voice)` — 调用 Edge TTS |
| | `selectBgm(theme)` — AI 推荐背景音乐 |
| | `mixAudio(narration, bgm)` — 混音 |
| 剪辑 Agent | `composeVideo(shots, transitions)` — FFmpeg 合成 |
| | `addSubtitle(videoPath, subtitles)` — 添加字幕 |
| | `exportVideo(resolution, aspectRatio)` — 导出 |
| 导演 Agent | 以上所有 Agent 的调度权 |
| | `requestUserFeedback(storyboard)` — 向用户请求确认/修改 |
| | `approveAndPublish(video)` — 最终审核并发布 |

### 4.3 Spring AI Agent 实现思路

```java
// 使用 Spring AI 的 FunctionCallback 注册 Tools
@Bean
public FunctionCallback generateImageTool() {
    return FunctionCallback.builder()
        .function("generateImage", (ImageRequest req) -> dashScopeService.generate(req))
        .description("调用 DashScope 通义万相生成图片")
        .inputType(ImageRequest.class)
        .build();
}

// Agent 的 ReAct 循环
ChatClient agentClient = ChatClient.builder(chatModel)
    .defaultTools(generateImageTool, enhancePromptTool, ...)
    .defaultSystem("你是短视频导演 Agent，负责协调编剧、美术、音频、剪辑 Agent 完成视频制作。")
    .build();
```

---

## 五、环境依赖

### 必须安装

| 依赖 | 用途 | 安装命令 |
|------|------|---------|
| FFmpeg | 视频合成 + 字幕烧录 + 比例转换 | 路径走 `video.ffmpeg-path` / `video.ffprobe-path` 配置，不依赖系统 PATH。本机已装 `D:/Program Files/ffmpeg/bin/`（BtbN win64-gpl，2026-11-13 构建），该目录已于 2026-11-14 追加到**用户** PATH 末尾（机器 PATH 未动，且此前任何 PATH 作用域里都没有 ffmpeg，不存在被旧版抢命中的情况）。**版本必须 ≥ 4.3**，`xfade` 转场滤镜才存在——EVCapture 自带的 `C:\Program Files\EVCapture\ffmpeg.exe` 是 4.2 构建，无 xfade，不可用 |
| DeepSeek API Key | 脚本生成 | 设置环境变量 `DEEPSEEK_API_KEY` |
| DashScope API Key | 图片生成 | 设置环境变量 `DASHSCOPE_API_KEY` |

### 可选安装

| 依赖 | 用途 | 安装命令 |
|------|------|---------|
| Edge TTS | 旁白配音 | `pip install edge-tts`（本机已装 7.3.1）。调用命令由 `video.python-command` 指定，见下方 Python 行 |
| Python 3.x | Edge TTS 运行环境 | 本机已装 3.14.6 于 `%LOCALAPPDATA%\Python\bin\python.exe`（`pip check` 通过）。**曾经的坑**：用户 PATH 里微软商店"应用执行别名"桩 `%USERPROFILE%\AppData\Local\Microsoft\WindowsApps` 排在它前面，裸 `python` 会以退出码 49 失败。2026-11-14 已把 `Python\bin` 提到 WindowsApps 之前修好；`video.python-command` 仍保留绝对路径，避免依赖各启动环境（IDE/服务）继承到的 PATH 新旧 |
| Ollama | 本地 LLM 脚本生成 | `curl -fsSL https://ollama.com/install.sh \| sh` |
| OllamaDiffuser | 本地图片生成 (SD/FLUX) | `pip install ollamadiffuser` |
| ComfyUI | 本地图片生成 (更强大) | 参考 https://github.com/comfyanonymous/ComfyUI |

### 启动命令

```bash
cd D:/code/spring-ai-demo
mvn spring-boot:run
```

访问: `http://localhost:8080/video-studio`

---

## 六、实现优先级

### Phase 2（MVP 增强）— 建议实现顺序

| 优先级 | 模块 | 状态 | 工作量 | 价值 | 依赖 |
|--------|------|------|--------|------|------|
| P0 | A. 脚本编辑器 | ✅ 已完成 | 2-3 天 | 没有编辑能力 = 不可用 | 无 |
| P0 | E. 并行图片生成 | ✅ 已完成 | 1 天 | 用户体验巨大提升 | 无 |
| P1 | F. 字幕系统 | ✅ 已完成 | 1 天 | 社交媒体必备 | 无 |
| P1 | B. 提示词优化器 | ✅ 已完成 | 1 天 | 显著提升图片质量 | 无 |
| P1 | I. 社交媒体导出 | ✅ 已完成（平台预设/封面/Logo/批量已落地） | 1-2 天 | 多平台投放必备 | F |
| P2 | G. 本地模型集成 | ✅ 已完成（本地 SD + 风格预设，2026-11-14） | 2 天 | 降低成本、无网络依赖 | 无 |
| P2 | C. 视频预览与导出 | ✅ 已完成 | 1-2 天 | 竖屏/横屏多场景 | I |
| P2 | D. 背景音乐 | ✅ 已完成 | 1-2 天 | 氛围感提升 | 无 |
| P2 | H. 视频模板系统 | ✅ 已完成（2026-11-14） | 1 天 | 降低使用门槛 | A |
| P3 | J. 图片质量评估 | ✅ 已完成（2026-11-14，默认关闭） | 1-2 天 | 自动化质量保障 | G |

**建议实现顺序**: A → E → F → B → I → G → C → D → H → J
**当前进度**: Phase 2 全部模块 A / B / C / D / E / F / G / H / I / J 已完成 ✅（含 S1 补齐：预置 BGM 素材/多语言字幕/ZIP 打包下载；FFmpeg/TTS 环境已落地并回归验证）；下一步：Phase 3 Agent 化（长期）

### Phase 3（Agent 化）— 长期规划

| 优先级 | 内容 | 工作量 | 价值 |
|--------|------|--------|------|
| P3 | Multi-Agent 工作流 | 5-10 天 | 架构升级，学习价值高 |
| P3 | 自动化发布到社交平台 | 3-5 天 | 全自动化流水线 |

---

## 七、数据库迁移脚本汇总

> **实际实现说明**：项目使用 JPA `ddl-auto: update` 自动建表（H2/PostgreSQL），未启用 Flyway，规划中的迁移脚本未创建。实际表名对照：`story_scripts` / `story_shots`（模块 A）、`image_prompt_optimizations`（模块 B）、`video_exports`（模块 C）、`bgm_tracks`（模块 D）。以下为后续启用 Flyway 时的参考。

| 版本 | 文件名 | 内容 |
|------|--------|------|
| V2 | `V2__create_video_project_tables.sql` | video_project + video_shot 表（模块 A） |
| V3 | `V3__create_video_export_table.sql` | video_export 表（模块 C） |
| V4 | `V4__create_bgm_table.sql` | bgm 音乐库表（模块 D） |
| V5 | `V5__create_video_template_table.sql` | video_template 模板表（模块 H） |

---

## 八、关键技术决策记录

| 决策 | 选择 | 原因 |
|------|------|------|
| 脚本生成 LLM | DeepSeek + Ollama | DeepSeek 云端质量高，Ollama 本地免费无网络依赖 |
| 图片生成 | DashScope + 本地 SD | DashScope 免费额度够用，本地 SD 作为备选降本 |
| 视频合成 | FFmpeg (ProcessBuilder) | 业界标准，Java 无需额外依赖 |
| TTS 配音 | Edge TTS (Python CLI) | 免费、质量好、部署简单 |
| Ken Burns 效果 | zoompan 滤镜 | 让静态图片产生"镜头运动"感 |
| 转场效果 | xfade (fade) | 简洁优雅，计算量小 |
| 结构化输出 | BeanOutputConverter | Spring AI 原生支持，类型安全 |
| 字幕烧录 | FFmpeg drawtext/subtitles | 硬字幕兼容性最好，社交媒体必备 |
| 多平台导出 | FFmpeg crop+scale | 智能裁剪 + 多比例适配 |
| 并行生成 | CompletableFuture + SSE | Java 17 原生支持，实时进度推送 |
| 图片质量评估 | 多模态 LLM + 规则降级 | 有 LLM 用 LLM，无 LLM 用规则检查 |
| 视频模板 | JPA 实体 + 系统预置 | 支持用户自定义，系统内置开箱即用 |
| 存储 | StorageService 工厂模式 | 已有本地/OSS/MinIO/FastDFS 四种实现复用 |
| 前端 | Thymeleaf + 原生 JS | 与现有项目一致，无需引入新框架 |
| FFmpeg / Python 调用方式 | 不依赖 PATH，由 `video.ffmpeg-path` / `video.ffprobe-path` / `video.python-command` 指定绝对路径（可用 `FFMPEG_PATH` / `FFPROBE_PATH` / `PYTHON_CMD` 环境变量覆盖） | 本机 PATH 曾经不可控：EVCapture 自带的 4.2 构建缺 xfade；微软商店的应用执行别名桩会把裸 `python` 顶成退出码 49。两处坑现已修好（新 FFmpeg 目录追加、`Python\bin` 提前），但 IDE/服务启动的 JVM 可能仍持有改动前的 PATH 快照，因此配置继续写绝对路径，命令行手工调试则可直接用 `ffmpeg` / `python` |

---

## 九、完整文件结构规划

```
src/main/java/com/github/ylyan2015/springaidemo/
├── video/
│   ├── Storyboard.java                          # 分镜脚本 DTO
│   ├── Shot.java                                # 单个镜头 DTO
│   ├── VideoScriptService.java                  # 脚本生成 + 图片生成调度
│   ├── VideoComposeService.java                 # FFmpeg 视频合成 + TTS + 音视频合并
│   ├── VideoController.java                     # REST API
│   ├── subtitle/                                # 模块 F：字幕系统
│   │   ├── SubtitleService.java                 # SRT 生成 + FFmpeg 字幕烧录
│   │   ├── SubtitleStyle.java                   # 字幕样式配置
│   │   └── SubtitleController.java              # 字幕 API
│   ├── generator/                               # 模块 G：本地模型集成
│   │   ├── ImageGenerator.java                  # 图片生成接口
│   │   ├── DashScopeImageGenerator.java         # DashScope 实现
│   │   ├── LocalSdImageGenerator.java           # 本地 SD 实现
│   │   └── ImageGeneratorFactory.java           # 工厂类
│   ├── template/                                # 模块 H：视频模板
│   │   ├── VideoTemplate.java                   # 模板实体
│   │   ├── VideoTemplateRepository.java         # JPA Repository
│   │   ├── VideoTemplateService.java            # 模板服务
│   │   └── VideoTemplateController.java         # 模板 API
│   ├── export/                                  # 模块 I：社交媒体导出
│   │   ├── SocialMediaExportService.java        # 多平台导出服务
│   │   ├── PlatformPreset.java                  # 平台预设枚举
│   │   └── SocialMediaExportController.java     # 导出 API
│   ├── quality/                                 # 模块 J：图片质量评估
│   │   ├── ImageQualityService.java             # 质量评估服务
│   │   └── ImageQualityScore.java               # 质量评分 DTO
│   └── project/                                 # 模块 A：脚本编辑器
│       ├── VideoProject.java                    # 项目实体
│       ├── VideoShot.java                       # 镜头实体
│       ├── VideoProjectRepository.java          # JPA Repository
│       ├── VideoShotRepository.java             # JPA Repository
│       ├── VideoProjectService.java             # 项目管理服务
│       └── VideoProjectController.java          # 项目 API
├── service/
│   ├── ImageService.java                        # 现有图片服务（重构为 ImageGenerator 接口）
│   └── BgmService.java                          # 模块 D：背景音乐服务
└── ...

src/main/resources/
├── templates/
│   └── video-studio.html                        # 前端页面（增强）
├── static/
│   ├── bgm/                                     # 模块 D：预置 BGM 音乐文件
│   └── fonts/                                   # 模块 F：字幕字体文件
└── db/migration/
    ├── V2__create_video_project_tables.sql
    ├── V3__create_video_export_table.sql
    ├── V4__create_bgm_table.sql
    └── V5__create_video_template_table.sql
```

### 9.1 已实现部分的实际文件落点（2026-11-07）

```
src/main/java/com/github/ylyan2015/springaidemo/
├── video/
│   ├── Storyboard.java / Shot.java / VideoScriptService.java / VideoController.java   # Phase 1（旧一次性流程，保留兼容）
│   ├── VideoComposeService.java                # FFmpeg 合成 + ComposeOptions（分辨率/多比例/水印/BGM 混音/字幕烧录）
│   ├── VisualStyle.java                        # 模块 B：8 种视觉风格预设（含负面提示词）
│   ├── PlatformPreset.java                     # 模块 I：社媒平台预设枚举（六平台）
│   ├── ImageGenerationProgress.java            # 模块 E：SSE 进度事件
│   ├── subtitle/                               # 模块 F：字幕系统
│   │   ├── SubtitleService.java                # SRT/ASS 生成 + xfade 对齐时间轴
│   │   ├── SubtitleStyle.java                   # 字幕样式（字体/字号/颜色/描边/位置）
│   │   └── SubtitleCue.java                     # 字幕条目（文本 + 起止毫秒）
│   └── generator/                              # 模块 E/G：图片生成抽象
│       ├── ImageGenerator.java / GeneratedImage.java
│       ├── DashScopeImageGenerator.java        # 云端通义万相
│       ├── LocalSdImageGenerator.java          # 本地 SD WebUI / FLUX
│       ├── LocalModelStylePreset.java          # 模块 G：SD1.5/SDXL/FLUX 风格预设（2026-11-14）
│       └── ImageGeneratorFactory.java          # 配置驱动 + 运行时切换
├── video/quality/                              # 模块 J：图片质量评估（2026-11-14）
│   ├── ImageQualityService.java                # 多模态评分 + 规则降级
│   └── ImageQualityScore.java                  # 评分 DTO
├── entity/
│   ├── StoryScript.java / StoryShot.java       # 模块 A（StoryShot 含 quality_score 列，模块 J）
│   ├── ImagePromptOptimizer.java               # 模块 B
│   ├── VideoExport.java                        # 模块 C（含 subtitle_language 列）
│   ├── Bgm.java                                # 模块 D
│   └── VideoTemplate.java                      # 模块 H（2026-11-14）
├── repository/
│   ├── StoryScriptRepository / StoryShotRepository / ImagePromptOptimizerRepository   # 模块 A/B
│   └── VideoExportRepository / BgmRepository                        # 模块 C/D
├── service/
│   ├── StoryScriptService.java                 # 模块 A：脚本生成/编辑/排序（含模板引导重载）
│   ├── ImagePromptOptimizerService.java        # 模块 B：LLM 提示词优化
│   ├── ImageGenerationService.java             # 模块 E：并行生成 + SSE + 重试 + 质量评估重试（模块 J）
│   ├── VideoExportService.java                 # 模块 C：预览/高清导出/导出历史 + ZIP 打包（模块 I）
│   ├── BgmService.java                         # 模块 D：音乐库扫描/推荐/上传
│   └── VideoTemplateService.java               # 模块 H：模板预置/应用/自定义（2026-11-14）
└── controller/
    ├── StoryScriptController.java / ImagePromptOptimizerController.java
    ├── ImageGenerationController.java / VideoExportController.java
    ├── BgmController.java
    ├── SubtitleController.java                 # 模块 F：SRT 导出/字幕预览（多语言）
    ├── SocialMediaExportController.java       # 模块 I：平台导出/封面截帧
    └── VideoTemplateController.java            # 模块 H：模板列表/应用/自定义（2026-11-14）

src/main/resources/
├── templates/video-studio.html                # 完整工作流前端界面（含模板下拉/字幕语言/打包下载）
└── application.yml                             # image.* / bgm.* / video.* 配置项

tools/generate-preset-bgm.ps1                  # 预置 BGM 素材生成脚本（模块 D，2026-11-14）
data/bgm/*.mp3                                 # 5 首预置免版权 BGM（启动自动扫描注册）
src/test/java/.../                             # 单元测试：SubtitleServiceTest / VideoExportServiceZipTest / VideoTemplateServiceTest / LocalModelStylePresetTest / ImageQualityServiceTest / ImageGenerationServiceQualityTest / VideoComposeFfmpegTest（真实 FFmpeg 回归，TTS 用例 -Dtts=true）
```
