# Phase 5 切片 3：本地图像侧（SD1.5+LoRA 分镜）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `ComfyUiImageGenerator`（image 侧 type=comfyui），SD1.5+角色 LoRA 出分镜（实测 ~8s/张），角色卡增 loraName/triggerWord，可选 ControlNet canny 锁机位；与切片 1/2 的本地 i2v 组成全链路零云依赖漫剧流水线。身份一致性=LoRA（实测 IPAdapter 不迁身份，方案撤销）。

**Architecture:** 提取共享 `ComfyUiClient`（upload/submit/poll/download/probe，video 生成器委托回归不变）；`ImageGenerator` 增能力旗标 `supportsLora/generateWithLora`（default 方法 idiom 同 supportsReferences）；`LoraResolver` 取首个带 LoRA 角色；`ShotPromptAssembler`（唯一 prompt 装配点）注入触发词；`generateWithRetry` 单 dispatch 点路由 LoRA；三模板（plain/lora/lora+canny）占位符替换，节点 schema 经 /object_info 核验。

**Tech Stack:** Java 17 / Spring Boot 3.4 / ComfyUI v0.45.3 / SD1.5 anything-v5 + saki_chan_lora / control_v11p_sd15_canny_fp16。

**Spec:** 手册 §5.2-A/D（最小图）、§6（22 步 CFG7 euler/normal、1280×704）；切片 2 计划路线修订节。

## Global Constraints

- 默认零变化：image.generator 默认不变；ComfyUI 未启动 comfyui 图像源 available=false；无 LoRA 角色时行为与存量逐字一致（plain 模板仅 comfyui 源内部使用）。
- 装配点红线：characterToMap 单一静态装配点增键；controller create/update 透传；前端对话框三处（输入/回填/清空）同步。
- prompt 装配唯一入口 ShotPromptAssembler；触发词仅当 triggerWord 非空注入。
- 数值占位符不带引号；PROMPT/NEGATIVE 最后替换；输出过滤 type=="output"。
- 720 高→704（实测）；SD1.5 VAEDecode 无需 tiled（tiled 强制仅 Wan）。
- 暂存不提交；四份双语文档同步；gated 冒烟 -Dcomfyui=true。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `video/generator/ComfyUiClient.java` | 共享协议客户端（包私有） |
| Modify | `video/generator/ComfyUiVideoGenerator.java` | 委托 client（seam 保留） |
| Create | `video/generator/ComfyUiImageGenerator.java` | SD1.5+LoRA/Canny 图像生成 |
| Create | `video/generator/LoraSpec.java` / `LoraResolver.java` | LoRA 规格 record + 解析（CN 配置 gated） |
| Modify | `video/generator/ImageGenerator.java` | +supportsLora/generateWithLora default |
| Modify | `video/ShotPromptAssembler.java` | 触发词注入 |
| Modify | `service/ImageGenerationService.java` / `CandidateGenerationService.java` | LoRA 路由（generateWithRetry 单点+候选路径） |
| Modify | `entity/StoryCharacter.java` / `service/StoryCharacterService.java` / `controller/StoryCharacterController.java` | loraName/triggerWord 全链 |
| Modify | `templates/video-studio.html` | 角色卡对话框两输入 |
| Modify | `config/ImageGeneratorConfig.java` / `application.yml` | Bean + image.comfyui 块 |
| Create | `docs/comfyui/sd15_storyboard.template.json` / `sd15_lora_storyboard.template.json` / `sd15_lora_canny.template.json` | 三模板 |
| Create/Modify | 测试×6 + 冒烟 | 见各 Task |

---

### Task 1：ComfyUiClient 提取（回归不变）

**Interfaces:** `ComfyUiClient(HttpClient, ObjectMapper, String baseUrl, int timeoutSeconds, long pollIntervalMs)`；`String uploadImage(byte[], String fileName)`；`String submit(ObjectNode)`；`JsonNode pollEntry(String promptId)`（成功返回 entry，error/超时抛）；`byte[] download(String filename, String subfolder, String type)`；`byte[] fetchBytes(String url)`；`boolean probe()`；`static String[] findOutput(JsonNode entry, String... suffixes)`（type==output，gifs/videos/images 键）。

- [ ] Step 1：video 生成器现有 14 测试即回归基线（先跑绿确认）。
- [ ] Step 2：提取 client，video 生成器私有协议方法改委托；`parseVideoOutput` 委托 `findOutput(entry,".mp4")`→webm/mkv；fetchFirstFrame 目标解析留 video 侧、取字节走 client.fetchBytes。
- [ ] Step 3：跑绿 14/14 + 全量。

### Task 2：角色卡 loraName/triggerWord 全链（TDD）

- [ ] Step 1：失败测试 `CharacterLoraFieldsTest`：create/update 透传+characterToMap 含两键+空值兼容（mock repo，镜像服务 ctor 四参）。
- [ ] Step 2：跑红。
- [ ] Step 3：entity +两字段（loraName length 200 / triggerWord length 100）+getter/setter；service create/update 增参（null 安全）；characterToMap +两键；controller request.get 透传。
- [ ] Step 4：前端对话框：charFeatures 行后增 `charLora`（placeholder LoRA 文件名，如 saki_chan_lora.safetensors）/`charTrigger`（触发词）两输入；saveCharacter body 增 loraName/triggerWord；编辑回填+新建清空同步。
- [ ] Step 5：跑绿。

### Task 3：能力旗标 + LoRA 路由（TDD）

**Interfaces:** `ImageGenerator.supportsLora()` default false；`generateWithLora(prompt, neg, w, h, loraName, controlNetRefUrl)` default 抛 UnsupportedOperationException；`LoraSpec(String loraName, String triggerWord, String controlNetRefUrl)`；`LoraResolver.resolve(List<StoryCharacter>)`（首个非空 loraName；cnRef=controlnet-enabled?referenceImageUrl:null；无→null）。

