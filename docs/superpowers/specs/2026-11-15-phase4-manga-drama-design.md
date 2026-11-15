# Phase 4 AI 漫剧质量升级 — 设计规格

> **状态**：设计已确认（2026-11-15），待实现；本文档为唯一规格来源，实现时按切片调用 writing-plans 生成实施计划。
> **技术路线**：云端 API 路线（用户已确认）；本地 ComfyUI+LoRA 仅预留扩展位，不在本期范围。
> **优先级**：切片 1 → 2 → 3 → 4 → 5，每切片独立交付、可暂停。
> **关联文档**：`VIDEO_STUDIO_PLAN.md`（主计划，Phase 1-3 已完成）、`docs/superpowers/plans/2026-11-14-phase3-slice*.md`。
> **约束**：先不开始实现代码；本文档不含任何实现变更。

---

## 1. 背景与目标

### 1.1 课程与教程共识

用户提供的 AI 漫剧课程截图流水线：①故事脚本（故事剧本/人物设计/分镜脚本/Prompt）→ ②文生图（画风选择/人物一致性/分镜生成/首尾帧延伸/重绘细节）→ ③图生视频 → ④配音配乐 → ⑤剪辑 → ⑥视频放大 → ⑦字幕；并指出"视频转绘可能是目前最好的产出方向"。

网上成熟教程交叉验证的共识（来源见附录 A）：

1. **人物一致性是命脉**：纯文本提示词锁不住身份，"必须把身份的图像交给模型，而不是身份的描述"；主流组合 = 角色特征表 + 参考图/LoRA 双保险。
2. **图生视频是基础操作**，不直接用文生视频；首尾帧控制起止与镜头衔接；中低运动幅度、单镜 3-5s、每镜至少 3 个备选抽卡择优。
3. **配音**：每角色固定独立音色，BGM 20-30% 不盖人声，环境音效提升沉浸感。
4. **后期**：超分/补帧提升观感；视频转绘（实拍→动漫）一致性由实拍天然锁定。
5. **工程化**：角色设定总表、按镜号资产管理、质检清单、批量队列与失败重试；"AI 是加速器，不是最终决策者"。

### 1.2 目标

在现有 video-studio 流水线（Phase 1-3）上补齐上述缺口，使产出从"动态 PPT"升级为具备角色一致性、真动态、多角色声音的 AI 漫剧。

### 1.3 非目标（本期不做）

- 角色 LoRA 训练流水线、ComfyUI 原生协议（工厂预留扩展位，见 §11）。
- 口型同步（Kling lip-sync / wan s2v，列为 4-OPT 后续可选）。
- 专业 NLE 级剪辑（节奏卡点仅做轻量增强，不做时间线编辑器）。
- Flyway 迁移（沿用 H2 `ddl-auto: update` 自动建列）。

---

## 2. 现状基线（代码核实，2026-11-15）

| 能力 | 落点 | 状态 |
|---|---|---|
| 脚本生成/编辑/模板/提示词优化 | `VideoScriptService`、`StoryScriptService`、`VideoTemplateService`、`ImagePromptOptimizerService` | ✅ |
| 文生图 6 provider + 工厂 | `video/generator/*`（`ImageGenerator` 接口：`generate(prompt, negativePrompt, w, h)` + `getType/getName/isAvailable`） | ✅ |
| 图片质量评估/低分重试 | `video/quality/ImageQualityService`（默认关） | ✅ |
| 合成 | `VideoComposeService.compose(List<Map<String,Object>> shots, ComposeOptions)`：静图 Ken Burns + xfade + TTS + BGM + 字幕烧录 | ✅ |
| TTS | Edge TTS，**硬编码单声线 `zh-CN-YunxiNeural`**，仅旁白 | ✅ 待增强 |
| 字幕/导出/社媒 | SRT/ASS zh/en/bilingual；六平台预设；封面/水印/ZIP | ✅ |
| Agent | 导演 + 编剧/美术/音频/剪辑四专家，委托工具 + 确认闸门 + 异步编排 | ✅ |

**关键缺口**：无角色卡/参考图机制；无真 i2v；无首尾帧；无多候选抽卡；无局部重绘；无多角色声线/音效；无超分补帧；无视频转绘。

**关键扩展点与装配点（实现时必须遵守）**：

