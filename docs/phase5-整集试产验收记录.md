git commit -m "feat(phase5): 本地 ComfyUI 生成链路与评论杂谈自动化工作台" -m "Track A（AI 短剧集·本地零云依赖）:
- ComfyUI Wan2.2 i2v 与 SD1.5+LoRA 分镜生成器、注册与模板；角色卡 loraName/triggerWord 全链；local 存储首帧直通
- 排产加固：输出节点解析 + metadata 免解码核帧；干净复测基线与整集试产验收

Track B（评论杂谈·独立工作台 /commentary-studio）:
- format 分离 + 解说 JSON 导入；素材库（上传/裁剪/文件夹扫描入库）；镜头素材绑定（视频片段/图片主视觉）
- 合成自动对齐：TTS 预探测抬升有效时长 + 短片段循环填充；出处水印 + 引用占比≤20% 版权护栏
- 异步高清导出 + jobs 轮询；导出媒体就绪判定兼容仅绑视频镜头（镜像合成视频优先）

工具与文档:
- 发片拼接/整集试产/ComfyUI 基准/评论 e2e 脚本；四双语文档、实施计划与验收记录同步

验证：全量 397 绿；gated 冒烟 comfyui/commentary；API e2e 与浏览器 UI 全流程 PASS"# Phase 5 #1 整集试产验收记录（2026-11-15）

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

## 状态

暂存不提交；验证实例已停止；四双语文档 + VIDEO_STUDIO_PLAN 已同步 Phase 5 Track B 节。