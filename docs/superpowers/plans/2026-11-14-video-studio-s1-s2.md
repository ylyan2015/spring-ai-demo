# Video Studio S1 快速补齐 + S2 视频模板 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 Phase 2 遗漏项（预置 BGM 素材、批量导出 ZIP 打包下载、多语言字幕），并实现模块 H 视频模板系统。

**Architecture:** 全部增量构建在已有基础设施上：BGM 用 FFmpeg 合成免版权素材放入 `data/bgm/`（BgmService 启动扫描自动注册）；ZIP 打包复用导出记录 + HTTP 媒体下载；多语言字幕在 SubtitleService 增加 LLM 翻译层（zh/en/bilingual），导出管线只改 ASS 文本生成处；模板系统按项目实际分层约定落 entity/repository/service/controller（不建 video/template 包）。

**Tech Stack:** Spring Boot 3.4 + Java 17 + Spring AI 1.0 + JPA/H2(ddl-auto) + FFmpeg + 原生 JS 前端

**Spec:** `VIDEO_STUDIO_PLAN.md`（模块 D 素材项 / 模块 I 打包下载 / 模块 F 多语言 / 模块 H）

## Global Constraints

- 分层约定：entity/ repository/ service/ controller/，构造器注入、SLF4J、统一响应 `{success, message, data}`
- 时长型 FFmpeg 参数一律用实际输出时长（已扣 xfade 重叠 `XFADE_DURATION=0.8`）
- 含分钟级 LLM/FFmpeg 调用的方法不加 `@Transactional`
- 不引入后端异步任务表；不在工作线程隐式依赖 SecurityContext
- 中英文文档同步：README.md ↔ README-en.md、API_TEST.md ↔ API_TEST-en.md
- 本机 FFmpeg 路径：`C:\Program Files\EVCapture\ffmpeg.exe`（不在 PATH；应用运行时仍用 `ffmpeg` 命令名，仅素材生成脚本用全路径）
- 当前分支 `feature/vedio-create`，直接在该分支实施

---

### Task 1: S1-1 预置 BGM 素材（5 首合成免版权音乐）

**Files:**
- Create: `tools/generate-preset-bgm.ps1`（可复现的素材生成脚本，入库）
- Create: `data/bgm/happy__sunny_morning.mp3`、`calm__peaceful_piano.mp3`、`epic__heroic_journey.mp3`、`sad__melancholy_strings.mp3`、`tense__suspense_pulse.mp3`（生成产物，入库；文件名必须匹配 `BgmService.BUILT_IN_PRESETS` 的 key 以获得 mood 标签）

**Interfaces:**
- Consumes: `BgmService.scanLibrary()` 启动扫描 `bgm.library-path=./data/bgm`，`category__name.ext` 自动注册
- Produces: 5 首内置 BGM（每分类 1 首，约 40-50s，128kbps MP3），`GET /api/video/bgm` 可见

**方案:** 用 FFmpeg lavfi `aevalsrc` 合成和弦垫音（4 个和弦段 × 12s，`acrossfade` 衔接 + 整体淡入淡出），无外部下载、无版权风险。各分类和弦进行：
- happy: C–G–Am–F（明亮大三和弦为主，加八度泛音）
- calm: C–Am–F–G（低音量慢速正弦垫）
- epic: Dm–Bb–F–C（叠加低八度根音，音量略高）
- sad: Am–Em–F–Dm（小三和弦，柔和）
- tense: C+Db 不协和持续音 + 2Hz 振幅脉冲