- 工厂模式：`ImageGeneratorFactory` List 注入自动收录，新 provider 零工厂改动。
- **shot Map装配点共 4 处 + 前端**：`StoryScriptService.shotToMap`（规范源）、`ImageGenerationController.shotToMap`（独立副本）、`VideoExportService.toComposeMap`、`VideoAgentToolFactory`（复用 shotToMap）、`templates/video-studio.html`。新增字段必须枚举全部装配点（cameraMovement 断链教训）。
- 异步模式：`ImageGenerationService` / `AgentOrchestrationService`（SecurityContext 传播、独立线程池、SSE GET）。
- 外部工具配置模式：`video.ffmpeg-path` / `video.ffprobe-path` / `video.python-command`。

---

## 3. 总体设计原则

1. **沿现有扩展点生长**：`VideoGenerator`/Factory 复刻 `ImageGenerator` 模式；配置驱动、**空 Key = 不可用**（干净环境启动不破坏，Spring AI 自动配置陷阱教训）。
2. **零成本回退**：i2v 未配置/失败 → Ken Burns；参考图不可用 → 纯文本 + 特征表；超分/补帧未配置 → 跳过。所有新能力默认关闭或 none，存量行为零变化。
3. **人决策、AI 加速**：抽卡候选择优、导出确认闸门；一键流水线为保持状态机简单默认 N=1（不抽卡），手动工作流才启用多候选。
4. **工程红线**（详见 §9）：异步 SecurityContext 全工作线程传播；协调池与工作池分离；长任务禁 `@Transactional`；LLM 可控 URL 仅接受存储相对路径（SSRF）；服务层登录/归属校验。
5. 每切片 TDD（先红后绿）+ 中英文四份文档同步 + 真实冒烟开关默认跳过。

---

## 4. 切片 1：角色卡与图像一致性（约 3 天）

### 4.1 数据模型

新实体 `entity/StoryCharacter`（表 `story_characters`，H2 自动建表）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long PK | |
| script | ManyToOne StoryScript | 归属经 script.userId 校验 |
| name | VARCHAR(100) | 角色名（中文） |
| featureTable | TEXT | **固定特征表（英文）**：发型/瞳色/服装/体型，LLM 生成，出图时拼 prompt 头部 |
| personality | VARCHAR(500) | 性格/情绪关键词 |
| referenceImageFileId / referenceImageUrl | VARCHAR(200)/(1024) | 定妆参考图（存储转存） |
| voice | VARCHAR(100) | edge-tts 声线名，默认 `zh-CN-XiaoxiaoNeural` |

`StoryScript` 增列：`style` VARCHAR(50)（VisualStyle 枚举名，**脚本级整体画风锁**，null 沿用现状）。
`StoryShot` 增列：`character_ids` VARCHAR(200)（逗号分隔角色 id）、`shot_size` VARCHAR(20)（CLOSE/MEDIUM/LONG，默认 MEDIUM）。

### 4.2 脚本生成与 prompt 装配

- `Storyboard` DTO 增 `characters[]`（name/featureTable/personality/suggestedVoice）；shots 增 `characterNames[]`、`shotSize`。LLM 单次结构化输出（BeanOutputConverter），System Prompt 要求"先人设后分镜、一个分镜对应一个镜头"。
- 持久化时 name→id 关联写 `character_ids`；未匹配角色名记 warn 不阻断。
- **新增唯一装配点 `video/ShotPromptAssembler`**：`finalPrompt = 风格前缀 + 角色特征表（"Character {name}: {featureTable}." 逐角色）+ shot.imagePrompt + 景别/运镜补充`（景别映射：CLOSE→close-up、MEDIUM→medium shot、LONG→long shot）。`ImageGenerationService` 与提示词优化器统一调用，禁止多处各自拼接。

### 4.3 参考图生成

- `ImageGenerator` 增 **default 方法** `generateWithReferences(prompt, neg, w, h, List<String> refs)`（默认委托 `generate`，忽略 refs）与 `supportsReferences()`（默认 false）→ **现有 6 实现零改动**。
- 新 `video/generator/Wan27ImageGenerator`（type=`wan27`，DashScope wan2.7-image，参考图 ≤9 超出截断取前 9）；`ImageGeneratorConfig` 注册 Bean，`image.generator: wan27` 可切换，复用 `DASHSCOPE_API_KEY`。
- **参考图传递回退链**（本地存储无公网 URL 问题）：存储公网 URL/presigned URL → 若协议支持 base64 则 data URI → 否则降级纯文本 + 特征表并 warn。协议核对清单见 §4.8。
- `ImageGenerationService` 装配点：按 shot.characterIds 收集角色参考图传入。

