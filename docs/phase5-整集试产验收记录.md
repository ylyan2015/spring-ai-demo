# Phase 5 #1 整集试产验收记录（2026-11-15）

> 驱动：`tools/e2e-local-episode.js`（node，raw http 长任务防 undici 300s 超时）
> 实例：`mvn spring-boot:run --auth.captcha.debug-text=true --video.workers=1`（8080，验收后已停止）
> 账号：trial_39178 / E2eTest@2026；scriptId=194（保留供前端查看）
> 链路：DeepSeek 剧本 → 角色卡挂 LoRA → ComfyUI SD1.5+LoRA 分镜 ×5 → ComfyUI Wan2.2 i2v ×5（2s/49f）→ edge-tts + BGM(calm) + 字幕烧录 → 720p 导出

## 验收结果：PASS

| 检查 | 结果 |
|---|---|
| 剧本/角色卡自动创建 + loraName/triggerWord 全链 | ✅ saki / saki_chan_lora.safetensors / saki_chan |
| 分镜 ×5 | ✅ 1280×704，~10-15s/张（含装配开销） |
| 视频 ×5 | ✅ 各 49f / 2.0417s / 1280×704 |
| 导出 | ✅ 1280×720 + 音轨（TTS+BGM）+ 中文字幕烧录 |
| 合成时长 | ✅ 6.784s = 5×2 − 4×0.8（XFADE_DURATION，VideoComposeService 常量） |
| 目视 | ✅ 字幕正确；saki 脸/发色/琥珀瞳/红领结跨镜一致；5.5s 帧重影为 xfade 转场正常观感；"回头微笑"动作成立 |
| 全量回归 | ✅ 346 绿（0 失败 0 错误 7 跳过） |

成片：`uploads/2026/11/15/26a0497b-5025-4f06-accf-136fef37453f_video_1794757657738.mp4`

## 实测计时（⚠️ 受并发 GPU 任务污染，仅参考，不得用于排产）

| 段 | GPU 实测（污染环境） |
|---|---|
| 视频 shot1（首镜） | **890.75s**（/history status.messages 时间戳） |
| 视频 shot2 | 534s |
| 视频 shot3/4/5 | 238 / 236 / 262s |

**污染声明**（2026-11-15 用户告知后取证）：采样期间 Ollama llama-server（PID 32760）与 Qoder CN 桌面版并发任务驻留 GPU（nvidia-smi --query-compute-apps 实证），违反手册 §3.5/§9 干净基准纪律 → 上表全部秒数为**竞争环境下参考值**。
- “冷启动 penalty +500s”“USB4 流式方差”均为**未验证假设**；GPU 竞争是同等可能的解释（shot2 534s vs shot3-5 ~240s 的波动与竞争窗口吻合）。
- **仍有效的结论**：功能验收（上表）；`timeout-seconds=1800` 即使竞争下余量充足（ worst 890s）；workers=1 串行正确。
- **排产基线已由干净复测值替换**（见下节）；手册值保留为历史参照。
- 图像批 5 张 ~73s 同为参考值。

## 干净复测 ✅（2026-11-16 00:00–00:10 本地时钟，替换排产基线）

**三件套取证**：/queue 空（响应非空验证）+ 无下载/pip 进程 + nvidia-smi compute 仅 ComfyUI 自身 python（Ollama 已退出，util 基线 1-3%，显存 ~840-1470MiB 无外来驻留）。
**工具**：`tools/bench-comfyui-i2v.js`（seed=42，计时=/history execution_start→success，前置 queue 非空校验）；17f 为暖机草稿（兼冷启动参照），29f/49f 为正式测量。

| 帧数 | 干净实测 | 手册历史值 | 产物核验 |
|---|---|---|---|
| 17f（冷，后端新启） | **76.4s** | 71.1s（暖） | 1280×704/17f/0.708s |
| 29f（暖） | **123.7s** | 155.4s | 1280×704/29f/1.208s |
| 49f（暖） | **225.6s** | ≈320-397s（外推） | 1280×704/49f/2.042s |

**新排产基线**：边际 ≈ (225.6−123.7)/20 = **5.1 s/帧**；每成片秒 ≈ 102-110s GPU；一集 20 镜×2s ≈ **75min GPU**（旧污染估 2.5h 作废）；timeout-seconds=1800 余量 ≥8×。
**口径说明**：复测快于手册（5.1 vs 8 s/帧）——手册部分基准含 CLIP CPU/竞争窗口状态差异；两口径并存，**排产以本复测为准**，手册值作历史参照。冷启动 penalty 实测仅 ≈+5s（76.4 vs 71.1），旧“+500s”假设彻底作废（纯竞争产物）。

## 过程修正

