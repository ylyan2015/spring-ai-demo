# 素材库与画面感增强设计（Asset Library & Visual Richness）

> 日期：2026-11-17 · 分支：feature/vedio-create · 状态：六节设计已逐节经用户确认
> 驱动诉求：成片画面卡通但朴素。①脚本生成更有画面感（人物+背景），用户可自备素材放文件夹、数据库建索引、生成时判断使用；②动漫/影视人物网图自动搜索、挑选入库作素材。

## 0. 已确认决策口径（AskUserQuestion 逐项拍板）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 网图用法 | **仅作参考图引导生成**，成片画面全部 AI 原创（与既有版权决策「引用≤16%+原创主角」一致，网图永不直插成片） |
| 2 | 素材范围 | 首期 **人物（CHARACTER）+ 场景背景（SCENE）** 两类 |
| 3 | 匹配流程 | **自动推荐 + 人工确认**，确认闸门永远在人手里 |
| 4 | 网搜触发 | **两步走**：首期素材库页手动搜；二期脚本生成时自动搜（复用同一搜索服务） |
| 5 | 消费链路 | **双链路自动路由**：本地 ComfyUI IP-Adapter / 云 wan2.7 refs，按激活 provider 路由 |
| 6 | 架构方案 | **方案 A：扩展 MediaAsset 统一素材库**（否决方案 B 独立 ReferenceAsset 实体——上传/存储/扫描/页面重复建设约 2 倍工作量，收益不抵成本） |

## 0.1 现状地基（已核实代码事实）

- `MediaAsset`（Track B Task1）：userId/name/kind(VIDEO|IMAGE)/fileId/url/durationSeconds/tags/sourceNote；upload/ingestPath/scanImportDir/list/delete 全套；`epNN_标签_名称` 扫描约定
- `StoryCharacter`：featureTable + referenceImageUrl/FileId + loraName/triggerWord（脚本私有，跨脚本不复用——本设计补公共库）
- `ReferenceImageResolver`：回退链 http 绝对 URL → 非 local accessUrl → local base64 data URI → 跳过 warn；SSRF 红线=仅接受存储相对路径
- `ImageGenerator` 接口：`supportsReferences()`/`generateWithReferences()` default 方法已存在；`ImageGenerationService`（L555 附近）与 `CandidateGenerationService`（L114 附近）**两处**已接 refs 路由；Wan27ImageGenerator 已覆写（local 存储 base64 可用，Phase 4 实测）
- `ComfyUiImageGenerator`：模板三选一 canny>lora>plain；占位符 {{PROMPT}}/{{NEGATIVE}}/{{WIDTH}}/{{HEIGHT}}/{{SEED}}/{{LORA_NAME}}/{{CN_IMAGE}}；参考图字节 fetchRefBytes（data:/http/同源相对路径）→ client.uploadImage → 文件名进模板（canny idiom）；720→704
- `VideoScriptService.generateScript(idea, templateGuidance)`：BeanOutputConverter<Storyboard>，system prompt 含 characters/shots 要素要求
- 本机 ComfyUI：IPAdapter 节点曾用过（手册记录「IPAdapter 只迁风格」）；SD1.5 身份=LoRA 路线

## 1. 数据模型（已确认）

**零新实体，两处加列，ddl-auto=update 自动建列（endCard idiom）。**

### 1.1 MediaAsset 加 `category`（VARCHAR 20）

- 取值：`CHARACTER`（人物参考图）/ `SCENE`（场景背景参考图）/ `INSERT`（Track B 评论插入素材，现状）
- **存量兼容**：现有行 category=null，代码层一律按 INSERT 语义读取（`category == null → INSERT`），不做数据迁移，Track B 行为逐字不变
- kind/tags/sourceNote 复用：参考素材强制 kind=IMAGE（服务层护栏）；sourceNote 记出处（booru 原帖 URL / "用户上传"），版权可追溯
- 上传/扫描接口加可选 category 参数，缺省 INSERT（Track B 前端零改动）；列表接口加 category 过滤

### 1.2 StoryShot 加 `referenceAssetIds`（VARCHAR 500）

- 逗号分隔素材 id，镜像 characterIds idiom（解析复用同款静态方法、绑定走 updateShot 同款归属过滤）
- null=未绑定；**仅人工确认后写入**（推荐结果不落库）
- 绑定校验三条：素材属当前用户 + kind=IMAGE + category ∈ {CHARACTER, SCENE}（INSERT 禁入生成链路）
- 绑定/更换素材**不失效旧图**（参考图只影响下次生成；与 imageUrl 变更失效视频语义区分）

