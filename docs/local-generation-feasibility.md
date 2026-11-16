# 本地生成可行性分析：Spring AI 编排 + 本地 ComfyUI 执行

> 日期：2026-11-15 · 依据：系统信息截图 + `nvidia-smi` 实测 + 代码链路核实
> 目标：评估"应用程序（spring-ai-demo）编排 + 本地跑开源模型"的零/低成本漫剧路线可行性

## 1. 结论先行

**可行，且本机正是社区 8G 显存漫剧流水线的入门甜点位。**

- 图像侧：SDXL / Flux FP8 本地可跑；项目已有 `LocalSdImageGenerator`（A1111 协议）接入先例。
- 视频侧：Wan2.2 TI2V-5B（Apache 2.0 可商用）FP8 + offload 可在 8G 显存跑 720P 图生视频；项目 `VideoGenerator` 接口 + 工厂模式使新增 `ComfyUiVideoGenerator` 成为中等规模切片。
- 混合架构最大红利：**ComfyUI 走 localhost 通信，首帧图经 resolveMediaUrl 直通相对路径 + 同源取字节直传（切片 1 落地），彻底绕开云端视频 API 的"公网 URL / oss-minio"约束**——本地存储即可全链路跑通。
- 推荐组合：智谱免费云生图（不占显存、角色参考图已验证）+ 本地 Wan2.2 i2v（免费、无限速）+ 本地 FFmpeg/edge-tts 合成。全链路 ¥0。

## 2. 硬件实况

| 项 | 值 | 来源 |
|---|---|---|
| 机型 | 华为 MateBook 16s（笔记本） | 截图 |
| CPU | i9-13900H（13 代，2.60 GHz） | 截图 |
| 内存 | 32 GB DDR5-4800（31.7 GB 可用） | 截图 |
| GPU | NVIDIA GeForce RTX 5060 Laptop，8 GB（8151 MiB），Blackwell sm_120 | 截图 + nvidia-smi |
| 核显 | Intel Iris Xe（128 MB） | 截图 |
| 驱动 / CUDA | 610.88 / CUDA UMD 13.3 | nvidia-smi 实测 |
| 功耗墙 | 155 W（cap） | nvidia-smi 实测 |
| 存储 | 954 GB，已用 445 GB，**剩余约 509 GB** | 截图 |
| 系统 | Windows 11 家庭中文版 26H2（26300.9974） | 截图 |

**实测关键事实**：空闲时 IDEA/Chrome/微信等日常进程已占 **1867 MiB 显存**（WDDM 桌面合成占用），推理实际可用约 **6.2 GB**。

## 3. 关键约束与对策

| 约束 | 影响 | 对策 |
|---|---|---|
| 8G 显存 − WDDM 占用 ≈ 6.2G | 14B FP16 必 OOM；5B FP8 刚好 | FP8 量化权重 + ComfyUI offload/block swap；渲染时关浏览器硬件加速或容忍 6.2G |
| Blackwell sm_120 | 旧 PyTorch 不识别架构 | **PyTorch ≥ 2.7 + cu128**；ComfyUI 用最新 portable 或手动装环境，勿用旧整合包 |
| 笔记本散热（155W 持续） | 长渲染降频 | 性能模式 + 散热垫；批量渲染放夜间；单段时长控制在 5s |
| 32G 内存 | offload 交换空间 | 刚好达标（社区建议 32G），渲染时避免同时开重型 IDE 工程 |
| 磁盘剩 509G | 模型体积 | 5B FP8 ≈ 6–10G、14B FP8 ≈ 17G、Flux FP8 ≈ 12G、SDXL ≈ 7G，全装也够 |
| 单 GPU 多模型 | 图像/视频模型抢显存 | ComfyUI 智能内存管理自动卸载；应用侧图像批与视频批保持串行 |

## 4. 本机能力矩阵（2026-11-15 实测，源：docs/comfyui/漫剧流水线-本机实测手册.md）