1. **驱动时长断言误设**：初版阈值 `≥ n×1.5` 未计 xfade 重叠（产品正确：6.8s 期望 vs 6.784s 实测）→ 驱动改为 `|实测 − (n×2 − (n−1)×0.8)| ≤ 0.5`。核实顺序：先读 VideoComposeService.XFADE_DURATION=0.8 再改断言。
2. `/history` 只含已完成任务：渲染中任务在 `/queue`，勿据 history 缺失误判未提交；GPU util 单点采样可能落在 VAE tile I/O 间隙（2%），连续采样判别假死（本次 100% 满载=正常）。
3. **基准纪律缺口**：试产前只查了 /queue 空与无下载，**未枚举 GPU compute 进程**（Ollama/其他 agent 会话）→ 计时被污染。今后基准前置检查加 `nvidia-smi --query-compute-apps`，外来推理进程在跑则不采计时。
4. **后端停止事件 + 空断言陷阱**：复测首跑时 ComfyUI 后端已停（8188 无监听）；而 PowerShell 对 curl 失败的空响应取 `.Count` 得 0，致三件套“queue=0”假阳性。修正：queue 检查须验证响应非空（bench 工具首请求 fetch 失败即 ABORT，未产脏数据）；后端由用户按手册红线在 Desktop 手动 Start 恢复。

## 观察（不阻断）

- 身份一致性=脸/发/瞳/领结（LoRA 生效）；**服装随镜头 prompt 变化**（shot1 西装制服 vs shot5 水手服）——特征表/提示词需统一服装描述才能锁服装，属内容侧约束，非程序缺陷。
- 首镜冷启动 penalty 可通过"试产先跑 17f 草稿暖机"缓解（手册 §6 草稿策略），暂不程序化。

## 状态

暂存不提交；验证实例已停止；后续切片排序（#2-#9）已固化至 [feasibility §7](local-generation-feasibility.md)（Phase 5 唯一路线图入口，2026-11-16 合并整理）。

---

# Phase 5 Track B 评论杂谈自动化验收记录（2026-11-16）

> 驱动：`tools/e2e-commentary.js`（node，RSA-OAEP 认证 + cookie 会话）
> 实例：`java -jar target/spring-ai-demo-2.0.0.jar --video.ffmpeg-path=… --video.ffprobe-path=… --video.python-command=…(edge-tts) --video.commentary.attribution-font=C:/Windows/Fonts/msyh.ttc --video.commentary.import-dir=data/scan-test --auth.captcha.debug-text=true`（8080，验收后已停止）
> 账号：e2e_comm_46098 / E2eTest@2026；scriptId=226（保留供前端查看）
> 链路：导入评论 JSON → 扫描入库（epNN_标签_名称 约定）→ 镜头绑定（视频片段/图片主视觉）→ edge-tts 旁白 + 出处水印 + 字幕烧录 → 9:16 高清导出（同步+异步）

## 验收结果：PASS（commentary-automation 计划 Task 1-6 全实现）

| 检查 | 结果 |
|---|---|
| 评论工作台页面渲染（登录态，全标记） | ✅ quoteRatioBadge/scanAssets/exportCommentaryAsync/pollJob/updateQuoteRatio |
| format 分离 + JSON 导入 | ✅ scriptId=226 format=commentary 3 镜 |
| 扫描入库（仅配置目录，约定解析） | ✅ 2 素材 tags=ep01,名场面 / ep02,日常 |
| 镜头绑定（视频→片段回写时长 / 图片→主视觉） | ✅ videoUrl dur=3；imageStatus READY |
| 同步高清导出（t7 媒体就绪修正：视频绑定镜无图可导出） | ✅ 21.4s 含旁白音轨 |
| 出处水印中文渲染（目视抽帧 10s） | ✅ 「来源：第1话」左下黑底白字 + 旁白字幕烧录 |
| 异步导出 + jobs 轮询终态 | ✅ jobId=1 RUNNING→SUCCESS |
| 引用占比护栏拒绝 | ✅ 67% > 20% 拒绝（预期 ERROR 日志，返 400） |
| 导出历史 | ✅ 2 记录 |
| 全量回归 | ✅ 397 绿（0 失败 0 错误 9 跳过） |
| gated 冒烟 | ✅ VideoComposeAutoFitTest -Dcommentary=true 4/4（单镜+多镜 loop+xfade） |

成片：`uploads/2026/11/16/32485299-abe1-449d-a1e2-49e587bad5d2_video_1794765955065.mp4`（21.4s，9:16）

## 每 Task 审查与修复

- Task 1-3：审查修复（裁剪 fail-fast / 全局 unhandledrejection / updateFormat 白名单 / IMAGE 绑定失效旧视频 / kind 护栏 / assetId null 友好报错）。
- Task 4：审查 PASSED；补多镜头 loop+xfade gated 冒烟闭合单镜覆盖缺口。
- Task 5：审查 1 Major（drawtext expansion=normal 致 %{…} 注入/导出失败→expansion=none）+ 1 Minor（反斜杠两层解析转义不足→4 反斜杠）+ 1 Nit（徽章舍入边界→未舍入判定），均 TDD 补环修复。
- Task 6：审查 PASSED（路径遍历/并发/SecurityContext/资源/回归全核验）。

## 集成核对（t7）修正

1. **doExport 媒体就绪门槛误拒视频绑定镜头**（仅 videoUrl、imageStatus=PENDING）→ 新增 `isShotMediaReady`（镜像 compose 视频优先：videoUrl 非空须 videoStatus READY，否则须图 READY），Track B 视频评论可导出、Track A 零回归；5 回归测试。