- [x] **Step 1:** 编写 `tools/generate-preset-bgm.ps1`：参数 `-FfmpegPath`（默认 `ffmpeg`）、`-OutDir`（默认 `./data/bgm`）；每首曲目生成 4 个和弦段（aevalsrc 表达式 = 各和弦音正弦叠加，峰值 ≤0.5），段间 `acrossfade=d=2`，整体 `afade in 2s / out 3s`，`-c:a libmp3lame -b:a 128k` 输出
- [x] **Step 2:** 运行脚本（-FfmpegPath 指向 EVCapture ffmpeg），生成 5 个 mp3
- [x] **Step 3:** 用 ffprobe 验证每个文件时长 35-55s、可解码；验证文件名与 BUILT_IN_PRESETS key 一致
- [x] **Step 4:** README 中英文补充"预置 BGM 为程序合成素材，可用 `tools/generate-preset-bgm.ps1` 重新生成或替换为真实免版权音乐"

### Task 2: S1-2 批量导出 ZIP 打包下载

**Files:**
- Modify: `video/VideoComposeService.java`（暴露公共媒体下载方法）
- Modify: `service/VideoExportService.java`（新增 `prepareBatchZip` + `writeBatchZip`）
- Modify: `controller/VideoExportController.java`（新增 `POST /api/video/export/batch-download`）
- Modify: `templates/video-studio.html`（批量结果区 + 导出历史区"打包下载"按钮）
- Test: `src/test/java/.../service/VideoExportServiceZipTest.java`

**Interfaces:**
- Produces:
  - `VideoComposeService.downloadMediaTo(String url, Path target)` — 公共委托已有私有 `downloadImageTo`（HTTP 下载，兼容 local/oss/minio/fastdfs 的访问 URL）
  - `VideoExportService.prepareBatchZip(List<Long> exportIds)` → `List<VideoExport>`：当前用户归属校验、仅 SUCCESS 且有 videoUrl、1-10 条限制
  - `VideoExportService.writeBatchZip(List<VideoExport> records, OutputStream out)`：临时目录下载视频（+已有封面）→ ZipOutputStream 流式写出 → 清理
  - `POST /api/video/export/batch-download` body `{"exportIds":[1,2,3]}` → `application/zip` 流（StreamingResponseBody），`Content-Disposition: attachment; filename=video_exports_<ts>.zip`；校验失败返回 JSON 错误
- ZIP 条目命名：`{序号}_{resolution}_{aspect冒号转x}_{exportId}.mp4`（如 `01_1080p_9x16_12.mp4`），封面 `..._cover.jpg`，附 `manifest.txt`（每行：文件名 | 分辨率 | 比例 | 时长 | BGM | 字幕语言）

**关键决策:** 下载走 HTTP 访问 URL（与 renderVideoCover 相同路径），不做 URL→fileId 反解（四种存储后端映射各异）。先同步校验再开始流式输出，避免流中途报错。

- [x] **Step 1:** 写失败测试：ZIP 条目命名规则（aspect `:`→`x`）、manifest 内容、归属/状态校验抛异常（Mockito mock repository）
- [x] **Step 2:** 运行测试确认失败（方法不存在）
- [x] **Step 3:** 实现 downloadMediaTo / prepareBatchZip / writeBatchZip / Controller 端点
- [x] **Step 4:** 运行测试通过 + `mvn compile`
- [x] **Step 5:** 前端：`batchExport()` 成功后收集成功导出 id，结果区追加"打包下载 ZIP"按钮；`loadExportHistory()` 头部追加"打包下载全部"按钮（取全部 SUCCESS 记录 id，≤10）；`downloadBatchZip(ids)` 用 fetch POST → blob → a.download 触发下载，失败时解析 JSON 错误提示

### Task 3: S1-3 多语言字幕（zh / en / bilingual）

**Files:**
- Modify: `video/subtitle/SubtitleService.java`（翻译层 + language 重载）
- Modify: `entity/VideoExport.java`（新增 `subtitle_language` 列）
- Modify: `service/VideoExportService.java`（subtitleLanguage 贯穿 exportPreview/exportHd/exportForPlatform/doExport/ExportMeta/toMap）
- Modify: `controller/VideoExportController.java`、`controller/SocialMediaExportController.java`（解析 `subtitleLanguage`）
- Modify: `controller/SubtitleController.java`（/generate 与 /preview 支持 language）
- Modify: `templates/video-studio.html`（字幕语言下拉 + 各调用点透传）
- Test: `src/test/java/.../video/subtitle/SubtitleServiceTest.java`