- [ ] Step 1：失败测试：`LoraResolverTest`（首 LoRA/无 LoRA null/CN gated）；`ShotPromptAssemblerTest` 增触发词用例（有 triggerWord 注入、无则逐字不变）；`ImageLoraRoutingTest`（supportsLora=true→generateWithLora 被调且参数正确；false→存量 generate；spec=null→存量）。
- [ ] Step 2：跑红。
- [ ] Step 3：实现接口 default/LoraSpec/LoraResolver/assembler 触发词；ImageGenerationService generateWithQualityCheck/generateWithRetry 增 LoraSpec 参（两调用点传 loraResolver.resolve(characters)）；CandidateGenerationService.generateImageCandidates 同路由。
- [ ] Step 4：跑绿 + 存量 ImageGenerationService/Candidate 回归。

### Task 4：ComfyUiImageGenerator + 模板 + 注册（TDD）

- [ ] Step 1：失败测试 `ComfyUiImageGeneratorTest`：buildWorkflow seam 三模板选择（cnRef→canny 含 ControlNetApplyAdvanced；lora→LoraLoader {{LORA_NAME}} 替换；plain 无 LoraLoader）；720→704；happy path（mock http：submit/history/images output png/view→storage）；unavailable 探针；`ComfyUiImageRegistrationTest`（image 工厂含 comfyui、默认 active 不变）。
- [ ] Step 2：跑红。
- [ ] Step 3：实现生成器（client 复用；输出 findOutput ".png"→".jpg"/".webp"；转存 comfyui_img_{ts}_{uuid}.png；supportsLora=true；supportsReferences=false）；三模板（schema 核验：LoraLoader req=model,clip,lora_name,strength_model,strength_clip；ControlNetApplyAdvanced req=positive,negative,control_net,image,strength,start_percent,end_percent，vae opt；Canny low/high 0.4/0.8）；ImageGeneratorConfig @Bean（base-url 复用 video.comfyui.base-url；image.comfyui.workflow-plain/lora/canny、timeout-seconds 120、poll-interval-ms 1000、controlnet-enabled false）；yml 块。
- [ ] Step 4：跑绿 + 全量。

### Task 5：gated 图像冒烟 + 文档 + 暂存

- [ ] Step 1：`ComfyUiImageRealSmokeTest`（-Dcomfyui=true）：generateWithLora("saki_chan, 1girl, school uniform, hallway, best quality, anime coloring", neg, 1280, 720, "saki_chan_lora.safetensors", null) → assert fileId（实测 ~8-10s）。
- [ ] Step 2：全量回归绿×2 纪律。
- [ ] Step 3：README×2/API_TEST×2 切片 3 节（角色卡 LoRA 字段/图像源 comfyui/ControlNet 开关/排产 ~8s 张）；feasibility §7 切片 3 ✅。
- [ ] Step 4：执行记录；暂存。

## 执行记录

（2026-11-15 实施）

- **T1**：ComfyUiClient 提取（upload/submit/pollEntry/download/fetchBytes/probe/findOutput/jsonEscape），video 生成器委托；回归 14/14。
- **T2**：红=编译缺字段/方法 → 绿 9/9。entity loraName(200)/triggerWord(100)；service create 7 参/update 8 参（null 不覆盖、空串清空）；characterToMap +两键；controller 透传；前端对话框输入/回填/清空三处。存量 StoryCharacterServiceTest 两调用点签名同步。
- **T3**：红=编译缺 LoraResolver → 绿 40/40。ImageGenerator +supportsLora/generateWithLora default；LoraSpec record；LoraResolver（controlnet-enabled gated）；assembler 触发词仅非空注入；generateWithRetry 单 dispatch + generateWithQualityCheck 透传 + 候选抽卡同路由；存量 6 处测试构造点同步。
- **T4**：红=编译缺生成器 → 26/26。三模板落盘（schema 经 /object_info 在线核验：anything-v5-PrtRE.safetensors / saki_chan_lora.safetensors / control_v11p_sd15_canny_fp16.safetensors；Canny low/high_threshold；ControlNetApplyAdvanced 七参）；22 步 CFG7 euler/normal；720→704；ImageGeneratorConfig @Bean NO_PROXY；yml image.comfyui 块。**注册测试环境依赖修正**：本机 env IMAGE_GENERATOR=zhipu 经 relaxed binding 覆盖 yml → 断言改 assertNotEquals("comfyui")（切片 1 Minor#2 同款教训）。
- **T5 真实冒烟✅**：/queue 空 → -Dcomfyui=true → 2/2 绿 23.95s（含启动）；ffprobe 产物 **1280×704**；LoRA=saki_chan_lora 闭环。**全量 346 绿×2**（0 失败 0 错误 7 跳过）。
- **文档**：README×2/API_TEST×2 切片 3 节；feasibility §7 切片 3 ✅。
- **暂存不提交**。

## 校准小修记录（#0，2026-11-15，最新手册复核后）

- 红：repoTemplatesCalibrated 增断言（strength 1.0/1.0、end_percent 0.8、vae=[1,2]）→ expected 1.0 but was 0.8。
- 修：sd15_lora_storyboard / sd15_lora_canny 两模板 LoraLoader strength 0.8→**1.0/1.0**（手册 §5.2-A 身份配方）；canny ControlNetApplyAdvanced end_percent 1.0→**0.8** + 补 **vae=[1,2]** 连线（手册 §5.2-D）。
- 绿 26/26；暂存不提交。随后进入 #1 整集试产验收。