## 已知限制（不阻断，留后续切片）

- **字幕重定时**：SubtitleService 时间轴按名义镜头时长；Task 4 TTS 抬升有效时长后，若旁白长于名义时长字幕会偏移。计划 Task 4 显式范围仅视频/音频对齐，字幕重定时留后续切片（需 compose 内按 effectiveDurations 重建 ASS）。本次 E2E 旁白短于镜头时长未触发。

## 浏览器级 UI 验证（2026-11-16 补充，chrome-devtools 真实浏览器）

> 实例：同 Track B 验收实例（8080，--auth.captcha.debug-text=true）；账号 e2e_comm_46098；页面 /commentary-studio。
> 目的：覆盖 API 级 e2e 发现不了的模板渲染/按钮交互类缺陷（对照 Phase 4 C1 截断/M1 掩码陷阱）。

| 检查 | 结果 |
|---|---|
| 登录→/commentary-studio 渲染完整性 | ✅ 24 个 JS 函数全定义 + 18 个关键控件 id 齐全 + **0 控制台 error/warn**（无 Thymeleaf 截断，th:inline="none" 生效） |
| UI 导入解说 JSON | ✅ 粘贴→「导入 JSON」→列表新增 3 镜脚本+编辑器自动打开；镜2 带「引用」徽章+出处水印回填「来源：第1话」 |
| UI 扫描入库 | ✅ 素材库面板→「扫描入库」→状态「扫描入库 2 个」+列表刷新（kind/时长/tags 渲染正确） |
| UI 镜头素材绑定 | ✅ 「绑定素材」模态（按 kind 标注）→视频绑为片段（时长回写 3+视频预览渲染+模态自动关闭）；图绑为主视觉×2（图预览渲染） |
| 引用占比徽章·绿 | ✅ 绑定后 13%（上限20%）绿色；初始边界 20% 不误报超限 |
| UI 异步导出+jobs 轮询 | ✅ 「后台导出」→按钮禁用+提交中→RUNNING→SUCCESS（21s/720p/9:16/含字幕）→轮询终态即停+按钮恢复+历史刷新含下载链接 |
| 引用占比徽章·红+护栏 | ✅ UI 编辑时长致 21%→徽章红「超限，导出将被拒绝」；「后台导出」→FAILED「引用占比超限：21% > 上限 20%…」+轮询停+按钮恢复 |
| 恢复 | ✅ 时长改回 10s 逐镜保存→徽章回绿 13% |
| 全页截图目视 | ✅ 头部导航/脚本列表/编辑器三镜卡片/导出面板/素材库面板全渲染，按钮与预览均正常 |

**结论：浏览器全流程零缺陷**，无需修复（C1 模板截断/M1 错误掩码两类陷阱在本页均不存在）。导出面板状态文本保留最后一次任务结果（超限测试的 FAILED 文案）属预期行为。

---

# Phase 5 Track B 真实脚本端到端实测（FX《战士久留美》·2026-11-16）

> 输入：`docs/story/FX/kurumi-commentary-domestic.json`（评论类 · 10 镜 92s · 5 处引用 insert · 主 9:16/B站 16:9）
> 驱动：`tools/e2e-fx-kurumi.js`（probe/full 两模式，RSA-OAEP 认证 + cookie 会话）
> 实例：`java -jar target/spring-ai-demo-2.0.0.jar --video.ffmpeg-path=… --video.ffprobe-path=… --video.python-command=…(edge-tts) --video.commentary.attribution-font=C:/Windows/Fonts/msyh.ttc --video.commentary.import-dir=data/scan-test --auth.captcha.debug-text=true`（8080，验收后停止）；账号 fx_kurumi_98185；scriptId=292
> jar 陈旧已 `mvn -DskipTests clean package` 重建；生图后端 zhipu cogview-3-flash（~8.7s/张）

## 结果：PASS（真实 FX 脚本跑通到 B站 16:9 成片）

| 检查 | 结果 |
|---|---|
| 导入 FX JSON | ✅ 10 镜 format=commentary；5 镜自动挂 insertAttribution（第1/3/4话 · 评论引用） |
| 引用占比护栏实测（导入即导出） | ✅ 拒绝「引用占比超限：49% > 上限 20%，请减少引用素材或增加原创镜头」（status=500） |
| fallback（脚本 §1.4 素材缺失→原创画面顶替） | ✅ 清除 5 镜水印后引用归零（0/92），过护栏 |
| 10 镜原创画面真实生成 | ✅ 全部 imageStatus READY（zhipu，逐镜同步端点） |
| 异步导出 16:9 720p + jobs 轮询 | ✅ RUNNING→SUCCESS（约 50s，10 次轮询） |
| 成片核验（ffprobe） | ✅ 1280×720 / 90.8s / 2724帧@30fps / aac 音轨（旁白 TTS+BGM）/ 5.4MB |
| 抽帧目视 | ✅ 2s 钩子卡+字幕「一部开场就铺满风险警示…今天聊《FX战士久留美》。」+右下「AI生成」标识；45s 白银数据卡+对应旁白字幕 |