### 4.4 API 与前端

- `/api/video/characters`：GET?scriptId / POST / PUT{id} / DELETE{id} / POST{id}/reference-image（multipart，存储转存）；GET `/voices` 返回内置 zh 声线列表（声线试听为可选优化，不做）。
- 前端 `video-studio.html` 角色卡面板：列表/编辑/上传定妆图/声线下拉；脚本面板显示 style 选择。

### 4.5 Agent 集成

编剧专家增 `generateCharacters` 工具（复用脚本生成的 characters 输出）；美术专家 generateImage 自动走参考图模式（服务层透明）。

### 4.6 测试与验收

- 测试类：`StoryCharacterServiceTest`（CRUD/归属/越权）、`VideoScriptServiceCharactersTest`（结构化解析/关联/未匹配 warn）、`ShotPromptAssemblerTest`（装配顺序/空角色/风格锁）、`Wan27ImageGeneratorTest`（refs 序列化/≤9 截断/回退链/错误转 {success:false}）、`Wan27RegistrationTest`（Bean 注册/工厂切换/空 Key 不可用）、`ImageGeneratorDefaultMethodTest`（旧实现忽略 refs 向后兼容）。
- 验收：全量绿；`-Dwan27=true` 真实冒烟同角色 3 镜头参考图出图目视一致；无 Key 环境启动与存量出图不变。

### 4.7 文档同步

README.md/en、API_TEST.md/en 增角色卡/参考图/wan27 配置节；`VIDEO_STUDIO_PLAN.md` 增 Phase 4 指针节。

### 4.8 协议核对清单（实现前必核，官方文档为准）

- wan2.7-image 端点/参考图字段/base64 支持/bbox 编辑参数：阿里云百炼模型文档（Wan2.7-Image，2026-04 发布，≤9 参考图、框选编辑）。
- 核对结果写入切片实施计划，不得凭记忆写协议。

---

## 5. 切片 2：真图生视频与首尾帧（约 4 天，核心）

### 5.1 接口与 provider

- 新 `video/generator/VideoGenerator` 接口：`GeneratedVideo generate(VideoGenerationRequest req)`（阻塞封装内部异步轮询）+ `getType/getName/isAvailable`。
  - `VideoGenerationRequest` record：prompt、firstFrameUrl、lastFrameUrl（可 null）、durationSeconds、resolution。
  - `GeneratedVideo`：url、fileId（存储转存后）、durationSeconds、width/height。
- 新 `VideoGeneratorFactory`（List 注入，复刻 ImageGeneratorFactory）。
- 首实现 `DashScopeWanVideoGenerator`（type=`dashscope-wan`）：模型 `video.wan.model` 默认 `wan2.7-i2v`（首帧+首尾帧，media type=first_frame/last_frame），可选 `wan2.6-i2v`（仅首帧，传 lastFrame 时 warn 忽略）；异步头 `X-DashScope-Async: enable`，轮询 `GET /api/v1/tasks/{id}` 间隔 5s、超时 `video.wan.timeout-seconds` 默认 600；结果 mp4 下载转存。
- **Kling/Seedance 为可选扩展（2-OPT），本期不实现**；配置枚举预留 `kling|seedance`，工厂遇未实现 type 回退 none + warn。
- 配置 `video.generator: none|dashscope-wan`，**默认 none**；运行时切换 `PUT /api/video/videos/generator?type=`（镜像 images 端点）。

### 5.2 生成服务与数据

- 新 `service/VideoGenerationService`：按 shot 并行（工作池 `video.workers` 默认 2，协调池独立 CachedThreadPool）、SSE `GET /api/video/videos/progress?scriptId=`、失败重试 ≤1（成本）、SecurityContext 全工作线程传播、状态回写。
- `StoryShot` 增列：`video_url`(1024)/`video_file_id`(200)/`video_status`(20, 默认 NONE：NONE/PENDING/GENERATING/READY/FAILED)/`last_frame_url`(1024)。
- 参数基线（教程共识）：单镜 3-5s（`durationSeconds` clamp 2-15）、**中低运动幅度**（prompt 模板注入"subtle motion, slow camera, no large deformation"）、分辨率 `video.wan.resolution` 默认 720P。