### 1.3 参考素材扫描目录（用户"放文件夹"工作流）

- 新配置 `video.assets.reference-import-dir`（独立于 Track B import-dir）
- 文件名约定：`char_林晚_正面全身.png` → CHARACTER/name=林晚/tags=正面全身；`scene_雨巷_黄昏.jpg` → SCENE/name=雨巷/tags=黄昏；扩展名支持 png/jpg/jpeg/webp（仅图片，与参考素材 IMAGE 护栏一致）
- 前缀不符跳过 + log（Track B epNN_ 约定同款行为）；页面「扫描入库」触发

## 2. 匹配与确认流（已确认）

**推荐是阅后即焚的计算结果，确认才落库；LLM 只碰文本（素材名+标签），不碰图片字节。**

### 2.1 AssetMatchService（service 层，登录校验 idiom）

输入 scriptId，一次批量匹配整部脚本：
1. 加载全部镜头（sceneDescription+characterNames）+ 用户参考素材清单（CHARACTER/SCENE 且 IMAGE，仅 id/name/category/tags）
2. **空库短路**：清单空 → 返回空推荐，不调 LLM
3. LLM 批量判断：复用 ModelService 单次调用，输出 JSON 每镜 ≤3 候选（assetId+一句理由）；提示「人物素材优先匹配出场角色，场景素材匹配环境描述」
4. 降级：LLM 失败/非法 JSON → 关键词重叠打分（镜头描述分词 × name/tags）取 top-3，标 source=FALLBACK，前端明示
5. 上限：素材目录 >100 条按 createdAt 倒序截断 100 + warn

### 2.2 API 与前端

- `POST /api/video/scripts/{sid}/asset-matches`：触发匹配返回候选，**不写库**
- `PUT /api/video/scripts/{sid}/shots/{shotId}`：现有 updateShot 扩展 referenceAssetIds，确认即走这里
- video-studio 分镜编辑区：每镜「匹配素材」+ 脚本级「一键匹配」→ 候选缩略图 chip（名字+理由+类别角标）→ 单个采用/移除或整镜采用全部 → updateShot 落库；已绑定常驻可换；手动挑选同入口

### 2.3 不做（YAGNI）

推荐持久化/缓存（每次重算）；图像相似度匹配（CLIP 等，文本标签够用）；生成时自动绑定。

## 3. 双链路消费（已确认）

### 3.1 路由总表（两处组装点同步改：ImageGenerationService + CandidateGenerationService）

| 镜头状态 | 路由 |
|---|---|
| LoRA + 素材 refs | 组合链路 generateWithLoraAndReferences（新 default 方法） |
| 仅素材 refs | generateWithReferences（现有） |
| 仅 LoRA | generateWithLora（现状） |
| 无 | generate（现状） |
| provider 不支持 refs | refs 静默忽略走纯 prompt（现状，log 一次） |

接口扩展走 default 方法 idiom（uploadFinal 同款）：`ImageGenerator.generateWithLoraAndReferences(...)` 默认降级 generateWithReferences，其余 6 个 generator 零改动，仅 ComfyUI 覆写。

### 3.2 本地 ComfyUI IP-Adapter

**代码侧**（镜像 canny idiom）：
- 新模板 `docs/comfyui/sd15_ipadapter.template.json` + 组合版 `sd15_ipadapter_lora.template.json`；配置 `image.comfyui.workflow-ipadapter` / `workflow-ipadapter-lora`
- ComfyUiImageGenerator 覆写 supportsReferences()=模板文件存在（**模板未就位自动降级，不阻塞启动**）；覆写 generateWithReferences/generateWithLoraAndReferences
- 参考图字节走现成 fetchRefBytes → client.uploadImage → 文件名替换进模板
- 占位符：{{REF_IMAGE}}/{{REF_IMAGE2}}（字符串带引号）、{{IP_WEIGHT}}（**数值不带引号**）、其余沿用现有
- **最多 2 张参考图**（模板 ImageBatch 合并；仅 1 张时重复上传补第二槽位；CHARACTER 优先 SCENE 排序）；超出跳过 + warn
- 权重默认 0.7（行业甜点区 0.65-0.85），yml `image.comfyui.ipadapter-weight` 可配

**用户自备（ComfyUI Desktop 环境）**：