成片：`uploads/2026/11/16/dde849c6-d0e6-49f6-9d0b-f17e3bddb137_video_1794810648698.mp4`（90.8s，16:9）

## 关键发现：护栏口径 vs 脚本设计（真实脚本才暴露）

- 护栏 `insertRatio` 以「带 insertAttribution 镜的**整段时长**」计引用（in-app compose 模型：绑定片段的镜整段即引用）→ FX 5 镜整段 45s/92s = **48.9%**。
- 脚本 `insertPolicy.ratio=15.2%` 是「插入片段实际 2-3s 占各镜前段、其余原创」口径，对应 §4.1 **外部拼接工具** `auto-commentary-concat.js`，非 in-app 整镜模型。
- **判定：非程序 bug**，是两套引用模型的口径差异。FX 长插入镜（8-10s）在 in-app 20% 上限下必然超限；合规出片走脚本自身 fallback（原创画面顶替、评论句式不变，本次实证）或外部拼接工具（有真实截图时）。
- **建议（未改代码，留产品决策）**：若需 in-app 支持「短插入+长原创」混合镜，可让护栏按 `insert.seconds` 而非整镜时长计占比（需确认 + TDD 先红后绿）。

## 观察（不阻断）

- 末镜「纯黑文字尾卡」被 cogview 渲染为相机器材图——AI 生图对纯排版文字卡 prompt 的还原度波动，属内容侧（换文字渲染更强的 ark/dashscope 或后期文字卡叠加），非流程缺陷。
- 合成时长 90.8s ≈ 名义 92s − 9×0.8 xfade + TTS 有效时长抬升（Task 4 自动对齐生效，旁白长于名义镜头时抬升填充）。

## 状态

暂存不提交；验证实例已停止；四双语文档 + VIDEO_STUDIO_PLAN 已同步 Phase 5 Track B 节。

---

# Phase 5 Track A 真实脚本端到端实测（GTA6《十三年等待》·本地零云·2026-11-16）

用 FX 同批的真实脚本 `docs/story/gta6/gta6-13-years-script.json`（16:9 横版母版，9 镜 75s，1 角色「阿哲」）驱动 **Track A（AI 短剧集·本地零云依赖）** 整条链路：ComfyUI SD1.5 分镜 → ComfyUI Wan2.2 i2v → edge-tts → FFmpeg 导出，图像/视频生成全程零云。

## 结果 PASS

| 环节 | 端点 | 实测 |
|---|---|---|
| 建脚本 | `POST /api/video/scripts`（storyboard 结构，shotOrder→order、voice→suggestedVoice） | scriptId=323，9 镜，Σ名义 75s |
| 角色卡 | `GET /characters` + `PUT /{id}/style` CINEMATIC_REALISM | 阿哲(feature=Y)；style success |
| 切零云源 | `PUT /images/generator?type=comfyui` + `/videos/generator?type=comfyui` | 均 success |
| 分镜图 | `POST /images/shot/{sid}/{shid}` ×9 | **9/9 READY**，ComfyUI SD1.5 ~8s/张（12→77s） |
| 图生视频 | `POST /videos/shot/{sid}/{shid}`（shot1 5s/121f、shot7 6s/145f） | **2/2 READY**，源 mp4 1.19MB/3.1MB（含运动） |
| 高清导出 | `POST /export/hd` 16:9 720p 字幕+BGM calm+TTS | SUCCESS，url 落地 |
| 成片核验 | ffprobe | **1280×720 h264 30fps / 71.5s / 2145 帧 / aac / 7.25MB** |
| 抽帧目视 | 2s/20s/49s/68s | 旁白+对白字幕逐镜烧录正确；i2v 镜有运动 |

成片：`uploads/2026/11/16/cad8e0c4-…_video_1794814256352.mp4`（scriptId=323 保留供前端查看）。

## GPU 预算与耗时

- i2v 帧数随时长线性增长（`computeFrames = min(15,dur)×24` 取 4n+1）；母版全量原生时长 i2v ≈ 1800 帧 ≈ **2+ 小时**，不现实。
- 本次刻意只对 2 个短镜（shot1 钩子 5s、shot7 霓虹 6s）跑 i2v，其余 7 镜静图运镜合成——实测 shot1 ~13min、shot7 ~16min（含模型冷加载，慢于干净基线 5.1s/帧），全流程 total≈2001s(~33min)。
- 结论：Track A 全量长镜 i2v 需按预算裁剪运动镜数，或降镜时长。

## 观察（内容/合规侧，非流程缺陷）