| 任务 | 配方 | 实测耗时 | 实测峰值显存 |
|---|---|---|---|
| 角色定妆分镜 1280×704 | SD1.5 anything-v5 + 角色 LoRA，22 步 CFG7 | **7.2–8.8 s/张** | ~2.6 GiB |
| 机位/构图锁定 | + ControlNet canny 0.8 | **+10 s** | 4.18 GiB |
| i2v 0.7s（17f） | Wan2.2 5B fp8 + **VAEDecodeTiled** + shift8/CFG5 | **71 s** | 7.14 GiB |
| i2v 1.2s（29f） | 同上 | **155 s** | 7.15 GiB |
| i2v 2.4s（57f） | 同上 | **382 s** | 7.21 GiB |
| i2v 5.0s（121f） | 同上 | **722 s** | 6.91 GiB |
| RIFE ×2 补帧 | 核心内置 FrameInterpolate | **~3 s** | 0.92 GiB |
| 新角色 LoRA 训练 | OneTrainer 24 图 960 步 | **7.5 min** | 3.2 GiB |

**实测定律**（排产依据）：
- 边际≈8 s GPU/输出帧（手册历史口径），**无规模效应**→短切 1.5–2.5s 是最实在的提速手段。**排产以 2026-11-16 干净复测为准：边际≈5.1s/帧、29f≈124s、49f≈226s、20 镜×2s 集≈75min GPU**（§7→验收记录）；手册值作历史参照。
- **VAEDecodeTiled 强制**：裸 VAEDecode 在本机（12.6GB 权重流式进 8G 卡）>40min 假死且不可中断。
- **length=故事时长**：半帧+RIFE 换时长=慢动作（动作量仅 36%），禁用；RIFE 仅 48fps 顺滑/刻意慢动作。
- **身份一致性=LoRA**；IPAdapter 实测只迁风格/构图不迁身份。
- 训练与视频不可同卡并行；基准期间禁下载/pip（USB4 单链路抢占污染计时）。
- 14B/SDXL 本机不可行；5B 无 distill LoRA，维持 20 步 CFG5。

## 5. 混合架构设计（应用编排 + 本地执行）

### 5.1 现有接入点（已核实）

- 图像侧：[LocalSdImageGenerator](../src/main/java/com/github/ylyan2015/springaidemo/video/generator/LocalSdImageGenerator.java) 调 A1111 `POST /sdapi/v1/txt2img`（默认 127.0.0.1:7860），base64 → `storageService.upload` 转存。**本地存储闭环先例已存在**。
- 视频侧：[VideoGenerator](../src/main/java/com/github/ylyan2015/springaidemo/video/generator/VideoGenerator.java) 接口（`generate/getType/getName/isAvailable`），现有 `none` / `dashscope-wan` 两实现，工厂按 `video.generator` 配置分派。
- 合成侧：`VideoComposeService` 不关心素材来源，真片段 ffprobe 实测时长，本地 FFmpeg 全免费。

### 5.2 新增 ComfyUiVideoGenerator（设计草图 → 已实现：切片 1/2，最终配置以 README 为准）

```
配置：video.generator=comfyui
      video.comfyui.base-url=http://127.0.0.1:8188
      video.comfyui.workflow=wan22_i2v_5b.json   # 参数化工作流模板
      video.comfyui.timeout-seconds=1800        # 本地渲染慢，超时放宽
```

流程（全 localhost，无公网 URL 依赖）：

1. `resolveMediaUrl` 增 local×comfyui 直通分支返回相对路径；生成器以同源 `http://127.0.0.1:{server.port}` 取首帧字节（http/data URI 亦支持）→ `POST /upload/image` 传入 ComfyUI input 目录；
2. 装配工作流 JSON：LoadImage 节点 filename、prompt、帧数（时长×fps）、分辨率（720P/480P 档位）；
3. `POST /prompt` 提交 → 轮询 `GET /history/{prompt_id}` 至 outputs 出现；
4. `GET /view?filename=...` 下载 mp4 字节 → `storageService.upload` 转存 → 返回 `GeneratedVideo`。

要点：