### 5.3 首尾帧连续性（脚本级开关，默认 NONE）

`StoryScript` 增列 `continuity_mode` VARCHAR(20)：

- `NONE`：首帧 = 本镜定妆图。
- `PREV_LAST_FRAME`：shot i 视频 READY 后 FFmpeg 抽末帧转存 → 作为 shot i+1 首帧（零额外 API 成本；本镜定妆图仍用于候选/缩略图，仅视频首帧被覆盖）。
- `GENERATED_LAST_FRAME`：wan2.7-image 以 shot i 图为参考 + shot i+1 描述"延伸尾帧"→ shot i 的 lastFrame（课程推荐做法）；wan27 不可用时回退 NONE + warn。

### 5.4 合成管线升级

- shot Map 增 `videoUrl` 键，**枚举修改全部装配点**（§2 清单 + 前端）。
- `VideoComposeService`：有真实片段 → ffprobe 实际时长 + scale/pad 对齐 + xfade；无 → Ken Burns 回退。**TTS/字幕时间轴改用实际时长列表**（SubtitleService 入参同步）。
- `ComposeOptions` 不变（超分/补帧在切片 4）。

### 5.5 Agent 集成

美术专家增 `generateShotVideo(shotId)` 工具；一键流水线在图片阶段后插入 i2v 阶段（video.generator=none 时跳过，状态机不变）。

### 5.6 测试与验收

- 测试类：`DashScopeWanVideoGeneratorTest`（提交/轮询/超时/首尾帧序列化/wan2.6 忽略尾帧/失败转错误）、`VideoGeneratorFactoryTest`（注册/未实现回退/空 Key）、`VideoGenerationServiceTest`（SecurityContext/重试/SSE/池分离）、`VideoComposeMixedClipsTest`（真 FFmpeg，混合片段+静图、实际时长字幕对齐）、`ShotMapVideoFieldTest`（4 装配点回归）。
- 验收：全量绿；`-Di2v=true` 真实冒烟单镜 5s 720P 成片含真动态；none 时存量合成逐帧不变。

### 5.7 协议核对清单

- wan2.7-i2v 新协议 media 数组/地域 Endpoint：https://help.aliyun.com/zh/model-studio/image-to-video-general-api-reference
- wan2.6-i2v 首帧协议：https://www.alibabacloud.com/help/zh/model-studio/image-to-video-guide
- 参考生视频（角色参考，2-OPT 预留）：https://help.aliyun.com/zh/model-studio/wan-video-to-video-api-reference

---

## 6. 切片 3：抽卡多候选与局部重绘（约 3 天）

### 6.1 候选机制

- 新实体 `entity/ShotCandidate`（表 `shot_candidates`）：id/shotId/type(IMAGE|VIDEO)/url/fileId/qualityScore/selected/createdAt。
- 配置 `video.candidates.image-count` 默认 3、`video.candidates.video-count` 默认 2；**一键流水线强制 N=1**。
- 批量生成写候选 + `ImageQualityService` 自动评分排序，**主资产暂取最高分**（保证流水线流动），前端对比卡人工择优回写。
- API：`GET /api/video/candidates?shotId=`、`POST /api/video/candidates/{id}/select`（归属校验；回写 shot 主资产 + selected 标记，旧主资产保留为候选）。
- 前端：镜头卡"候选"展开对比网格 + 选用按钮 + 质量分徽章。

### 6.2 局部重绘（重绘确定细节）

- `ImageGenerator` 增 default `editWithMask(prompt, imageUrl, rect, instruction)`（默认抛 UnsupportedOperationException）；`Wan27ImageGenerator` 覆写（wan2.7-image 框选编辑）。
- `POST /api/video/images/inpaint`：{shotId, x/y/w/h（0-1 相对坐标）, instruction}；不支持的 generator → 400；结果存为新候选（type=IMAGE）供择优，不直接覆盖主资产。
- 前端：图片上矩形框选 mask + 指令输入。

### 6.3 Agent 集成

`inpaintImage` 工具挂美术专家；候选**推荐**可入工具，**选择必须人工**（闸门语义同 export）。

### 6.4 测试与验收

`ShotCandidateServiceTest`（择优回写/归属/越权）、`CandidateBatchTest`（N 并行/评分排序/N=1 编排）、`InpaintEndpointTest`（协议 mock/不支持 400/坐标越界 400）。验收：全量绿 + 手动抽卡 3 选 1 冒烟 + 重绘冒烟（`-Dwan27=true`）。