**Interfaces:**
- Produces:
  - `SubtitleService.generateSrt(List<StoryShot> shots, String language)` / `generateAss(List<StoryShot> shots, SubtitleStyle style, String language)`；旧签名保留并委托 `"zh"`
  - language 取值：`zh`（默认，原文）/ `en`（仅英文）/ `bilingual`（原文 + 换行 + 英文）；非法值按 zh
  - 翻译：`ModelService.getChatClient()` 一次调用批量翻译缺失文本，System Prompt 要求"仅输出 JSON 字符串数组、顺序一致"；`ConcurrentHashMap<String,String>` 按原文缓存；LLM 失败/解析失败/数量不符 → 逐条回退原文并 log.warn
  - `VideoExport.subtitleLanguage`（String，nullable；null 视为 zh）
  - 导出请求体新增可选 `subtitleLanguage`；`/api/video/subtitle/generate`、`/preview` 请求体新增可选 `language`
- 不变：TTS 旁白仍为中文原文（仅字幕文本变化）；时间轴算法不变（buildCues 不动）

**关键决策:** 双语文本用 `\n` 连接，ASS 经 `escapeAssText` 自动转 `\N` 换行；SRT 天然支持多行。翻译在 doExport 预生成 ASS 处发生，`VideoComposeService`/`ComposeOptions` 零改动。

- [x] **Step 1:** 写失败测试：buildCues 时间轴（xfade 中点切换）、zh/en/bilingual 内容形态（mock 翻译）、非法 language 回退
- [x] **Step 2:** 运行确认失败
- [x] **Step 3:** 实现 SubtitleService 翻译层与重载、VideoExport 字段、导出链路参数、两个字幕端点
- [x] **Step 4:** 测试通过 + `mvn compile`
- [x] **Step 5:** 前端：action-bar 烧录字幕开关旁加"字幕语言"下拉（中文/English/双语，全局 `currentSubtitleLang`）；exportVideo/batchExport body 加 `subtitleLanguage`；exportSrt/previewSubtitle 加 `language`；导出历史显示字幕语言徽章

### Task 4: S2 模块 H 视频模板系统

**Files:**
- Create: `entity/VideoTemplate.java`、`repository/VideoTemplateRepository.java`、`service/VideoTemplateService.java`、`controller/VideoTemplateController.java`
- Modify: `video/VideoScriptService.java`（generateScript 增加模板引导重载）
- Modify: `service/StoryScriptService.java`（generateAndSaveScript 增加 templateId 重载）
- Modify: `controller/StoryScriptController.java`（/generate 解析 templateId）
- Modify: `templates/video-studio.html`（模板下拉 + 另存为模板）
- Test: `src/test/java/.../service/VideoTemplateServiceTest.java`

**Interfaces:**
- 实体 `VideoTemplate`（表 `video_templates`）：id, name(100), category(50: product/story/knowledge/travel/food/emotion), description(TEXT), shotCount(默认5), stylePrefix(TEXT), defaultBgmCategory(50), defaultVoice(50), isSystem(Boolean), userId(nullable), createdAt
- Repository：`findByIsSystemTrueOrUserIdOrderByIdAsc(Long userId)`、`findByIsSystemTrue()`、`findByIdAndUserId(Long id, Long userId)`
- Service：
  - `@PostConstruct seedSystemTemplates()`：`findByIsSystemTrue().isEmpty()` 时插入 8 个系统模板（产品展示/故事讲述/知识科普/风景旅拍/美食制作/情感语录/科技数码/运动健身），各含 stylePrefix（英文风格引导句）+ shotCount + defaultBgmCategory
  - `listTemplates()`：系统 + 当前用户自定义
  - `getTemplate(id)`：系统或本人自定义，否则抛异常
  - `applyTemplate(templateId, idea)` → 委托 `StoryScriptService.generateAndSaveScript(idea, templateId)`，返回 scriptToMap 所需实体
  - `saveCustomTemplate(name, category, description, shotCount, stylePrefix, defaultBgmCategory)`：userId=当前用户，isSystem=false
  - `deleteTemplate(id)`：仅本人自定义可删，系统模板拒绝