- **画风偏动漫**：prompt 与 style 均为「电影写实/photorealistic」，但本机 SD1.5 base checkpoint 为动漫向（anything-v5 系），出图渲染为动漫风。若要 GTA6 的写实电影感，需换写实 checkpoint（如 RealVis/Juggernaut 类），属模型选型而非链路问题。
- **抽象/负向 prompt 还原波动**：shot9「纯黑无图像尾卡」被渲染为动漫人物（与 FX 末镜同现象）；AI 生图对「无文字/纯底」类 prompt 易幻化内容。
- **Track A 无自动「AI 生成」可视角标**：compose 水印仅在 `insertAttribution` 非空时叠加（Track B 评论专用，本脚本全空），`withLogo` 为品牌 logo 图片（`video.watermark.logo-path` 未配置→跳过）。故成片无 AI 标识属当前实现预期；若面向 B站/抖音需强制「AI 生成」披露角标，是一个待补的合规特性（需确认 + TDD）。
- **时长 71.5s vs 名义 68.6s**（75 − 8×0.8 xfade）：TTS 旁白有效时长抬升（Task 4 自动对齐），与 FX 实测同现象，非缺陷。

## 状态

Track A GTA6 零云链路实证 PASS；驱动 `tools/e2e-gta6-track-a.js` 与本节记录暂存不提交；验证实例已停止。

# Phase 5 Track A 真实脚本端到端实测（BLG 夺冠双脚本·本地零云·2026-11-16）

> 驱动：`tools/e2e-blg-track-a.js <scriptFile> <i2vOrders>`（参数化，复用 GTA6 idiom，BGM 按 JSON `bgmCategory` 自动选取）。
> 输入：`docs/story/BLG/blg-faker-mountain-script.json`（9 镜 74s，epic）、`docs/story/BLG/blg-23-years-script.json`（11 镜 94s，calm）。
> 环境：ComfyUI 8188 存活、jar 14:25 版（无源码改动）、`--auth.captcha.debug-text=true --video.workers=1`。

## 结果

| 项 | 脚本二《那座山，翻过去了》(scriptId=354) | 脚本一《二十三年，把「玩」玩成正经事》(scriptId=355) |
|---|---|---|
| storyboard 导入 | PASS 9/9 镜 74s | PASS 11/11 镜 94s |
| 画风/角色 | CINEMATIC_REALISM 成功；角色「无名选手」feature=Y | 同左 |
| SD1.5 分镜 | 9/9 READY（~8s/张，75s 完成） | 11/11 READY（91s 完成） |
| Wan2.2 i2v | shot1(6s/145f) @1005s、shot6(8s/193f) @2415s | shot1(7s/169f) @1270s、shot10(9s/217f) @2959s |
| HD 导出 | PASS 1280×720 h264/aac，total 2478s(~41min) | PASS 1280×720，total 3038s(~51min) |
| 成片 | `uploads/2026/11/16/cade484f-…_video_1794819083268.mp4`（95.6s） | `uploads/2026/11/16/a73d2da9-…_video_1794822529870.mp4`（130.0s） |
| 抽帧目视 | 字幕逐镜正确（含「十三场不败」「1834 天」长句完整烧录）；shot1 雪山 i2v 电影感好；shot5/7 动漫风 | shot10 夺冠蒙太奇 i2v 情绪到位（举臂+水晶枢纽爆）；shot8 雅加达画面与旁白契合 |

## 观察（内容侧，非流程缺陷）

- **TTS 时长抬升显著**：脚本二 95.6s vs 名义 67.6s（+41%）、脚本一 130.0s vs 86.0s（+51%）——BLG 旁白密度远高于 GTA6（多镜 40+ 字），每镜被拉长到念完为止。发布前建议精简旁白或按抬升后时长重排分镜表（GTA6 仅 +4%）。
- **尾卡幻化复现**：两份脚本的「黑底尾卡」均被 SD1.5 渲染为灰色抽象物（同 GTA6 shot9/FX 末镜）——尾卡应改走纯色/文字压图而非生图 prompt。
- **screenCaptions 不落地**：JSON 中「屏幕字后期压」字段 Track A 管线不消费（仅烧录旁白/对白字幕），压字需发布环节补。
- 画风偏动漫、无 AI 角标两项同 GTA6 观察，不再重复。

## 状态

Track A BLG 双脚本零云链路实证 PASS（2/2 成片）；驱动 `tools/e2e-blg-track-a.js` 暂存不提交；验证实例（pid 18584）已停止。

---

# Phase 5 Track A 修复复测（旁白精简 + 尾卡本地渲染·2026-11-16/17）

> 用户看完 BLG 两片后指出两问题：①旁白太密致成片 +41%/+51%；②尾卡镜（S9/S11）SD1.5 幻化。本轮修复后重跑。

## 修复 1：尾卡镜跳过生图（源码改动，TDD 先红后绿）

- 评估结论：原链路**不支持**跳过生图——`ImageGenerationService.generateForShot` 无条件走 provider，导出校验要求每镜有媒体。采用最小侵入方案：生图阶段旁路，compose/字幕/导出零改动。
- 实现：`StoryShot`/`Shot` 新增 `endCard` Boolean（ddl-auto=update 自动建列）；组装链路全点贯穿（controller mapToStoryboard、StoryScriptService saveFromStoryboard×2/toStoryboard/shotToMap）；`Boolean.TRUE.equals(endCard)` 时走新 `EndCardImageRenderer`（FFmpeg lavfi 纯色底 + drawtext 居中压字，fontfile=msyh.ttc、expansion=none，转义对齐 attributionFilter idiom），产物 PNG 入库按普通静图 READY；旁白仍正常 TTS+烧字幕。
- 测试：新增 `EndCardImageRendererTest`（含真实 FFmpeg 渲染像素级核验：1280×720、黑角、中心亮带验中文落墨）、`ImageGenerationServiceEndCardTest`（4 用例）、`StoryScriptEndCardMapTest`（3 用例）；既有 5 个测试构造点补第 10 参。**全量回归 407/407 绿**，jar 重建 21:39。
- JSON：脚本二 S9、脚本一 S11 加 `"endCard": true`；驱动 storyboard 映射透传 endCard。

