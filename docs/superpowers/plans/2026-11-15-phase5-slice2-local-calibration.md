# Phase 5 切片 2：本机实测校准与端到端验收 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以《漫剧流水线-本机实测手册》（2026-11-15 实测）为唯一事实源，校准切片 1 的参考模板与协议细节，完成 Phase 1 遗留的真实环境 e2e 验收；并据实测修订后续路线（LoRA 替代 IPAdapter 身份方案）。

**Architecture:** 模板校准为实测图 B（fp8 KJ UNet + ModelSamplingSD3 shift8 + CFG5 + **VAEDecodeTiled 强制** + 核心 CreateVideo/SaveVideo，**无 VHS**）；生成器协议补 `type=="output"` 过滤与 704 高度；comfyui Bean 强制 NO_PROXY（本机有系统代理）；排产策略 workers=1 + 短切时长。

**Tech Stack:** Java 17 / Spring Boot 3.4 / ComfyUI Desktop v0.45.3（torch 2.12.1+cu130，RTX 5060 8G USB4 eGPU）/ Wan2.2 TI2V-5B fp8。

**Spec:** `docs/comfyui/漫剧流水线-本机实测手册.md`（§1.3 显存预算、§2 能力矩阵、§3.1 tiled A/B、§3.6 length=故事时长、§5.1 API 范式、§5.2-B 最小图、§6 生产参数）；切片 1 计划 `2026-11-15-phase5-slice1-comfyui-i2v.md`。

## 实测事实（本计划一切决策的根，均经 /object_info 在线核验）

| 事实 | 对切片 1 的冲击 | 决策 |
|---|---|---|
| 非 tiled VAEDecode 解码 >40min 假死且不可中断；tiled 512/32/32/8 同链 75s | 参考模板用 VAEDecode=本机必死 | 模板改 VAEDecodeTiled，**测试锁定禁回退** |
| VHS/VideoHelperSuite 未安装；视频读写为核心内置 | 模板 VHS_VideoCombine 节点不存在 | 改 CreateVideo+SaveVideo（schema 已核验） |
| 实测配方 CFG 5.0 + ModelSamplingSD3 shift=8 + CLIP device=default | 模板 cfg1.0 无 shift 节点 | 校准 |
| 实测分辨率 1280×704 | 720P 映射 720 高未经实测 | 720P→1280×704 |
| SaveVideo 产物在 outputs[id]["images"]，LoadVideo 输入也写 images(type=input) | findVideo 未过滤 type | 过滤 type=="output" |
| 121f≈722s、57f≈382s、29f≈155s、17f≈71s；边际≈8s/帧无规模效应 | 估算 2–6min 偏乐观；workers=2 时超时预算含队列等待 | yml 注释 workers=1（comfyui）；时长策略短切 1.5–2.5s |
| length=故事时长；半帧+RIFE=慢动作（动作量 36%） | 无直接冲击 | 路线禁用"半帧偷时长"；RIFE 仅 48fps 顺滑/刻意慢动作 |
| IPAdapter 只迁风格/构图不迁身份；LoRA 7.5min/角色锁定身份 | 原 Phase 2"IPAdapter 一致性"不成立 | 切片 3 改 LoRA 本地图像侧（SD1.5+LoRA 图 A，可选 ControlNet 图 D） |
| 本机有系统代理（flclash）；Java HttpClient 默认走系统代理选择器 | localhost 调用可能被代理劫持 | comfyui Bean `.proxy(NO_PROXY)` |
| Wan2.2 V2V 转绘本机**未实测** | 原"本地 V2V 替代 videoedit"无依据 | 降级为待验证项，不排产 |

## Global Constraints

- 默认零变化、record 签名不变、工厂/前端零改动（沿用切片 1 红线）。
- 模板数值占位符不带引号；PROMPT 最后替换（切片 1 审查修复沿用）。
- 基准纪律：冒烟前确认 `/queue` 空、无下载/pip/OneTrainer 并行（手册 §9）。
- 暂存不提交；四份双语文档同步。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Modify | `docs/comfyui/wan22_i2v_5b.template.json` | 校准为实测图 B |
| Modify | `video/generator/ComfyUiVideoGenerator.java` | type 过滤 + 704 映射 |
| Modify | `config/VideoGeneratorConfig.java` | comfyui Bean NO_PROXY |
| Modify | `resources/application.yml` | workers/timeout 实测注释 |
| Modify | `ComfyUiVideoGeneratorTest.java` | 704 断言更新 + input 过滤 + 模板校准锁定 |
| Modify | `ComfyUiRealSmokeTest.java` | 冒烟改 720P×2s（实测区间） |
| Modify | README×2 / API_TEST×2 / feasibility 文档 | 704/排产策略/实测矩阵回写 |

---

### Task 1：协议与映射校准（TDD）

**Files:** Modify 生成器/测试；**Interfaces:** `resolutionSize("720P")→{1280,704}`；`findVideo` 仅收 `type=="output"`。