---

## 7. 切片 4：多角色配音与后期增强（约 3 天）

### 7.1 对白/旁白分离与多声线

- `StoryShot` 增列：`dialogue` TEXT、`dialogue_character_id` BIGINT。
- TTS 行构建：每镜 = 对白（角色卡 voice）+ 旁白（默认声线，沿用现有 `zh-CN-YunxiNeural`）顺序拼接（apad 补静音沿用）；`--voice` 参数化替换硬编码；字幕 cue 同步含对白行。
- 脚本生成 LLM 输出 dialogue 归属角色名（复用切片 1 关联逻辑）。

### 7.2 音效

- 预置音效库 `data/sfx/`（wind/rain/footsteps/heartbeat，`tools/generate-preset-sfx.ps1` lavfi 合成，复刻 BGM 预置模式）；`SfxService` 扫描 + LLM 按镜推荐。
- 混音分层：人声 100% / BGM 15%（沿用）/ SFX ≈30%，按镜 adelay 定位。

### 7.3 视频放大与补帧（课程⑥）

- 配置 `video.realesrgan-path` / `video.rife-path`（空 = 跳过，probe 可用性同 ffmpeg 模式）。
- `ComposeOptions` 增 `upscale`/`interpolate` 布尔（默认 false），导出后处理：抽帧 → Real-ESRGAN 超分 → RIFE 补帧（帧数翻倍，输出帧率同步翻倍 30→60fps 保时长；切片 4 审查修正，原 24→48fps 假设与 30fps 合成管线不符）→ 重编码保留音轨；仅对最终 mp4 生效。
- 文档注明 CPU/GPU 耗时预期；Docker 镜像不预装（可选挂载）。

### 7.4 测试与验收

`TtsMultiVoiceTest`（声线参数化/对白+旁白拼接，`-Dtts=true` 真跑）、`SfxMixTest`（命令构造/分层音量）、`UpscaleCommandTest`（未配置跳过/probe/命令序列）。验收：全量绿 + 双角色对白冒烟听检。

### 7.5 后续可选（4-OPT，不在本期）

口型同步：Kling lip-sync API / wan s2v；触发条件：用户提出口型需求时单独立切片。

---

## 8. 切片 5：视频转绘新输出模式（探索，约 2-3 天）

### 8.1 范围与入口

- 独立入口 `POST /api/video/restyle`：上传实拍视频（存储转存）+ 风格指令 → 异步转绘 → 产物记 `VideoExport`（mode=RESTYLE）可下载；**不触碰主 pipeline**。
- provider 首选 DashScope wan2.7 视频编辑（风格切换，复用现有 Key）；Kling 4.0 transform 为备选（需 KLING_API_KEY，2-OPT 不默认实现）。

### 8.2 探索决策规则（先 spike 后实现）

- 0.5 天协议 spike：wan2.7 视频编辑对"实拍→漫剧画风"保持运动/构图的效果验收（目视：动作与机位不变、画风动漫化）。
- 达标 → 按 wan2.7 实现；不达标且用户有 Kling Key → 改 Kling transform；两者皆不可 → 切片标记 blocked 并报告，不硬做。

### 8.3 测试与验收

`RestyleServiceTest`（协议 mock/归属/异步状态/超时）、`-Drestyle=true` 真实冒烟 10s 实拍转绘目视验收。

---

## 9. 横切工程约束（每切片实现与审查必核）

1. **异步**：SecurityContext 提交捕获、**所有**工作线程 set + finally clear；协调池与工作池分离；SSE 端点 GET；注入 Spring ObjectMapper（JavaTimeModule）。
2. **事务**：分钟级 LLM/生成循环禁 `@Transactional`，逐次 save 独立事务。
3. **安全**：HTTP 层 `/api/video/**` permitAll 沿用，服务层登录 + userId 归属校验；LLM 可控 URL 仅存储相对路径 + 同源基址解析（SSRF）；上传 multipart 校验类型/大小。
4. **Map 装配点**：新增 shot 字段枚举 §2 全部 4 处 + 前端，配回归测试。
5. **启动安全**：新 Bean 空 Key/空路径 = isAvailable false，干净环境全上下文启动测试必绿。
6. **闸门**：导出确认闸门语义不变；候选选择人工；编排 confirm 续跑上下文回放沿用。
7. **线程池**：自建池 `@PreDestroy` shutdown；内存任务表上限逐出沿用。
8. **存储**：生成产物一律 StorageService 转存（UUID 前缀），不依赖供应商临时 URL 持久化。