- Controller `/api/video/templates`：
  - `GET /` 列表；`GET /{id}` 详情
  - `POST /{id}/apply` body `{idea}` → 生成并保存脚本（data=脚本 Map，与 /scripts/generate 一致）
  - `POST /custom` body `{name, category, description, shotCount, stylePrefix, defaultBgmCategory}`
  - `DELETE /{id}`
- `VideoScriptService.generateScript(String idea, String templateGuidance)`：templateGuidance 非空时追加到 System Prompt（"模板风格要求：{stylePrefix}；镜头数量约 {shotCount} 个"）；旧签名委托 null
- `StoryScriptService.generateAndSaveScript(String idea, Long templateId)`：templateId 非空时取模板拼 guidance；旧签名委托 null
- `StoryScriptController /generate`：请求体新增可选 `templateId`（Map<String,Object> 接收）

**前端:**
- ideaSection 增加"视频模板"下拉（init 时 `loadTemplates()` 加载；选项显示 name+分类；选中显示 description 提示）；`generateScript()` 有模板时改调 `POST /templates/{id}/apply`，否则原接口
- storyboard-header 增加"另存为模板"按钮：prompt 输入名称 → `POST /templates/custom`（stylePrefix 取当前风格预设的 promptPrefix，shotCount 取当前镜头数）

- [x] **Step 1:** 写失败测试：seed 幂等（已有系统模板不重复插入）、8 个预置、归属校验、系统模板禁删（Mockito）
- [x] **Step 2:** 运行确认失败
- [x] **Step 3:** 实现实体/仓库/服务/控制器 + 脚本生成链路模板注入
- [x] **Step 4:** 测试通过 + `mvn compile`
- [x] **Step 5:** 前端模板下拉与另存为模板
- [x] **Step 6:** JS 语法检查（node --check 提取脚本块）

### Task 5: 全量验证 + 文档同步

- [x] **Step 1:** `mvn -q compile` 与 `mvn test`（全部通过）
- [x] **Step 2:** GetProblems 检查所有改动文件无编译/lint 错误
- [x] **Step 3:** 更新 `VIDEO_STUDIO_PLAN.md`：3.0 总览表（D 素材✅、F 多语言✅、I 打包✅、H✅）、模块 H 章节标记已实现 + 实际落点、六/九章进度与文件结构、最后更新日期
- [x] **Step 4:** README.md / README-en.md：特性列表 + 模板/多语言字幕/打包下载说明 + BGM 素材生成说明
- [x] **Step 5:** API_TEST.md / API_TEST-en.md：新增 batch-download、subtitleLanguage 参数、templates 四端点章节与示例
- [x] **Step 6:** 汇报完成状态与剩余项（G 收尾、J 质量评估）

## Self-Review 结论

- Spec 覆盖：S1 三项 + S2 全功能清单（预设模板库/应用/自定义保存/风格切换=另存+重新应用）均有对应任务；"风格一键切换"通过"选模板重新生成"实现，不做存量脚本批量换风格（YAGNI）
- 类型一致性：subtitleLanguage 全链路同名；templateId/apply 返回结构与 /scripts/generate 一致
- 风险：EVCapture ffmpeg 为 2019 构建（≈4.2），合成脚本仅用 aevalsrc/acrossfade/afade/libmp3lame（均兼容）；应用运行时 BGM 混音 amix normalize=0 需 ≥4.4 属既有问题不在本次范围