- [ ] **Step 1：写失败测试**：① resolutionMapping 改断言 704；② workflowTokens 高度断言 704；③ 新增 `inputTypeEntriesIgnored`（outputs.images 含 type=input 的 in.mp4 与 type=output 的 out.mp4，captor 断言 /view 请求 filename=out.mp4）；④ 新增 `repoTemplateCalibrated`（workflowPath 指仓库模板，buildWorkflow 后断言含 VAEDecodeTiled/SaveVideo/ModelSamplingSD3(shift8)/KSampler(cfg5)，不含 VHS_VideoCombine 与裸 VAEDecode）。
- [ ] **Step 2：跑红**。
- [ ] **Step 3：实现** findVideo 增 `"output".equals(item.path("type").asText())` 过滤；resolutionSize 720P→704。
- [ ] **Step 4：跑绿**。

### Task 2：模板校准 + NO_PROXY + 排产注释

**Files:** 模板重写（节点 schema 经 /object_info 核验：Wan22ImageToVideoLatent 仅 vae/width/height/length/batch_size+opt start_image，**conditioning 直连 KSampler**；SaveVideo req=video/filename_prefix/format，codec opt）；Config Bean `.proxy(HttpClient.Builder.NO_PROXY)`；yml workers 注释"comfyui 建议 1"、timeout 注释"121f≈722s 实测"。

- [ ] **Step 1** 重写模板为实测图 B（unet=Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ；shift8；cfg5；tiled 512/32/32/8；CreateVideo fps={{FPS}}；SaveVideo format=auto）。
- [ ] **Step 2** NO_PROXY + yml 注释。
- [ ] **Step 3** 跑绿 Task 1 的 repoTemplateCalibrated + 全量。

### Task 3：真实冒烟验收 + 文档 + 暂存

- [ ] **Step 1** 冒烟改 720P×2s（49 帧，实测外推≈400s）；确认 /queue 空后 `mvn test -Dtest=ComfyUiRealSmokeTest -Dcomfyui=true`（后台，分钟级）。
- [ ] **Step 2** 记录冒烟耗时/产物；全量回归绿。
- [ ] **Step 3** 文档：README×2/API_TEST×2 的 720P→1280×704 与 workers 建议；feasibility §2/§4 估算表替换为实测矩阵、§7 路线修订（LoRA/48fps/V2V 待验证）。
- [ ] **Step 4** 执行记录追加；暂存。

---

## 路线修订（切片 3+，仅规划不实施，待用户决策）

> **注（2026-11-16 整理）**：本节为历史规划快照——切片 3 已实施完成；最新已完成清单与下一步排序以 [feasibility §7](../../local-generation-feasibility.md) 为唯一入口，本节不再更新。

- **切片 3 本地图像侧**：ComfyUiImageGenerator（图 A：SD1.5 anything-v5 + 角色 LoRA，1280×704，22 步 CFG7 euler/normal，~8s/张）；角色卡增 loraName 字段；可选 ControlNet canny 锁机位（图 D，+10s）。身份一致性=LoRA（实测），**IPAdapter 身份方案撤销**。全链路零云依赖闭环。
- **切片 4（可选）**：ComfyUI 内 RIFE ×2 → 48fps 顺滑交付（+3s/0.9GiB，帧数 (N−1)×2+1、fps×2）；平台 48fps 接受度待验证。
- **待验证项**：Wan2.2 V2V 本地转绘（手册未测，不排产）；快动作半帧+RIFE aliasing；真实图集 LoRA 效果。

## 执行记录

（2026-11-15 实施）

- **环境核验**：/system_stats=v0.45.3/torch 2.12.1+cu130/7.96GiB；/object_info 全节点在线（VAEDecodeTiled/CreateVideo/SaveVideo/FrameInterpolate/GetVideoComponents 等），模型下拉与手册一致；schema 核验 Wan22ImageToVideoLatent 不接 conditioning（直连 KSampler）、SaveVideo codec 为 opt。
- **Task 1**：红=4 失败（704×2/input 过滤/模板锁定）→ 绿 14/14。
- **Task 2**：模板重写为实测图 B（KJ fp8/shift8/CFG5/tiled 512-32-32-8/CreateVideo+SaveVideo/CLIP default）；comfyui Bean NO_PROXY；yml workers/timeout 实测注释。
- **Task 3 真实冒烟✅**：/queue 空→`-Dcomfyui=true`→2/2 绿 315.4s（含启动）；ffprobe 产物 **1280×704/24fps/49f/2.04s** 与配方精确一致；产物 uploads/2026/11/15/ce38c6b4-…_comfyui_video_….mp4。Phase 1 遗留 e2e 验收闭环。
- **全量 320 绿**（0 失败 0 错误 6 跳过）。
- **文档**：feasibility §4 实测矩阵+§7 路线修订；README×2/API_TEST×2 704/workers/排产策略。
- **暂存不提交**。