---

## 10. 优先级、里程碑与成本控制

| 切片 | 内容 | 估时 | 交付标志 |
|---|---|---|---|
| 1 | 角色卡 + 参考图一致性 | 3d | 同角色跨镜一致冒烟 |
| 2 | 真 i2v + 首尾帧 | 4d | 真动态成片冒烟 |
| 3 | 抽卡 + 重绘 | 3d | 3 选 1 + 框选重绘冒烟 |
| 4 | 多声线 + 音效 + 超分补帧 | 3d | 双角色对白 + 增强导出 |
| 5 | 视频转绘 | 2-3d | 实拍转绘目视验收 |

**成本控制**：i2v 按秒计费 → 默认 720P/5s/中低幅度；手动视频候选 N=2、编排 N=1；图像候选 N=3 可配置降 1；所有付费能力配置开关 + 空 Key 不可用；重试 ≤1。

---

## 11. 风险与预留扩展

- **参考图一致性天花板低于 LoRA**：工厂模式预留本地扩展位——未来可增 `ComfyUiImageGenerator`（加载角色 LoRA，课程推荐做法）与 `ComfyUiVideoGenerator`，接口不变；触发条件：用户具备 NVIDIA GPU 并提出更高一致性需求。
- **协议变动风险**：wan2.7 系列 2026 年新发布，端点/参数以官方文档为准，每切片实现前执行协议核对清单（§4.8/§5.7），核对结果入实施计划。
- **多主体串脸**：同框多角色参考图仍可能轻微串脸（行业瓶颈），靠特征表 + 候选抽卡兜底，文档如实说明预期。
- **MinIO presigned 24h 过期**：参考图/首帧 URL 仅在任务期内使用，不持久化 presigned 语义（沿用遗留项边界）。

---

## 12. 文档同步与验证约定

- 每切片同步 README.md / README-en.md / API_TEST.md / API_TEST-en.md（特性/配置/端点/FAQ）。
- `VIDEO_STUDIO_PLAN.md` 增 Phase 4 节指向本规格；每切片实施计划落 `docs/superpowers/plans/2026-11-15-phase4-sliceN-*.md`（writing-plans 生成）。
- 验证：`mvn test` 全量绿为合并门槛；真实冒烟开关 `-Dwan27=true` / `-Di2v=true` / `-Dtts=true` / `-Drestyle=true` 默认跳过；FFmpeg 真跑用例沿用现有条件开关。
- 提交节奏：沿用用户指示，**暂不主动提交**；每切片完成后待用户确认再提交。

---

## 附录 A：教程来源（2026-11 检索交叉验证）

1. [AI漫剧制作全流程拆解：分镜脚本到人物一致性完整闭环](https://blog.csdn.net/weixin_29062963/article/details/170443355)（固定种子/参考图/LoRA/ID 嵌入四方案）
2. [影视级AI漫剧完整工作流：剧本人设、分镜渲染到动态成片](https://blog.csdn.net/weixin_29051811/article/details/170443572)（角色设定卡字段、LoRA 训练集 10-30 张、补帧 RIFE）
3. [开源AI漫剧切片制作全流程：从抽卡到流水线](https://blog.csdn.net/weixin_29055699/article/details/168830476)（特征表+LoRA 双保险、每角色独立 LoRA）
4. [2026新版ComfyUI漫剧动画全流程实战课](https://learnku.com/articles/96495)（本地工业化路线，本期仅作扩展位参考）
5. [AI短剧制作工程化：多主体一致性工作流](https://blog.csdn.net/weixin_33525298/article/details/168252625)（首尾帧、即梦参考图锁定）
6. [AI漫剧制作全流程：工具链实操指南](https://blog.csdn.net/weixin_29070721/article/details/170391273)（单镜 3-5s、中低幅度、每镜 3 备选）
7. [2026年AI漫剧制作全流程指南](https://www.sina.cn/news/detail/5351849775334562.html)（prompt 公式、BGM 20-30%、音效、导出规格）
8. API 事实来源：阿里云百炼 wan2.7-i2v/参考生视频/视频编辑官方文档（§5.7 链接）、Kling 4.0 发布报道（首尾帧/全能参考/transform）、Seedance 首尾帧+多参考图社区核验。