| 项 | 文件 | 位置 |
|---|---|---|
| 自定义节点 | ComfyUI_IPAdapter_plus（cubiq；机器大概率已装，Manager 确认） | custom_nodes/ |
| IP-Adapter 模型 | ip-adapter-plus_sd15.safetensors（~98MB，人物+场景通用） | ComfyUI/models/ipadapter/ |
| CLIP Vision | CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors（~2.5GB，SD1.5 必须 ViT-H） | ComfyUI/models/clip_vision/ |

不装 FaceID/insightface（安装坑多，plus 对动漫够用；身份精锁留 LoRA）。

**模板校准**（Phase 0 wan22 idiom）：用户 ComfyUI 搭 `LoadImage×2 → ImageBatch → IPAdapter(weight 0.7) → KSampler` 跑通 → 导出 API JSON → 字面量换占位符存上述路径。spec 附录 A 给节点连接与占位符对照。

### 3.3 云端 wan2.7

零新增代码：generateWithReferences 已存在且 Phase 4 实测（local 存储 base64 data URI，参考图支持 base64，无需 oss/minio）。素材 refs 经 resolver 扩展自动流入。

### 3.4 统一降级链

1. 素材文件缺失/下载失败 → resolver 跳过该张 + warn（角色参考图同款）
2. refs 全失效 → 空列表 → 落回纯 prompt
3. IP-Adapter 模板未就位 → supportsReferences=false → 路由走 LoRA/纯 prompt
4. 生成失败 → 现有重试/抽卡兜底，不新增降级

### 3.5 ReferenceImageResolver 扩展

新增 resolveAssets(StoryShot)：按 referenceAssetIds 加载 MediaAsset（归属+kind+category 校验）→ 与角色参考图完全相同的回退链。最终 refs = 角色定妆图 + 素材图合并（角色在前）。SSRF 红线不变：仅存储相对路径，素材 URL 来自自家数据库非 LLM。

## 4. 网搜入库（已确认）

### 4.1 Booru API（BooruSearchService + BooruProvider 双适配器，List 注入 idiom）

| 源 | 端点 | 分级 | 备注 |
|---|---|---|---|
| safebooru.org（默认主源） | GET /index.php?page=dapi&s=post&q=index&json=1&tags=&limit=&pid= | 全站 safe + 响应 rating 复核 | 无 key；图 URL=https://safebooru.org/images/{directory}/{image} |
| safebooru.donmai.us（备源） | GET /posts.json?tags=&limit=&page= | 强制追加 rating:g | 国内可直连；免费档限 2 tag 组合 |

- 角色名即 tag（hatsune_miku），空格转下划线，多 tag `+` 连接（AND）
- 中文输入：先经一次 LLM 转 booru 英文 tag（ModelService），失败/超时降级原文下划线化直查；搜不到返回空+提示换英文 tag，不报错
- 每次 ≤20 条、翻页 ≤5 页；归一化 BooruPost record：provider/postId/previewUrl/fileUrl/tags/rating/width/height/sourcePageUrl

### 4.2 安全护栏（三道闸）