- **resolveMediaUrl local×comfyui 直通**（返回相对路径），生成器同源取字节，与 `ReferenceImageResolver` 的 base64 降级同思路；local×dashscope-wan 存量抛异常行为不变；
- `isAvailable()` 探测 `GET /system_stats`，未启动时前端下拉置灰，回退 `none` 静图运镜；
- ComfyUI 队列天然串行，天然避免 GPU 并发 OOM；应用侧图像批（local 源时）与视频批保持串行，`image.local.timeout` 按本地出图速度调大；
- 测试 seam 仿 `DashScopeWanVideoGenerator`：工作流装配包私有 + HttpClient 注入 mock。

### 5.3 图像侧选型

- **推荐混合**：智谱免费云生图（CogView 免费档已实测，角色参考图一致性链路现成）→ 不占显存、秒级出图；本地 GPU 专供 i2v。
- 纯本地已落地（切片 3）：`image.generator=comfyui`（SD1.5+角色 LoRA，~8s/张）；角色一致性=LoRA（实测 IPAdapter 只迁风格不迁身份，方案撤销）。

### 5.4 角色一致性分工

本地 i2v 只负责"让画面动起来"，**一致性留在图像侧**（云参考图 / 本地 LoRA / 角色卡），与现有 Phase 4 切片 1 契约一致，视频侧不背一致性包袱。

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 长渲染热降频 | 单段 5s、夜间批量、性能模式+散热垫；监控 nvidia-smi 温度/功耗 |
| WDDM 显存挤占致 OOM | FP8 + offload 兜底；渲染会话关 Chrome 硬件加速 |
| 旧整合包 torch 不支持 sm_120 | 手动验证 `torch.cuda.get_device_capability()` == (12, 0)；cu128 起步 |
| 免费云生图 429 限速 | 已有重试/排队经验；或切 local 图像源 |
| 模型下载体积大 | 优先 5B FP8 单模型起步（≈10G），14B/Flux 按需后补 |

## 7. Phase 5 路线图（唯一入口，2026-11-16 合并整理）

> **文档地图**：[实测手册](comfyui/漫剧流水线-本机实测手册.md)=实测唯一事实源；本文档=设计源+路线图；`superpowers/plans/`=各切片实施档案（计划+执行记录，历史快照不再更新）；[整集试产验收记录](phase5-整集试产验收记录.md)=功能验收与排产基准。**路线规划只在本节维护**，其他文档不再重复。

### 已完成 ✅

| 里程碑 | 内容 | 档案 |
|---|---|---|
| Phase 0 | ComfyUI Desktop v0.45.3 + torch 2.12.1+cu130 就绪；能力矩阵见 §4 | 实测手册 |
| 切片 1 | ComfyUiVideoGenerator：Wan2.2 i2v 协议/模板占位符/同源取字节；318 绿 | [实施档案](superpowers/plans/2026-11-15-phase5-slice1-comfyui-i2v.md) |
| 切片 2 | 实测校准：VAEDecodeTiled 强制/核心 CreateVideo+SaveVideo/720→704/type=output 过滤/NO_PROXY；真实冒烟 315.4s；320 绿 | [实施档案](superpowers/plans/2026-11-15-phase5-slice2-local-calibration.md) |
| 切片 3 | ComfyUiImageGenerator：SD1.5+LoRA 分镜（图 A），ControlNet canny gated（图 D）；角色卡 loraName/triggerWord 全链；**IPAdapter 身份方案撤销**；346 绿 | [实施档案](superpowers/plans/2026-11-15-phase5-slice3-lora-image-side.md) |
| #0 校准 | 模板对齐手册：LoRA strength 1.0/1.0、canny end_percent 0.8 + vae=[1,2] | 切片 3 档案·校准记录 |
| #1 整集试产 | 5 镜全链功能 PASS（分镜→i2v→TTS/BGM/字幕→720p）；**干净复测替换排产基线：29f≈124s / 49f≈226s / 边际≈5.1s/帧 / 20 镜×2s 集≈75min GPU**（17f 冷 76.4s；timeout 1800 余量≥8×） | [验收记录](phase5-整集试产验收记录.md) |

### 下一步（双轨排序，2026-11-16 用户确认两方向并列）