## 修复 2：旁白精简到 2.5 字/秒×名义时长

- 两份 JSON 逐镜重写 narration，全部 ≤ round(2.5×dur)（脚本二 11–27 字/镜，脚本一 14–25 字/镜）；事实数字（比分/日期/1834 天等）让位给 screenCaptions/字幕卡，情绪落点保留；尾卡镜旁白即 endCardText 主句（兼作压字文本与 TTS）。校验脚本逐镜复核 OK。

## 复测结果（重跑两片，均 PASS）

| 项 | 脚本二《那座山》(scriptId=418) | 脚本一《二十三年》(scriptId=419) |
|---|---|---|
| 尾卡镜 | S9 `endcard_*.png` @93s（与 SD 镜同秒完成，零 GPU） | S11 `endcard_*.png` @92s |
| i2v | shot1(6s) @1257s、shot6(8s/193f) @2826s | shot1(7s) @1370s、shot10(9s/217f) @3218s |
| 成片时长 | **67.58s vs 名义 67.6s（偏差 0%，原 +41%）** | **85.97s vs 名义 86.0s（偏差 0%，原 +51%）** |
| 尾卡抽帧 | 纯黑底白字「那座山，我们翻过去了。」无幻化 | 纯黑底白字「他们用二十三年，把「玩」玩成了正经事。」无幻化 |
| 成片 | `uploads/2026/11/16/a250096e-…_video_1794842710624.mp4` | `uploads/2026/11/17/354b2d1a-…_video_1794846407554.mp4` |

## 过程发现（运维侧）

- **8s 镜 i2v（193 帧）实测 ~25min，顶到 `video.comfyui.timeout-seconds=1800` 红线**：首跑 shot6 超时 FAIL。复测以 `--video.comfyui.timeout-seconds=2700` 启动后通过；9s 镜（217 帧）@~31min 同余量不足。**2026-11-17 用户拍板：yml 默认值已上调 1800→2700**，后续无需再带启动参数。
- **长阻塞期间服务端 session 过期致导出报「用户未登录」**：驱动加 keepAlive（2min 带 cookie 轮询 /api/video/bgm）后消除。
- 中文旁白密度经验值再证：**2.5 字/秒 cap 下 TTS 抬升≈0**，可作 Track A 分镜表字数预算标准。

## 状态

两问题均修复并实片复验 PASS；源码/测试/JSON/驱动改动暂存不提交；验证实例已停止。

---

# Phase 5 成片归档（uploads/final/）验收（2026-11-17）

> 用户诉求：uploads 日期目录文件太多，最终成品视频/封面单独规划文件夹，按「成片名称-图片/视频」命名。
> 口径（AskUserQuestion 已定）：①仅成片视频+封面进 `final/`，中间产物留日期目录；②重名追加时间戳后缀防覆盖；③存量迁移=复制归集、不删原件。

## 实现

- **StorageService.uploadFinal（default 方法）**：新建 `uploadFinal(is, title, category, extension, contentType)`，default 降级为普通 `upload`（oss/minio/fastdfs 零影响）；LocalStorageService 覆写为 `uploads/final/<清洗后标题>-<类别>.<ext>`，重名自动追加 `-时间戳`。文件名清洗去 `\ / : * ? " < > |` 与 `..`、超 80 字符截断（防穿越 + Windows 合法）。
- **标题贯穿**：`StoryScript.getTitle()` → `doExport` 设 `ComposeOptions.finalTitle`（仅 HD，preview 传 null）→ `storeVideo` 路由 uploadFinal；`generateCover` 捕获脚本标题 → `renderVideoCover` 三参重载走 uploadFinal。无标题一律回落 legacy `video_*/video_cover_*` 日期目录路径，存量零回归。
- **访问无需改配置**：`/images/**` 映射 uploads 根，`/images/final/xxx.mp4` 自动可访问；download/delete 防穿越校验对子目录仍通过。

## TDD（先红后绿，12 绿）