1. **host 白名单**：`video.assets.booru.allowed-hosts`（默认恰为两域：两源的图片 URL 均在各自同域，无额外 CDN；配置可扩展）；搜索/预览代理/下载全部校验 host ∈ 白名单且 https；HttpClient **禁跟随重定向**
2. **入库不信任客户端 URL**：ingest 只收 provider+postId，服务端重查该帖元数据拿 fileUrl
3. **下载护栏**：连接 10s/读取 30s 超时、≤20MB、Content-Type 必须 image/*；字节走 storageService.upload 转存自家存储，外链永不进生成链路

版权可追溯：sourceNote 自动写「原帖 URL+作者/source+tags+rating」；category 仅 CHARACTER/SCENE，第 1 节护栏禁直插成片。

### 4.3 接口与前端

- `GET /api/video/assets/booru/search?q=&provider=&page=`：搜索（登录校验）
- `GET /api/video/assets/booru/preview?provider=&postId=`：服务端代理缩略图（sample 小图），解决浏览器直连网络问题，过白名单闸
- `POST /api/video/assets/booru/ingest`：{provider, postId, category, name, tags?} → 重查 → 下载 → 转存 → MediaAsset
- 前端：video-studio 新增「素材库」面板（Track B INSERT 管理留 commentary-studio 不动）；三入口=上传（带 category）/扫描参考目录/网搜；网搜对话框=输入 → 候选网格（缩略图+tags+尺寸+safe 徽章）→ 勾选 → 批量设 category/name（默认搜索词）→ 入库 → 刷新；入库素材与手动上传完全同权

### 4.4 不做

以图搜图（iqdb/saucenao）；Pixiv（token+版权敏感）；自动批量爬取入库（每张必须人工勾选）；二期自动搜仅加触发点，护栏不变。

## 5. 脚本增强（已确认）

**只动 prompt 与上下文，不动 Storyboard schema（BeanOutputConverter 契约零变化，下游组装零回归）。**

### 5.1 VideoScriptService system prompt 画面感升级

- sceneDescription 四要素强制：主体（谁+外观服装状态）+ 动作 + 具体背景环境 + 光影色调；情绪经表情/肢体，不写抽象形容词
- imagePrompt 固化公式：subject + action + environment details + lighting + color palette + composition + art style；环境细节 ≥2 个具体物件
- 禁令：vague 词（beautiful scene 等）；无背景纯色镜（endCard 除外）；同场戏服装状态一致
- 附 1 个高质量范例镜（few-shot ~150 token）
- 固化 BLG 教训：旁白字数 ≤ round(2.5 × durationSeconds)

### 5.2 素材清单注入

- StoryScriptService.generateAndSaveScript 加载用户 CHARACTER/SCENE 素材（name/category/tags）→ generateScript 新重载传入 → system prompt「可用素材库」节
- 格式：`[人物] 林晚：黑长直,红大衣,琥珀瞳` / `[场景] 雨巷：黄昏,霓虹,青石板`；指令=优先库内角色当主角、库内场景当背景、角色名与库一致、不够可原创
- **不带 id/URL**：LLM 只管写什么，绑定归 AssetMatchService；角色名一致 → 匹配命中率天然高（两节闭环）
- token 三道闸：≤30 条（createdAt 倒序）；每条 tags 截断 40 字符、整行 ≤60 字符（最坏 ~1000 token）；空库零注入
- 拼接顺序：基础 prompt + 素材清单 + templateGuidance（模块 H 共存）
- 查库异常降级无清单模式 + warn，不阻断生成

### 5.3 衔接与不做

生成完成 → 前端「一键匹配」高亮提示（库非空时）；不自动绑定/不自动生图。不做：DTO 结构改动、RAG/embedding 检索、本期生成时自动网搜（二期）。

## 6. 测试策略（已确认）

**TDD 先红后绿；HTTP 注入 mock（OpenAiCompatibleImageGeneratorTest idiom）；真实链路全 gated。**

### 6.1 单测覆盖点

| 模块 | 关键用例 |
|---|---|
| MediaAsset category | null→INSERT 语义；upload 带 category；参考强制 IMAGE；char_/scene_ 文件名解析（静态直测：前缀不符跳过、name/tags 拆分）；list 过滤 |
| StoryShot 绑定 | updateShot 三校验（他人拒/INSERT 拒/VIDEO 拒）；id 解析镜像 characterIds；绑定不失效旧图（对照 imageUrl 失效用例） |
| AssetMatchService | 空库短路（verify LLM 零调用）；JSON 解析 top-3；非法 JSON→FALLBACK；100 截断；推荐不落库（verify 零 save） |
| Resolver 扩展 | 四段回退链逐段；合并顺序角色在前；非存储路径拒（SSRF） |
| 路由矩阵 | 两处组装点各测四象限；default 降级逐字不变 |
| ComfyUI 装配 | ipadapter 占位符（IP_WEIGHT 数值无引号回归用例）；模板缺失→supportsReferences=false；单 ref 补槽；超 2 跳过 |
| Booru | tag 归一化；LLM 转 tag 降级；rating:g 强制+复核；白名单外拒（搜索/预览/下载三入口）；禁重定向；ingest 重查（伪造 URL 用例）；20MB/非 image 拒；sourceNote 完整 |
| 脚本增强 | 30 上限/行截断/空库零注入（字符串断言）；查库异常降级；新旧签名兼容 |

### 6.2 Track B 回归三道防线

1. 既有测试逐字不改必须绿（MediaAssetService/bindAsset/insertRatio/attributionFilter），红即停
2. 显式兼容用例：category=null 存量素材在 list/bindAsset/导出护栏行为与改动前一致（新增对照测试）
3. 每切片末 mvn clean package 全绿才进下一切片；收尾复跑 tools/e2e-commentary.js 全链路

### 6.3 gated 冒烟三层

1. **IP-Adapter 单镜**（-Dipadapter=true，Wan27RealSmokeTest idiom）：assumeTrue 三连（属性+模板存在+probe）；真实素材 generateWithReferences 一张 → 断言落存储+1280×704；**同 prompt 同 seed 无 refs 对照组**并排目视（生效唯一可信证据），记录验收文档
2. **Booru 真实网络**（-Dbooru=true）：search hatsune_miku limit 3 → 全 safe+白名单；ingest 1 张 → 在盘+sourceNote 含原帖 URL
3. **全链路 e2e**（tools/e2e-asset-library.js，现有认证 idiom）：注册 → 上传/扫描 → 建脚本（验证 LLM 用库内角色名）→ 一键匹配 → 确认绑定 → 生图（ComfyUI 存活走 IP-Adapter，否则 gate 跳过）→ export/hd → 断言落 uploads/final/；无 GPU 降级段用既有 PNG 绑镜 idiom 验数据链路

### 6.4 验收口径

常规全绿 + Track B e2e 复跑 PASS 为硬门槛；gated 按机器状态执行，结果+目视对照图记 docs/phase5-整集试产验收记录.md 新节；每切片末暂存不提交。

## 7. 配置清单（新增汇总）

```yaml
video:
  assets:
    reference-import-dir: ""        # 参考素材扫描目录（char_/scene_ 约定；空=禁用）
    booru:
      allowed-hosts: safebooru.org,safebooru.donmai.us   # 两源图片均同域，无额外 CDN；可扩展
      default-provider: safebooru   # safebooru / danbooru-safe
image:
  comfyui:
    workflow-ipadapter: ${COMFYUI_IMG_IPADAPTER:docs/comfyui/sd15_ipadapter.template.json}
    workflow-ipadapter-lora: ${COMFYUI_IMG_IPADAPTER_LORA:docs/comfyui/sd15_ipadapter_lora.template.json}
    ipadapter-weight: 0.7           # 行业甜点区 0.65-0.85
```

## 8. 实施切片建议（供 writing-plans 细化）

1. **S1 数据模型+扫描**：两列+category 语义+char_/scene_ 扫描+绑定校验（Track B 兼容用例先行）
2. **S2 匹配与确认**：AssetMatchService+asset-matches 端点+updateShot 扩展+前端 chip 流
3. **S3 双链路消费**：Resolver 扩展+路由矩阵+default 方法+ComfyUI 模板装配（用户并行下载模型/校准模板）
4. **S4 网搜入库**：BooruProvider 双适配+三闸护栏+三端点+素材库面板
5. **S5 脚本增强**：prompt 升级+清单注入+e2e 驱动+全链路验收

依赖：S2/S3 依赖 S1；S4 依赖 S1；S5 依赖 S1（清单）+S2（匹配衔接）；S3 真实冒烟依赖用户模型就位（不阻塞代码切片）。

## 附录 A：IP-Adapter 模板节点连接与占位符对照

节点图（SD1.5 checkpoint 沿用现有模板的 CheckpointLoaderSimple）：

```
CheckpointLoaderSimple ──MODEL──> IPAdapter ──MODEL──> KSampler ──> VAEDecode ──> SaveImage
LoadImage({{REF_IMAGE}})  ──┐
                            ├──> ImageBatch ──IMAGE──> IPAdapter
LoadImage({{REF_IMAGE2}}) ──┘
IPAdapterUnifiedLoader(或 IPAdapterModelLoader+CLIPVisionLoader) ──IPADAPTER──> IPAdapter
CLIPTextEncode({{PROMPT}})  ──> KSampler.positive
CLIPTextEncode({{NEGATIVE}}) ──> KSampler.negative
EmptyLatentImage({{WIDTH}},{{HEIGHT}}) ──> KSampler.latent_image
```

| 占位符 | 类型 | 替换值 |
|---|---|---|
| {{REF_IMAGE}} / {{REF_IMAGE2}} | 字符串（带引号） | client.uploadImage 返回的文件名 |
| {{IP_WEIGHT}} | **数值（不带引号）** | yml ipadapter-weight（默认 0.7） |
| {{PROMPT}} / {{NEGATIVE}} | 字符串（jsonEscape） | 装配后 prompt |
| {{WIDTH}} / {{HEIGHT}} / {{SEED}} | 数值（不带引号） | 720→704 规则沿用 |
| {{LORA_NAME}} | 字符串（仅组合版模板） | LoraLoader 节点，与 IPAdapter 串联 |

模型文件：ipadapter/ip-adapter-plus_sd15.safetensors + clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors（见 §3.2）。
校准验收：替换后 JSON 必须合法（先文本替换再 readTree）；ComfyUI 实跑一张成功后导出覆盖模板。