> **两轨关系**：Track A（AI 短剧集 /video-studio）已可用，剩余为增强项；Track B（评论杂谈 /commentary-studio）是从 0 到 1 的产品线，**优先**。**资源零冲突**：Track B 实施几乎不占 GPU（素材库/页面/FFmpeg/TTS 均 CPU 编排），Track A 增强多为 GPU 工作，两轨可并行；文件零重叠（仅 application.yml 同文件不同段）。**协同点**：Track B 的原创主体画面/主视觉由 Track A 流水线生产（版权决策：原创为主菜、引用为佐料≤16%）。

**Track B：评论杂谈产品线（优先；[实施计划](superpowers/plans/2026-11-15-phase5-commentary-automation.md) Task 0-6 已就绪）**

| # | 任务 | 范围与依据 | 规模 |
|---|---|---|---|
| B0 ✅ | 脚本级发片工具（Task 0，2026-11-16） | tools/auto-commentary-concat.js 落地：TEST_OK+lavfi 冒烟 24s+水印目视；修复 fontfile 静默不渲染/两级转义；用法见 kurumi md §4.1；真实发片仍需主体 mp4（Track A 流水线生产） | 小 |
| B1 | 素材库+独立页+绑定（Task 1-3） | MediaAsset 上传/裁剪/探测；/commentary-studio 新页+format 分离（导演列表默认排除评论）；镜头素材绑定 | 中 |
| B2 | 自动对齐+版权护栏（Task 4-5） | TTS 预探测+短片段 loop；逐镜出处水印+引用占比≤0.2 导出拒绝+插入镜原声丢弃 | 中 |
| B3 | 批量入库+异步导出（Task 6） | 文件夹 scan 约定命名入库；异步导出 jobs 轮询；四双语文档同步 | 中 |

**Track A：AI 短剧集增强（按需穿插，GPU 验证可在 Track B 开发间隙跑）**

| # | 任务 | 范围与依据 | 规模 |
|---|---|---|---|
| A1 ✅ | 排产加固（2026-11-16） | metadata 免解码核帧（findOutputNode+videoSpecMismatch，缺失宽容/不符仅告警）；timeout 1800/workers=1 按干净基线复核保持（余量≥8×）；349 绿 | 小切片 |
| A2 | 本地 inpainting | dreamshaper_8Inpainting + VAEEncodeForInpaint 实测 10.94s@1280×704（手册 §6.1）；ImageGenerator.editWithMask/supportsEdit 接口现成 | 中切片 |
| A3 | 多画风 | 吉卜力/WLOP/guofeng3 配方已实测（手册 §6.1）；身份×风格 LoRA 叠加需 spike 先行 | spike+中切片 |
| A4 | RIFE 48fps | 核心 FrameInterpolate ×2（+3s/0.92GiB；输出 (N−1)×2+1、fps×2）；平台 48fps 接受度待验证 | 小切片 |
| A5 | 原创角色 LoRA | 用户侧 OneTrainer（合成图集 24 张 49s + 训练 7.5min，手册 §6）；训练与视频禁同卡并行；产品侧零改动（角色卡挂 loraName 即用） | 用户侧并行 |
| A6 | Wan2.2 V2V 本地转绘 | 手册未实测，先 spike 验证再决定是否排产 | spike |
| A7 | 快动作 aliasing | 半帧+RIFE 的动作量/混叠验证（length=故事时长，禁半帧偷时长） | spike |

**执行建议**：B0+A1 已完成（2026-11-16）；下一步 **B1（素材库+独立页+绑定）为主线**，A2/A3 按需穿插。Track B 各 Task 按计划 TDD 执行，每切片末全量绿+暂存不提交。

## 8. 成本核算

| 环节 | 方案 | 成本 |
|---|---|---|
| 剧本 | DeepSeek/GLM-Flash 免费档 | ¥0 |
| 分镜图 | 智谱免费云（或本地 SDXL） | ¥0 |
| i2v | 本地 Wan2.2 5B | ¥0（电费） |
| 配音/合成 | edge-tts + FFmpeg | ¥0 |

对比云端 wan2.7-i2v 按段计费：10 分镜/集 × 多集迭代，本地路线边际成本趋近于零，且无速率限制、素材不外传。