- `LocalStorageServiceFinalTest` 7：友好命名/非法字符清洗/路径穿越/空标题/重名时间戳/80 截断/interface default 降级。
- `VideoComposeServiceStoreFinalTest` 3：有 finalTitle→uploadFinal、无→legacy、空白→legacy。
- `VideoExportFinalTitleWiringTest` 2：doExport 传 script 标题；generateCover 传标题。
- 踩坑：Java 正则字符类匹配字面反斜杠须写 4 个反斜杠（`[\\\\/…]`），首版漏掉导致含 `\` 标题写入失败。

## 验证

- **全量回归**：`mvn clean package` **419/419 绿**（9 skipped），jar 重建。
- **真实导出（无 GPU）**：`tools/verify-final-archive.js`——既有 PNG 绑镜标 READY → export/hd → `uploads/final/验收-成片归档-视频.mp4` + `-封面.jpg` 落地 PASS；重跑实测重名后缀 `…-视频-1794869987543.mp4` PASS。
- **存量归集**：H2 离线导出 SUCCESS 记录（`target/dump-finals.sql`）→ `node tools/migrate-finals-to-archive.js` 实跑归档 **19 视频 + 3 封面**（另 2 条已在 final/ 跳过），日期目录原件全部保留；`uploads/final/` 现存 22 个友好命名成片。

## 状态

成片归档功能 PASS；源码/测试改动与两个 tools 脚本暂存不提交；验证实例已停止。

---

# 素材库与画面感增强（S1→S5）验收（2026-11-17 起，代码切片收口于 2026-11-18）

范围：设计 `docs/superpowers/specs/2026-11-17-asset-library-design.md`；实施计划 `docs/superpowers/plans/2026-11-17-asset-library.md`（14 Task / 5 切片，逐 Task 审查 + 变异复证）。

## 结果：代码切片 PASS（S1–S5 全部收口）；唯一未闭合项 = 组合 IP-Adapter+LoRA 模板由用户手动校准（计划明文 AI 不代搭）

## 每切片门槛（全量 `mvn clean package` / `clean test`）

| 切片 | Task | 全量结果 | 本切片交付 |
|---|---|---|---|
| S1 数据面 | 1–3 | **439 tests / 0 fail / 9 skipped** | `MediaAsset.category`（CHARACTER/SCENE/INSERT，null 一律 INSERT 保 Track B）+ `char_`/`scene_` 参考素材扫描目录 + `StoryShot.referenceAssetIds` 与绑定校验（伪造/越权 id 服务端剔除） |
| S2 匹配 | 4–5 | **454 / 0 fail / 9 skipped** | `AssetMatchService`（LLM 推荐 + 关键词重叠降级 + 阅后即焚不落库）+ `POST /{sid}/asset-matches` + 前端 chip 流 |
| S3 生成 | 6–9 | **488 / 0 fail**（T10 复跑含 T8/T9 新增） | `ReferenceImageResolver` 素材参考图 + `generateWithLoraAndReferences`/`generateWithSeed` 组合链路与路由 seam + ComfyUI IP-Adapter 模板装配 + gated 真实冒烟 |
| S4 素材获取 | 10–12 | **543 / 0 fail / 0 error / 12 skipped** | `BooruProvider` 双源适配器（safebooru + danbooru-safe）+ `BooruSearchService` 三道闸（rating 白名单 / host 白名单 / 20MB+30s+Content-Length）+ 三端点 + 素材库面板（上传/扫描/网搜三入口） |
| S5 脚本增强 | 13–14 | **562 / 0 fail / 0 error / 12 skipped** | 分镜 system prompt 画面感升级（四要素/旁白字数/imagePrompt 公式/禁令/范例镜）+ 素材清单注入（≤30 条、tags 40、行 60、空库与异常零注入）+ 全链路 e2e |

## gated 冒烟执行状态（明示已跑与未跑）

- `IpAdapterRealSmokeTest -Dipadapter=true`（ComfyUI 8188 在线，Task 9）：**19/19 绿 27.2s**；同 prompt 同 seed 有/无参考图对照组 side-by-side 合成图交付——左图继承参考图的青雾、暖橙灯笼、雨丝、湿石板反射，右图无参考时构图漂移。**结论：IP-Adapter 传图链路在真实 ComfyUI 上生效。**
- `BooruRealSmokeTest -Dbooru=true`（Task 12 起，每轮修完护栏复跑）：**3 例 / 0 失败 / 1 skip**（skip 项 = 本机访问 safebooru.donmai.us 站点 API 返 403，非白名单/代码问题；cdn.donmai.us 图片域已单独用 yml/适配器/`@Value` 三侧同步钉住）。
- 未闭合：组合模板 `sd15_ipadapter_lora.template.json` 未落盘前，`generateWithLoraAndReferences` 恒降级为纯 LoRA 路径（WARN 每实例一次）。待办：节点 10→11 之间串 `LoraLoader` + `{{LORA_NAME}}` + strength，另存为组合模板名。

## 全链路 e2e（`tools/e2e-asset-library.js`，2026-11-18 实跑两轮）

注册 → 页面标记（含面板/网搜/扫描/chip 共 9 项）→ 上传 CHARACTER 素材并断言 `url` 为自家存储（红线）→ `scan-reference`（未配置目录时 0 且不报错）→ `category` 过滤 → LLM 生成 6 镜脚本 → **`asset-matches source=LLM`** 且推荐不落库 → 人工确认绑定（含伪造 id 被剔除 `kept=[261]`）→ booru 实搜 20 条（ratings 仅 general/safe）+ 代理缩略图 200 `image/jpeg` → **ComfyUI 逐镜真实生图全 READY（镜 1 带绑定参考图走 IP-Adapter）** → `export/hd` 成片归档 `/images/final/雨巷里的一碗暖-视频.mp4`。
两轮 `exitCode=0`，17 项 PASS + 1 项 INFO，0 FAIL；软断言「脚本是否复用库内角色名『林晚』」两轮均 **true**（S5 清单注入的真实效果证据）。

首轮实测教训两条已写进脚本注释：导出闸要求**全镜 READY**（只生第一镜必红），以及 PUT `shots/{shotId}` 直接返回 shot map（断言要打在 `data.referenceAssetIds` 数组上，写错路径会得到恒真的 `undefined`）。

## Track B 回归硬门槛（存量行为逐字不变）

`node tools/e2e-commentary.js`（需 `video.commentary.import-dir` 指向含 `epNN_标签_名称.mp4/png` 的目录，本轮临时造 1 mp4 + 1 png）：**12 项全 PASS**——评论导入、扫描入库（tags 解析 `ep01,piantou`）、视频/图片绑定、同步 HD 导出（21s 成片）、异步导出（RUNNING→SUCCESS）、引用占比护栏（47% > 20% 拒绝）、导出历史。
另：`VideoComposeServiceTimeoutTest.hangingProcessIsKilledAfterTimeout` 为**已知环境性 flaky**（Windows 被 kill 进程仍持有 temp 目录 `cmd-*.log` 句柄 → `@TempDir` 清理失败，Task 8 已记过一次）；单跑 2 次绿、随后全量门禁绿，非断言失败。

## 浏览器级 UI 验证（chrome-devtools 真实浏览器，Task 12）

两轮（fix round 1 / round 2 各一次，真实 safebooru 数据）：顶栏按钮开面板 → canvas 造 PNG 走真实 `onchange` 上传入库 → 网搜 20 条（缩略图经服务端代理，`postId` 经 `Number()` 后为纯数字）→ 勾选入库 → 网格刷新且 `sourceNote` 段序为 `URL | rating=general | tags=...`、`url` 全为自家存储 → 删除走 confirm 且网格同步 → 边界（未知 provider 400、缺 postId 400、不存在帖 500、`/preview` 魔数嗅探 `image/jpeg` + `FF D8 FF E0`）→ `esc('a"b<c')` 实跑 `a&quot;b&lt;c`。控制台除上游突发 404/500 外零 JS 错误。

## 审查与变异复证（每 Task 一轮以上）

Task 1–14 逐 Task 派 CodeReview 子代理（spec 合规 + 质量双结论），fix round 最多 2 轮；变异复证累计：Task 11 三轮、Task 12 七轮、Task 13 两轮共 22 条。反复被审查者抓到、值得后人记住的四类自欺：
1. **恒真断言**（`forEach(≤60)` 在短载荷下、`verifyNoInteractions` 指向从不参与的 bean、`length == codePoints().count()` 对孤立代理照样绿、`undefined` 路径上的 `indexOf < 0`）；
2. **两份格式清单**（读侧魔数嗅探与写侧扩展名映射不同步 → GIF 被降级成 octet-stream）；
3. **上游真实形态与 spec 假设不符**（safebooru 的 rating 是 `general` 而非 `safe`；表现是「搜索成功、空列表」而不是报错）；
4. **文本删除类零覆盖**（prompt 升级项只改文本不写断言，删掉任何一条都全绿）。

## 已知限制 / 遗留（不阻断）

- 不存在的 `postId` 走 `/preview`、`/ingest` 现为 **500**（适配器抛普通 `RuntimeException`「帖子不存在」）：属客户端可修正错误却归成服务端故障，修法需类型化异常或重查未命中返 null，是跨 Task 契约调整，未在本计划内静默改。
- 面板一次并发拉 20 张代理缩略图时，上游 safebooru 偶发对其中数个 id 的重查返 404（顺序重发全部 200）→ 用户偶见破图，重搜即可。设计建议：`preview` 复用 `search` 已回传并已过闸的 `previewUrl`，可免去每图一次重查（闸 2 语义须保留在 ingest 路径）。
- `BooruPost` 无 author 字段 → `sourceNote` 缺作者信息（Task 10 契约缺口，spec 验收矩阵「出处完整」按 URL+rating+tags 达成）。
- 下载护栏残留：中途彻底不吐字节的硬卡死仍靠 TCP/连接层自断，真要钉死需看门狗线程 `close` 该流（已写进代码注释，未假装修完）。
- 网搜入库的素材在 `sourceNote` 里只带 URL + rating + tags（上游无 author 字段），且 `tags`/`sourceNote` 均按列宽 500 截断；上传时不写 license 信息。

## 状态

S1–S5 代码切片全部收口；源码/测试改动、新增 `tools/e2e-asset-library.js` 与本验收文档均**暂存不提交**（HEAD 仍 `1963d2a`）。唯一待用户动作 = 组合 IP-Adapter+LoRA 模板校准（校准后 `generateWithLoraAndReferences` 自动升级，无需改码）。验证实例已停止，临时 import-dir 仅存在于 gitignore 的工作区目录。
