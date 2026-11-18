# Spring AI Demo API 测试指南

## 测试前提

### 1. 启动应用

```bash
mvn spring-boot:run
```

**注意**：默认使用 H2 数据库和 DeepSeek 模型。如需切换，请参考 README.md。

### 2. 认证说明

**大部分聊天接口需要先登录**。以下接口无需登录：
- `/api/auth/**` - 所有认证接口
- `/api/model/**` - 模型管理接口
- `/api/timezone` - 时区接口
- 静态资源（CSS、JS、页面）

---

## 一、用户认证接口

### 1.1 获取 RSA 公钥

**请求：**
```bash
curl http://localhost:8080/api/auth/public-key
```

**响应：**
```json
{
  "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

> **说明**：Base64 编码的 SPKI 格式，前端使用 Web Crypto API 的 `RSA-OAEP` + `SHA-256` 加密。

---

### 1.2 获取验证码

**请求：**
```bash
curl http://localhost:8080/api/auth/captcha
```

**响应：**
```json
{
  "success": true,
  "image": "data:image/png;base64,iVBORw0KGgo..."
}
```

> 验证码为 4 位随机字母数字干扰线图片，答案仅存 Session 不随响应返回（防爆破）；一次性使用，不区分大小写。
> 仅 dev 调试开关 `auth.captcha.debug-text=true` 开启时额外附带 `captcha` 明文字段（e2e 自动化用，生产禁用）。

---

### 1.3 用户注册

**请求（密码需 RSA 加密后传输）：**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "<RSA加密后的密码>",
    "confirmPassword": "<RSA加密后的确认密码>",
    "captcha": "aB3xK"
  }'
```

**密码要求**：大写字母 + 小写字母 + 数字，6-50位，注册时需输入两遍且一致。

**成功响应：**
```json
{ "success": true, "message": "注册成功", "username": "testuser" }
```

---

### 1.4 用户登录

**请求：**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{ "username": "testuser", "password": "<RSA加密后的密码>", "captcha": "aB3xK" }'
```

**成功响应：**
```json
{ "success": true, "message": "登录成功", "username": "testuser" }
```

> 登录限速：同用户名+IP 60 秒内最多 5 次失败（验证码错误/密码错误均计入），超限返回 **429**；登录成功后会话 ID 自动更换（防会话固定）。

---

### 1.5 获取当前登录用户

```bash
curl http://localhost:8080/api/auth/user
```
```json
{ "loggedIn": true, "username": "testuser" }
```

### 1.6 退出登录

```bash
curl -X POST http://localhost:8080/api/auth/logout
```
```json
{ "success": true, "message": "已退出登录" }
```

---

## 二、聊天接口（需要登录）

### 2.1 流式发送消息（SSE）⭐ 推荐

使用 Server-Sent Events 实时流式返回 AI 回复，逐字显示。

**请求：**
```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "your-session-id",
    "message": "什么是Spring AI？",
    "ragEnabled": true
  }'
```

**请求参数：**
- `sessionId`：会话ID（可选，不传则自动创建新会话）
- `message`：用户消息
- `ragEnabled`：是否启用 RAG 知识库上下文（默认 false）

**SSE 事件流：**
```
event: session
data: a1b2c3d4-e5f6-7890-abcd-ef1234567890

event: message
data: Spring

event: message
data: AI

event: message
data: 是一个

event: done
data: {"contextUsage":35}
```

**事件类型说明：**
- `session` - 流开始时发送，包含会话ID
- `message` - 每个 AI 回复片段（逐字/逐句）
- `done` - 流结束，包含上下文使用率 `contextUsage`
- `error` - 出错时发送，包含错误信息

---

### 2.2 发送消息（POST - 非流式）

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id",
    "message": "你好，请介绍一下自己",
    "ragEnabled": false
  }'
```

**响应：**
```json
{
  "success": true,
  "sessionId": "your-session-id",
  "response": "你好！我是一个AI助手..."
}
```

> `sessionId` 始终返回真实会话 ID（不传时自动创建新会话并返回其 ID）。

---

### 2.3 发送消息（GET 旧接口，已移除）

> **v2.0.0 起已移除**：GET 副作用端点可被跨站请求触发（CSRF 载体）。非流式发送请用 2.2 的 POST，流式请用 2.1。

---

### 2.4 创建新会话

```bash
curl -X POST http://localhost:8080/api/chat/conversation
```
```json
{ "success": true, "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" }
```

---

### 2.5 获取当前用户的所有会话

```bash
curl http://localhost:8080/api/chat/conversations
```
```json
{
  "success": true,
  "conversations": [
    { "id": "a1b2c3d4-...", "title": "什么是Spring AI？...", "updatedAt": "2026-06-15T10:30:05" }
  ]
}
```

---

### 2.6 获取会话历史

> 仅限会话拥有者访问，跨用户/未登录/会话不存在返回 400 `{success:false, message}`。

```bash
curl http://localhost:8080/api/chat/history/your-session-id
```
```json
{
  "success": true,
  "sessionId": "your-session-id",
  "messages": [
    { "id": 1, "role": "user", "content": "你好", "messageOrder": 0, "createdAt": "..." },
    { "id": 2, "role": "assistant", "content": "你好！...", "messageOrder": 1, "createdAt": "..." }
  ],
  "count": 2
}
```

---

### 2.7 删除会话（仅拥有者）

```bash
curl -X DELETE http://localhost:8080/api/chat/conversation/your-session-id
```
```json
{ "success": true, "message": "会话已删除" }
```

---

### 2.8 压缩上下文

用 AI 将较早的对话总结为摘要，释放上下文空间。

**请求：**
```bash
curl -X POST http://localhost:8080/api/chat/compress/your-session-id
```

**响应：**
```json
{
  "success": true,
  "message": "上下文已压缩，8 条旧消息已总结为摘要",
  "contextUsage": 25
}
```

---

### 2.9 获取上下文使用率

```bash
curl http://localhost:8080/api/chat/context-usage/your-session-id
```
```json
{ "success": true, "contextUsage": 65 }
```

---

## 三、知识库文档接口（需要登录）

### 3.1 上传文档到知识库

支持 TXT、PDF、MD 等格式，最大 10MB。

**请求（multipart/form-data）：**
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/document.pdf"
```

**响应：**
```json
{
  "success": true,
  "message": "文档上传成功",
  "document": {
    "docId": "doc-1",
    "fileName": "document.pdf",
    "chunkCount": 15,
    "fileSize": 102400,
    "uploadedAt": "2026-06-15T10:30:00"
  }
}
```

> **说明**：文档上传后自动经过 Apache Tika 解析 → TokenTextSplitter 分块 → EmbeddingModel 向量化 → 存入 VectorStore。

---

### 3.2 获取文档列表

```bash
curl http://localhost:8080/api/documents/list
```
```json
{
  "success": true,
  "documents": [
    { "docId": "doc-1", "fileName": "doc.pdf", "chunkCount": 15, "fileSize": 102400, "uploadedAt": "..." }
  ],
  "count": 1
}
```

---

### 3.3 删除文档

持久化模式下会同时从向量库和数据库中删除。

```bash
curl -X DELETE http://localhost:8080/api/documents/doc-1
```
```json
{ "success": true, "message": "文档已删除" }
```

---

## 四、模型管理接口

### 4.1 获取当前模型

```bash
curl http://localhost:8080/api/model/current
```
```json
{ "success": true, "model": "deepseek", "modelName": "DeepSeek V3" }
```

### 4.2 获取可用模型列表

```bash
curl http://localhost:8080/api/model/available
```
```json
{
  "success": true,
  "models": [
    { "key": "ollama", "name": "离线模式", "description": "本地部署，免费使用，隐私安全", "type": "offline" },
    { "key": "deepseek", "name": "DeepSeek V3", "description": "在线专家模式，更强的推理能力", "type": "online" },
    { "key": "openai", "name": "OpenAI GPT", "description": "OpenAI 官方模型，稳定可靠", "type": "online" }
  ]
}
```

### 4.3 切换模型

> 全局开关，**需登录**（匿名 401，防恶意切换影响全体用户）。

```bash
curl -X POST http://localhost:8080/api/model/switch \
  -H "Content-Type: application/json" \
  -d '{ "model": "ollama" }'
```
```json
{ "success": true, "message": "模型已切换到: 离线模式", "model": "ollama", "modelName": "离线模式" }
```

---

### 4.4 获取模型参数预设

获取当前用户对某模型的自定义参数（temperature、topP、topK、maxTokens）。

**获取某模型的参数：**
```bash
curl http://localhost:8080/api/model/params/ollama
```
```json
{
  "success": true,
  "preset": {
    "id": 1, "userId": 1, "modelKey": "ollama",
    "temperature": 0.7, "maxTokens": 2048, "topP": 0.9, "topK": 40,
    "updatedAt": "2026-06-15T10:00:00"
  }
}
```

**获取所有模型的参数：**
```bash
curl http://localhost:8080/api/model/params
```

### 4.5 保存/更新模型参数预设

```bash
curl -X POST http://localhost:8080/api/model/params/deepseek \
  -H "Content-Type: application/json" \
  -d '{
    "temperature": 0.8,
    "maxTokens": 4096,
    "topP": 0.95
  }'
```
```json
{
  "success": true,
  "message": "参数已保存",
  "preset": { "id": 2, "userId": 1, "modelKey": "deepseek", "temperature": 0.8, "maxTokens": 4096, "topP": 0.95, "topK": null }
}
```

> **说明**：参数预设保存后，后续该模型的所有对话将自动应用这些参数。

---

## 五、时区接口

### 5.1 获取IP时区和位置信息

根据客户端 IP 自动获取时区和经纬度。

```bash
curl http://localhost:8080/api/timezone
```
```json
{
  "success": true,
  "timezone": "Asia/Shanghai",
  "ip": "203.0.113.1",
  "latitude": 31.2222,
  "longitude": 121.4581
}
```

> **说明**：本地/私有 IP 使用服务器系统默认时区。结果缓存 24 小时。

---

## 六、图像生成接口（需要登录）

### 6.1 生成图像

调用阿里云百炼通义万相模型（wan2.6-t2i）生成图像，自动转存到配置的存储服务。

**请求：**
```bash
curl -X POST http://localhost:8080/api/image/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "一只可爱的橘猫在阳光下打盹",
    "size": "1024x1024",
    "n": 1
  }'
```

**请求参数：**
| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `prompt` | string | 是 | - | 图像描述提示词 |
| `size` | string | 否 | `1024x1024` | 图像尺寸（如 1024x1024、720x1280） |
| `n` | int | 否 | `1` | 生成数量（1~4） |
| `quality` | string | 否 | - | 图像质量（标准/高清） |
| `style` | string | 否 | - | 图像风格（写实/插画等） |
| `sessionId` | string | 否 | - | 关联到聊天会话的ID |

**响应：**
```json
{
  "success": true,
  "message": "图像生成成功",
  "data": {
    "imageUrl": "/images/2026/07/05/uuid_filename.png",
    "fileId": "2026/07/05/uuid_filename.png",
    "fileName": "ai_image_1234567890.png",
    "storageType": "local",
    "prompt": "一只可爱的橘猫在阳光下打盹",
    "fileSize": 102400,
    "width": 1024,
    "height": 1024,
    "createTime": "2026-07-05T10:30:00",
    "sessionId": null
  }
}
```

> **说明**：`imageUrl` 为可公开访问的图片地址（本地存储时通过 `/images/**` 静态资源映射）。
> 需先配置 `DASHSCOPE_API_KEY` 环境变量或 `application.yml` 中的 `spring.ai.dashscope.api-key`。

---

### 6.2 获取图像生成历史

**请求：**
```bash
curl http://localhost:8080/api/image/records
```

**响应：**
```json
{
  "success": true,
  "records": [
    {
      "id": 1,
      "prompt": "一只可爱的橘猫在阳光下打盹",
      "imageUrl": "/images/2026/07/05/uuid_filename.png",
      "fileId": "2026/07/05/uuid_filename.png",
      "fileName": "ai_image_1234567890.png",
      "storageType": "local",
      "fileSize": 102400,
      "width": 1024,
      "height": 1024,
      "sessionId": null,
      "createTime": "2026-07-05T10:30:00"
    }
  ],
  "count": 1
}
```

---

### 6.3 获取指定会话中的图像生成记录

**请求：**
```bash
curl http://localhost:8080/api/image/records/your-session-id
```

**响应：**
```json
{
  "success": true,
  "records": [],
  "count": 0
}
```

---

## 七、AI 短视频工作室接口（需要登录）

完整工作流：**创意想法 → 分镜脚本 → 提示词优化 → 并行图片生成（SSE 实时进度） → 快速预览 / BGM 配乐 / 多比例高清导出**

前端页面：http://localhost:8080/video-studio

> 视频合成依赖 FFmpeg（含 ffprobe，路径由 `video.ffmpeg-path` / `video.ffprobe-path` 配置，不依赖 PATH，需 ≥4.3），旁白配音依赖 Edge TTS（`pip install edge-tts`，Python 解释器由 `video.python-command` 指定，未安装时自动跳过配音）。

### 7.1 生成分镜脚本

输入创意想法，LLM 生成多镜头分镜脚本并持久化保存（可选 `templateId` 应用视频模板，见 7.10）：

```bash
curl -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea": "一只橘猫的温馨日常，适合社交平台投放"}'

# 应用视频模板生成（风格引导注入 System Prompt）
curl -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea": "一只橘猫的温馨日常", "templateId": 2}'
```

**响应：**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "title": "橘猫的一天",
    "theme": "温馨治愈",
    "status": "DRAFT",
    "shots": [
      {
        "id": 11, "shotOrder": 1,
        "sceneDescription": "清晨阳光洒进窗台，橘猫蜷缩在毛毯上睡觉",
        "cameraMovement": "缓慢推进", "durationSeconds": 5,
        "narration": "清晨的第一缕阳光",
        "imagePrompt": "A ginger cat sleeping on a blanket in morning sunlight...",
        "imageUrl": null, "imageStatus": "PENDING"
      }
    ]
  }
}
```

---

### 7.2 脚本列表 / 详情 / 更新 / 删除

```bash
# 脚本列表（仅当前用户的）
curl http://localhost:8080/api/video/scripts

# 脚本详情（含全部镜头）
curl http://localhost:8080/api/video/scripts/1

# 重命名
curl -X PUT http://localhost:8080/api/video/scripts/1 \
  -H "Content-Type: application/json" \
  -d '{"title": "新标题"}'

# 删除脚本（含镜头）
curl -X DELETE http://localhost:8080/api/video/scripts/1
```

**列表响应：**
```json
{ "success": true, "data": [ { "id": 1, "title": "橘猫的一天", "shotCount": 5, "status": "DRAFT" } ] }
```

---

### 7.3 镜头管理（增删改 / 拖拽排序）

```bash
# 添加镜头（追加到末尾）
curl -X POST http://localhost:8080/api/video/scripts/1/shots \
  -H "Content-Type: application/json" \
  -d '{"sceneDescription":"新的镜头","narration":"","imagePrompt":"","cameraMovement":"固定","durationSeconds":5}'

# 更新镜头（任意字段；imagePrompt 变化时自动清空旧图并置为 PENDING）
curl -X PUT http://localhost:8080/api/video/scripts/1/shots/11 \
  -H "Content-Type: application/json" \
  -d '{"durationSeconds": 6, "narration": "修改后的旁白"}'

# 删除镜头
curl -X DELETE http://localhost:8080/api/video/scripts/1/shots/11

# 拖拽排序（shotIds 为全量有序 ID 列表）
curl -X PUT http://localhost:8080/api/video/scripts/1/shots/reorder \
  -H "Content-Type: application/json" \
  -d '{"shotIds": [13, 11, 12]}'
```

> **cameraMovement 运镜映射**（合成时真实生效）：含「固定/静止」=纯静态无动效；含「拉」=zoom out；含「平移/横移」=水平 pan；其余（推进/推近/缺省）=zoom in。zoompan 前超采样消除逐帧整数截断抖动（倍数按分辨率自适应：480p 4×/720p 3×/1080p 2×，中间长边≤3840，`video.motion.supersample-factor` 为上限），幅度由 `video.motion.zoom-amount` 控制。

> 提示词变化后旧图片失效：更新 imagePrompt 时服务端会自动清空 imageUrl/imageFileId 并将 imageStatus 置回 PENDING，需重新生成图片。

---

### 7.4 提示词优化（8 种视觉风格）

```bash
# 获取风格列表
curl http://localhost:8080/api/video/enhance-prompt/styles

# 单个提示词优化（中文场景描述 → 高质量英文提示词）
curl -X POST http://localhost:8080/api/video/enhance-prompt \
  -H "Content-Type: application/json" \
  -d '{"sceneDescription": "夕阳下橘猫懒洋洋地卧在窗台", "style": "CINEMATIC_REALISM"}'

# 批量优化脚本所有镜头的提示词（逐镜头调 LLM，耗时较长）
curl -X POST http://localhost:8080/api/video/enhance-prompt/script/1 \
  -H "Content-Type: application/json" \
  -d '{"style": "ANIME"}'
```

**风格列表：** `CINEMATIC_REALISM`（电影写实）、`ANIME`（动漫）、`WATERCOLOR`（水彩）、`CYBERPUNK`（赛博朋克）、`PIXAR_3D`（皮克斯3D）、`VINTAGE`（复古）、`CHINESE_PAINTING`（国画）、`FANTASY`（奇幻），也支持中文传参（如 `"风格": "动漫"`）。

**单个优化响应：**
```json
{
  "success": true,
  "data": {
    "enhancedPrompt": "A ginger cat lounging on a windowsill at golden hour, warm cinematic lighting...",
    "negativePrompt": "text, watermark, blurry, low quality",
    "style": "CINEMATIC_REALISM",
    "styleName": "电影写实"
  }
}
```

---

### 7.5 图片生成（单镜头 / 批量 SSE / 生成器管理）

```bash
# 单镜头生成（style 可选；本地 SD 生成器会附加负面提示词并自动套用模型风格预设）
curl -X POST http://localhost:8080/api/video/images/shot/1/11 \
  -H "Content-Type: application/json" \
  -d '{"style": "CINEMATIC_REALISM"}'

# 生成器列表（含可用性检测）
curl http://localhost:8080/api/video/images/generators

# 运行时切换生成器（type: dashscope / local）
curl -X PUT "http://localhost:8080/api/video/images/generator?type=local"
```

> **可用 type**：`dashscope`（默认万相）/ `local` / `zhipu` / `siliconflow` / `openai` / `ark`。
> 后四家为通用 OpenAI 兼容实现（智谱 CogView / 硅基流动 Kolors·FLUX / OpenAI gpt-image-1 / 火山方舟 Seedream），
> Key 经环境变量 `ZHIPU_API_KEY` / `SILICONFLOW_API_KEY` / `OPENAI_API_KEY` / `ARK_API_KEY` 注入，空 Key 时列表显示不可用；
> 负面词自动拼接进 prompt，尺寸按宽高比映射到各家枚举，临时图片 URL 自动下载转存。

**批量并行生成（SSE，浏览器原生 EventSource 仅支持 GET）：**

```
GET /api/video/images/script/1/stream?style=CINEMATIC_REALISM
Accept: text/event-stream
```

**SSE 事件类型：**

| 事件 | data 内容 |
|------|-----------|
| `START` | `{totalShots, message}` |
| `SHOT_START` | `{shotId, shotOrder, currentShotIndex, message}` |
| `SHOT_COMPLETE` | `{shotId, imageUrl, qualityScore, completedShots, failedShots, totalShots}` |
| `SHOT_FAILED` | `{shotId, completedShots, failedShots, message}` |
| `COMPLETE` | `{completedShots, failedShots, message}` |
| `ERROR` | `{message}` |

> 后端并行度由 `image.generation.parallelism` 控制（默认 3），失败自动重试（`image.generation.retry-attempts`）。
>
> **质量评估（模块 J，默认关闭）**：`image.quality.enabled: true` 开启后，每张图片由多模态 LLM 按清晰度/相关性/美学打分（0-10），低于 `image.quality.threshold`（默认 6.0）自动重新生成（≤ `image.quality.max-retries` 次，默认 2，评分反馈并入负面提示词，保留最高分一张）；未配多模态 API Key 或调用失败时降级为分辨率规则评估；评估异常不阻塞生成。评分写入镜头 `qualityScore` 字段（未启用时为 null），随单镜头响应、脚本详情与 SSE `SHOT_COMPLETE` 返回。本地生成器另按 `image.local.model` 自动套用 SD1.5/SDXL/FLUX 风格预设（`image.local.style-prefix` / `image.local.extra-negative-prompt` 可覆盖）。

---

### 7.6 视频预览与导出

```bash
# 480p 快速预览（低分辨率 + 水印，秒级出片确认效果）
curl -X POST http://localhost:8080/api/video/export/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "16:9", "bgmId": null, "subtitle": true}'

# 高清导出（720p/1080p，可选 BGM 与字幕烧录，字幕语言可选 zh/en/bilingual）
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "resolution": "1080p", "aspectRatio": "9:16", "bgmId": 2, "subtitle": true, "subtitleLanguage": "bilingual", "withLogo": true}'

# 导出历史（含失败原因，可重新下载）
curl http://localhost:8080/api/video/exports/1
```

**请求参数：**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `scriptId` | long | 是 | - | 脚本ID |
| `resolution` | string | 否 | `720p` | 高清导出分辨率（720p/1080p），预览固定 480p |
| `aspectRatio` | string | 否 | `16:9` | 画面比例（16:9 / 9:16 / 1:1） |
| `bgmId` | long | 否 | null | 使用的 BGM，null 表示不配乐 |
| `subtitle` | boolean | 否 | false | 是否烧录字幕（无旁白时自动忽略） |
| `subtitleLanguage` | string | 否 | `zh` | 字幕语言：`zh` 原文 / `en` 英文（LLM 翻译）/ `bilingual` 中英双语；配音仍为中文原文 |
| `withLogo` | boolean | 否 | false | 是否叠加品牌 Logo 角标（未配置 `video.watermark.logo-path` 时自动忽略） |

**分辨率 × 比例 → 输出尺寸：**

| 分辨率\比例 | 16:9 横屏(B站) | 9:16 竖屏(抖音) | 1:1 方形(小红书) |
|--------------|----------------|----------------|----------------|
| 480p（预览） | 854x480 | 480x854 | 480x480 |
| 720p | 1280x720 | 720x1280 | 720x720 |
| 1080p | 1920x1080 | 1080x1920 | 1080x1080 |

> - 所有镜头图片必须为 READY 状态才可导出
> - 预览版叠加半透明 "PREVIEW" 水印、crf 28；正式版无水印、crf 23
> - 高清导出成功后脚本状态自动变为 EXPORTED
> - 合成流程：图片下载 → Ken Burns 动效 + xfade 转场 → Edge TTS 旁白（可用时）→ BGM 混音（可选，音量 15% + 2s 淡入/3s 淡出）→ Logo 角标（可选）→ 字幕烧录（可选）→ 存储

**响应（data.video 部分）：**
```json
{
  "videoUrl": "/images/2026/11/07/video_1730966400000.mp4",
  "fileSizeBytes": 2512345,
  "width": 1080, "height": 1920,
  "totalDuration": 21,
  "hasNarration": true, "hasAudio": true, "hasBgm": true, "hasSubtitle": true
}
```

---

### 7.7 BGM 管理（音乐库 / AI 推荐 / 上传）

```bash
# BGM 列表（内置 + 当前用户上传，可按分类过滤 ?category=happy）
curl http://localhost:8080/api/video/bgm

# AI 推荐BGM（LLM 分析主题情感后匹配分类曲目）
curl "http://localhost:8080/api/video/bgm/recommend?theme=%E6%B8%A9%E9%A6%A8%E6%B2%BB%E6%84%88%E7%9A%84%E7%8C%AB%E5%92%AA%E6%97%A5%E5%B8%B8"

# 上传自定义 BGM（mp3/wav/ogg/m4a/flac）
curl -X POST http://localhost:8080/api/video/bgm/upload \
  -F "file=@./my-music.mp3" \
  -F "name=我的配乐" \
  -F "category=happy"
```

**推荐响应：**
```json
{
  "success": true,
  "data": {
    "category": "calm",
    "reason": "温馨治愈的猫咪日常适合舒缓配乐",
    "available": true,
    "bgm": { "id": 2, "name": "peaceful piano", "category": "calm", "durationSeconds": 60 }
  }
}
```

> **内置音乐库**：将免版权音乐按 `分类__名称.mp3` 命名放入 `./data/bgm/` 目录（如 `happy__sunny_morning.mp3`），启动时自动扫描注册。分类：`happy`（轻快）/ `sad`（忧伤）/ `epic`（史诗）/ `calm`（舒缓）/ `tense`（紧张）。项目已预置 5 首程序合成的免版权和弦垫音（每分类 1 首，约 42s），可用 `tools/generate-preset-bgm.ps1` 重新生成或替换为真实音乐。

---

### 7.8 字幕系统（SRT 导出 / 效果预览 / 多语言）

根据镜头旁白自动生成带时间轴的字幕（时间轴与 xfade 转场精确对齐：转场中点切换，相邻字幕无缝不重叠）；支持多语言：`zh` 原文 / `en` 英文（LLM 翻译）/ `bilingual` 中英双语双行：

```bash
# 生成 SRT 字幕文件（上传存储 + 返回内容，社媒平台可直接上传）
curl -X POST http://localhost:8080/api/video/subtitle/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1}'

# 生成英文字幕（LLM 翻译旁白，失败自动回退原文）
curl -X POST http://localhost:8080/api/video/subtitle/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "language": "en"}'

# 渲染一帧字幕效果预览图（首镜头图片 + 当前比例 + 第一条字幕，可指定语言）
curl -X POST http://localhost:8080/api/video/subtitle/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "9:16", "language": "bilingual", "style": {"fontSize": 28, "position": "bottom"}}'
```

**generate 响应：**
```json
{
  "success": true,
  "data": {
    "srtUrl": "/images/2026/11/07/script_1_subtitle_1730966400000.srt",
    "srtContent": "1\n00:00:00,000 --> 00:00:03,600\n清晨的第一缕阳光\n\n...",
    "cueCount": 5,
    "language": "zh"
  }
}
```

**preview 可选 style 参数：**

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `fontName` | string | `Microsoft YaHei`（可配 `video.subtitle.font-name`） | 字体名，需系统已安装 |
| `fontSize` | int | `24` | 字号（1280x720 坐标系，自动等比缩放） |
| `fontColor` | string | `#FFFFFF` | 字体颜色（#RRGGBB） |
| `outlineColor` | string | `#000000` | 描边颜色 |
| `outlineWidth` | int | `2` | 描边宽度（像素） |
| `position` | string | `bottom` | 显示位置：`bottom` / `center` / `top` |

**preview 响应：**
```json
{ "success": true, "data": { "previewUrl": "/images/2026/11/07/subtitle_preview_1730966400000.png", "width": 720, "height": 1280 } }
```

> 字幕烧录随导出进行（7.6 的 `subtitle` + `subtitleLanguage` 参数，FFmpeg subtitles 滤镜 + libass）；前端工作流界面亦提供「烧录字幕」开关、字幕语言下拉（中文/English/双语）、「导出SRT」与「字幕预览」按钮。翻译结果按原文内存缓存，同一旁白重复导出不重复调用 LLM。

---

### 7.9 社交媒体导出（平台预设 / 封面截帧）

#### 平台预设列表

`GET /api/video/export/platforms` 返回六平台规格：

| name | displayName | 比例 | 分辨率 | 推荐时长 |
|------|-------------|------|--------|----------|
| `DOUYIN` | 抖音 | 9:16 | 1080x1920 | ≤60s |
| `BILIBILI` | B站 | 16:9 | 1920x1080 | 无限制 |
| `XIAOHONGSHU` | 小红书 | 1:1 | 1080x1080 | ≤60s |
| `KUAISHOU` | 快手 | 9:16 | 1080x1920 | ≤60s |
| `WEIXIN_VIDEO` | 视频号 | 16:9 | 1920x1080 | 无限制 |
| `YOUTUBE_SHORTS` | YouTube Shorts | 9:16 | 1080x1920 | ≤60s |

#### 按平台导出

```bash
curl -X POST http://localhost:8080/api/video/export/platform \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "platform": "DOUYIN", "bgmId": 2, "subtitle": true, "subtitleLanguage": "zh", "withLogo": false}'
```

- `platform` 支持枚举名（大小写不敏感）或中文显示名（如 `"抖音"`）
- 分辨率固定映射 1080p 档位；时长超平台推荐上限**不拦截**，响应附 `durationWarning` 提示
- 响应 data：exportHd 结果 + `platform` 规格对象（+ `durationWarning`）

#### 生成封面帧

```bash
# 从最近一次成功导出截取中间帧（默认）
curl -X POST http://localhost:8080/api/video/cover/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1}'

# 指定导出记录与截帧时刻
curl -X POST http://localhost:8080/api/video/cover/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "exportId": 12, "timeOffset": 3.5}'
```

**响应：**
```json
{ "success": true, "data": { "coverUrl": "/images/2026/11/08/video_cover_1731000000000.jpg", "timeOffsetSeconds": 10.5, "exportId": 12 } }
```

> 封面地址回写导出记录（导出历史可见缩略图）；批量多平台导出由前端并行调用 `/export/platform` 实现，API 用户可自行并发调用；导出完成后可用下方 ZIP 打包端点一次性下载。

#### 批量打包下载（ZIP）

```bash
# 将多条导出记录的视频（+已有封面）打包为 ZIP 流式下载
curl -X POST http://localhost:8080/api/video/export/batch-download \
  -H "Content-Type: application/json" \
  -d '{"exportIds": [12, 13, 14]}' \
  -o video_exports.zip
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `exportIds` | long[] | 是 | 导出记录 ID 列表，1-10 条，仅限当前用户的成功导出 |

- 响应：`application/zip` 流（`Content-Disposition: attachment`）；校验失败返回 JSON 错误
- ZIP 内容：`{序号}_{分辨率}_{比例}_{导出ID}.mp4`（如 `01_1080p_9x16_12.mp4`）+ 对应封面（若有）+ `manifest.txt` 清单（分辨率/比例/时长/BGM/字幕语言）
- 前端：批量三平台导出结果区与导出历史区均提供「打包下载」按钮

---

### 7.10 视频模板（模块 H：预设风格 / 应用 / 自定义）

系统启动时幂等预置 8 个内置模板：产品展示 / 故事讲述 / 知识科普 / 风景旅拍 / 美食制作 / 情感语录 / 科技数码 / 运动健身。应用模板 = 将模板的风格引导（stylePrefix + 推荐镜头数）注入脚本生成 System Prompt。

```bash
# 模板列表（系统内置 + 当前用户自定义）
curl http://localhost:8080/api/video/templates

# 模板详情
curl http://localhost:8080/api/video/templates/1

# 应用模板生成分镜脚本（响应 data 与 7.1 脚本结构一致）
curl -X POST http://localhost:8080/api/video/templates/1/apply \
  -H "Content-Type: application/json" \
  -d '{"idea": "一款无线耳机的种草短片"}'

# 保存自定义模板
curl -X POST http://localhost:8080/api/video/templates/custom \
  -H "Content-Type: application/json" \
  -d '{"name": "我的治愈风", "category": "story", "description": "温暖治愈系", "shotCount": 5, "stylePrefix": "warm healing cinematic style, soft light", "defaultBgmCategory": "calm"}'

# 删除自定义模板（系统内置模板不可删）
curl -X DELETE http://localhost:8080/api/video/templates/9
```

**模板字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 模板名称（必填） |
| `category` | string | 分类：product/story/knowledge/travel/food/emotion/tech/fitness/other |
| `description` | string | 模板描述（前端选择提示） |
| `shotCount` | int | 推荐镜头数（默认 5） |
| `stylePrefix` | string | 风格引导（英文，注入脚本生成 System Prompt） |
| `defaultBgmCategory` | string | 推荐 BGM 分类（happy/sad/epic/calm/tense） |
| `defaultVoice` | string | 推荐 TTS 声音（预留） |
| `isSystem` | boolean | 是否系统内置（只读，内置模板禁删） |

> 自定义模板仅创建者可见可删；前端新建脚本区提供模板下拉，脚本头部提供「另存为模板」（镜头数 + 当前视觉风格）。

---

### 7.11 Agent 工具清单（Phase 3 切片1：服务 Tool 化）

六项核心能力已用 Spring AI `FunctionToolCallback` 包装为 LLM 可调用工具（`video/agent/VideoAgentToolFactory`），本端点提供工具内省（名称/描述/输入 JSON Schema）；工具执行入口见 7.12 导演 Agent 对话：

```bash
# 工具清单（只读内省，匿名可访问；工具执行时的登录校验由服务层保障）
curl http://localhost:8080/api/video/agent/tools
```

**已注册工具：**

| 工具名 | 用途 | 关键入参 |
|--------|------|----------|
| `generateStoryboard` | 生成分镜脚本并保存（可选模板） | `idea`（必填）、`templateId`（可选） |
| `enhancePrompt` | 中文场景描述 → 英文图像提示词 | `sceneDescription`、`style`（可选） |
| `generateImage` | 单镜头图片生成（含重试/可选质量评估） | `scriptId`、`shotId`、`style`（可选） |
| `evaluateImage` | 图片质量三维评分（0-10） | `imageUrl`（仅本服务存储相对路径，如 /images/…，拒绝外部 URL，SSRF 防护）、`prompt` |
| `exportVideo` | 视频合成导出（预览/高清） | `scriptId`、`preview`、`resolution`、`aspectRatio`、`bgmId`、`subtitle`、`subtitleLanguage`、`withLogo` |
| `recommendBgm` | 按主题情感推荐 BGM | `theme` |

**响应示例（data 数组单元素）：**
```json
{
  "name": "generateStoryboard",
  "description": "根据创意想法生成分镜脚本并保存，返回脚本ID与镜头列表；可选模板ID按模板风格生成",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"idea\":{\"type\":\"string\",\"description\":\"...\"},...}}"
}
```

> 工具在请求线程内同步执行（登录身份可用）；执行失败返回 `{success:false, message}` 而非抛异常，便于 LLM 感知并调整策略。工具内部复用现有 REST 接口同款服务逻辑（权限校验/重试/质量评估等行为一致）。

---

### 7.12 导演 Agent 对话（Phase 3 切片2：ReAct + 人工确认点）

`DirectorAgentService` 用 `ChatClient.mutate()` 挂载导演 System Prompt 与上述六工具，Spring AI 内建 tool-calling 循环即 ReAct（思考→调用工具→观察→继续）；`exportVideo` 经 `ConfirmationGateToolCallback` 人工确认闸门。前端工作室页面底部内置「AI 导演」对话面板（含确认按钮）。

**请求参数（POST /api/video/agent/chat，需登录 Cookie）：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | 是 | 用户消息（空白拒绝） |
| `sessionId` | string | 否 | 会话ID；缺省自动生成并在响应中返回，后续请求携带以延续上下文 |
| `confirmedTools` | string[] | 否 | 本次请求显式确认放行的工具名（如 `["exportVideo"]`），仅对当次请求生效 |

```bash
# 第一轮：普通对话/创作指令
curl -X POST http://localhost:8080/api/video/agent/chat \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"message":"帮我做一个猫咪日常的治愈系短片，3个镜头"}'

# 清空对话历史
curl -X DELETE http://localhost:8080/api/video/agent/chat/{sessionId} -b "JSESSIONID=..."
```

**响应示例（含待确认项）：**
```json
{
  "success": true,
  "data": {
    "reply": "已生成分镜脚本（ID: 5，3 个镜头）。下一步建议逐镜头生成图片…导出前需要你确认。",
    "sessionId": "b7b55c50-…",
    "pendingConfirmations": [
      { "tool": "exportVideo", "input": "{\"scriptId\":5,\"preview\":true,…}", "reason": "导出视频将执行 FFmpeg 合成（耗时数分钟）" }
    ]
  }
}
```

**确认流程（两轮）：**
1. 用户请求导出 → Agent 调用 `exportVideo` 被闸门拦截，工具返回 `{success:false, requiresConfirmation:true}`，Agent 转述操作与参数并请求确认；响应 `pendingConfirmations` 非空，前端渲染「确认执行 exportVideo」按钮
2. 用户点击确认 → 前端重发同一消息并携带 `confirmedTools:["exportVideo"]` → 闸门放行，工具真实执行（预览/高清合成），结果由 Agent 转述

```bash
# 第二轮：显式确认放行
curl -X POST http://localhost:8080/api/video/agent/chat \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"message":"确认导出","sessionId":"b7b55c50-…","confirmedTools":["exportVideo"]}'
```

> 说明：仅 `exportVideo` 设闸（耗时数分钟）；generateImage 等创作循环工具不设闸。对话历史为内存态（重启清空），按 userId:sessionId 隔离，上限 20 条消息；同步执行于请求线程，多轮工具调用时单次请求可能耗时较长（建议客户端超时 ≥ 120s）。未登录/空消息返回 400 `{success:false,message}`。

---

### 7.13 多 Agent 编排（Phase 3 切片3：专家拆分 + 异步流水线）

编剧/美术/音频/剪辑四专家 Agent（`SpecialistAgentFactory`，各自 System Prompt + 工具子集）以「专家即工具」委托给导演；`AgentOrchestrationService` 在专用线程池异步执行一键成片流水线，SecurityContext 提交时捕获、工作线程显式传播（依赖登录身份的工具在异步线程内正常可用）。

**专家分工（工具子集映射现有 6 工具；TTS/混音已内嵌于 exportVideo 管线，不另设工具）：**

| 专家 | 委托工具 | 工具子集 |
|------|---------|---------|
| 编剧 | `delegateToScreenwriter` | generateStoryboard, enhancePrompt |
| 美术 | `delegateToArtist` | generateImage, evaluateImage |
| 音频 | `delegateToAudio` | recommendBgm |
| 剪辑 | `delegateToEditor` | exportVideo（带确认闸门） |

**端点（均需登录 Cookie，服务层校验）：**

| 端点 | 说明 |
|------|------|
| `POST /api/video/agent/orchestrate` | 提交任务，body `{idea（必填）, templateId（可选）}`，立即返回 `{taskId, status:RUNNING}` |
| `GET /api/video/agent/orchestrate/{taskId}` | 轮询状态：`{taskId, status, reply, pendingConfirmations, error}` |
| `POST /api/video/agent/orchestrate/{taskId}/confirm` | 仅 AWAITING_CONFIRMATION 可用（CAS 原子迁移，并发/重复确认仅一次生效），携 confirmedTools=[exportVideo] 续跑；续跑回放原始任务+上轮回复+待确认入参，确保执行的是用户确认的那个操作 |

**状态机：** `RUNNING → COMPLETED / FAILED`；闸门触发时 `RUNNING → AWAITING_CONFIRMATION →(confirm)→ RUNNING → COMPLETED / FAILED`。任务内存态（重启清空）上限 100 条，按 userId 归属校验（越权 400）。

```bash
# 1. 提交一键成片
curl -X POST http://localhost:8080/api/video/agent/orchestrate \
  -H "Content-Type: application/json" -b "JSESSIONID=..." \
  -d '{"idea":"猫咪日常治愈系短片，3个镜头"}'
# → {"success":true,"data":{"taskId":"cea1b607-…","status":"RUNNING",…}}

# 2. 轮询（建议 3s 间隔）
curl http://localhost:8080/api/video/agent/orchestrate/cea1b607-… -b "JSESSIONID=..."

# 3. 待确认时续跑
curl -X POST http://localhost:8080/api/video/agent/orchestrate/cea1b607-…/confirm -b "JSESSIONID=..."
```

**响应示例（AWAITING_CONFIRMATION）：**
```json
{
  "success": true,
  "data": {
    "taskId": "ad2c6556-…",
    "status": "AWAITING_CONFIRMATION",
    "reply": "脚本与图片已就绪。导出将执行 FFmpeg 合成（耗时数分钟），请确认…",
    "pendingConfirmations": [ { "tool": "exportVideo", "input": "{\"scriptId\":1,\"preview\":true,…}", "reason": "…" } ],
    "error": null
  }
}
```

> 委托链闸门行为：专家侧无 confirmedTools，剪辑专家的 exportVideo 必被拦截，由专家转述、导演汇总请求确认；confirm 续跑时导演直接调用自己的 exportVideo（已确认放行）完成导出。前端工作室「AI 导演」面板提供「一键流水线」按钮（提交后 3s 轮询，待确认时渲染确认按钮）。多轮委托耗时较长（分钟级），异步执行不阻塞 HTTP 请求；未登录/空 idea/越权/状态不符均返回 400。

---

## 八、完整测试流程

### 步骤1：获取 RSA 公钥和验证码
```bash
curl http://localhost:8080/api/auth/public-key
curl http://localhost:8080/api/auth/captcha
```

### 步骤2：注册并登录

> **提示**：密码需要 RSA 加密，建议使用浏览器控制台（F12）：
> ```javascript
> async function encryptPassword(password, publicKeyBase64) {
>     const binaryDer = atob(publicKeyBase64);
>     const derArray = new Uint8Array(binaryDer.length);
>     for (let i = 0; i < binaryDer.length; i++) derArray[i] = binaryDer.charCodeAt(i);
>     const cryptoKey = await crypto.subtle.importKey('spki', derArray.buffer,
>         { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
>     const encrypted = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, cryptoKey,
>         new TextEncoder().encode(password));
>     return btoa(String.fromCharCode(...new Uint8Array(encrypted)));
> }
> ```

### 步骤3：创建会话并流式聊天
```bash
# 创建会话
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/chat/conversation | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

# 流式聊天（SSE）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"什么是Spring AI？\",\"ragEnabled\":false}"

# 查看上下文使用率
curl http://localhost:8080/api/chat/context-usage/$SESSION_ID

# 压缩上下文（当使用率过高时）
curl -X POST http://localhost:8080/api/chat/compress/$SESSION_ID
```

### 步骤4：上传文档并使用 RAG
```bash
# 上传文档
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@./test-document.txt"

# 查看文档列表
curl http://localhost:8080/api/documents/list

# 开启 RAG 聊天
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"根据知识库回答问题\",\"ragEnabled\":true}"

# 删除文档
curl -X DELETE http://localhost:8080/api/documents/doc-1
```

### 步骤5：图像生成
```bash
# 生成图像（需先配置 DASHSCOPE_API_KEY）
curl -X POST http://localhost:8080/api/image/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"一只可爱的橘猫在阳光下打盹","size":"1024x1024","n":1}'

# 查看图像生成历史
curl http://localhost:8080/api/image/records
```

### 步骤6：AI 短视频工作流（完整链路）
```bash
# 1. 生成分镜脚本
SCRIPT_ID=$(curl -s -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea":"一只橘猫的温馨日常"}' | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)

# 2. 批量优化提示词（可选，耗时较长）
curl -X POST http://localhost:8080/api/video/enhance-prompt/script/$SCRIPT_ID \
  -H "Content-Type: application/json" -d '{"style":"CINEMATIC_REALISM"}'

# 3. 批量并行生成图片（SSE 实时进度）
curl -N "http://localhost:8080/api/video/images/script/$SCRIPT_ID/stream?style=CINEMATIC_REALISM"

# 4. 480p 快速预览
curl -X POST http://localhost:8080/api/video/export/preview \
  -H "Content-Type: application/json" \
  -d "{\"scriptId\":$SCRIPT_ID,\"aspectRatio\":\"16:9\",\"bgmId\":null}"

# 5. 高清导出（可选 BGM）
BGM_ID=$(curl -s "http://localhost:8080/api/video/bgm" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d "{\"scriptId\":$SCRIPT_ID,\"resolution\":\"1080p\",\"aspectRatio\":\"16:9\",\"bgmId\":$BGM_ID}"

# 6. 查看导出历史
curl http://localhost:8080/api/video/exports/$SCRIPT_ID
```

---

## 九、使用 Postman 测试

**环境变量：**
- `base_url`: `http://localhost:8080`
- `session_id`: 从创建会话响应中获取

**请求列表：**

| # | 名称 | 方法 | URL | Body |
|---|------|------|-----|------|
| 1 | Get Public Key | GET | `{{base_url}}/api/auth/public-key` | - |
| 2 | Get Captcha | GET | `{{base_url}}/api/auth/captcha` | - |
| 3 | Register | POST | `{{base_url}}/api/auth/register` | JSON (需加密密码) |
| 4 | Login | POST | `{{base_url}}/api/auth/login` | JSON (需加密密码) |
| 5 | Get User | GET | `{{base_url}}/api/auth/user` | - |
| 6 | Logout | POST | `{{base_url}}/api/auth/logout` | - |
| 7 | Create Session | POST | `{{base_url}}/api/chat/conversation` | - |
| 8 | List Sessions | GET | `{{base_url}}/api/chat/conversations` | - |
| 9 | Stream Chat | POST | `{{base_url}}/api/chat/stream` | `{"sessionId":"{{session_id}}","message":"你好","ragEnabled":false}` |
| 10 | Send Message | POST | `{{base_url}}/api/chat/send` | `{"sessionId":"{{session_id}}","message":"你好"}` |
| 11 | Get History | GET | `{{base_url}}/api/chat/history/{{session_id}}` | - |
| 12 | Compress Context | POST | `{{base_url}}/api/chat/compress/{{session_id}}` | - |
| 13 | Context Usage | GET | `{{base_url}}/api/chat/context-usage/{{session_id}}` | - |
| 14 | Delete Session | DELETE | `{{base_url}}/api/chat/conversation/{{session_id}}` | - |
| 15 | Upload Document | POST | `{{base_url}}/api/documents/upload` | form-data: file |
| 16 | List Documents | GET | `{{base_url}}/api/documents/list` | - |
| 17 | Delete Document | DELETE | `{{base_url}}/api/documents/doc-1` | - |
| 18 | Current Model | GET | `{{base_url}}/api/model/current` | - |
| 19 | Switch Model | POST | `{{base_url}}/api/model/switch` | `{"model":"deepseek"}` |
| 20 | Get Presets | GET | `{{base_url}}/api/model/params` | - |
| 21 | Save Preset | POST | `{{base_url}}/api/model/params/deepseek` | `{"temperature":0.8,"maxTokens":4096}` |
| 22 | Get Timezone | GET | `{{base_url}}/api/timezone` | - |
| 23 | Generate Image | POST | `{{base_url}}/api/image/generate` | `{"prompt":"一只可爱的橘猫","size":"1024x1024","n":1}` |
| 24 | Get Image Records | GET | `{{base_url}}/api/image/records` | - |
| 25 | Get Image Records By Session | GET | `{{base_url}}/api/image/records/{{session_id}}` | - |
| 26 | Generate Story Script | POST | `{{base_url}}/api/video/scripts/generate` | `{"idea":"一只橘猫的温馨日常"}` |
| 27 | List Video Scripts | GET | `{{base_url}}/api/video/scripts` | - |
| 28 | Get Script Detail | GET | `{{base_url}}/api/video/scripts/{{script_id}}` | - |
| 29 | Enhance Prompt | POST | `{{base_url}}/api/video/enhance-prompt` | `{"sceneDescription":"夕阳下的橘猫","style":"CINEMATIC_REALISM"}` |
| 30 | Enhance Script Prompts | POST | `{{base_url}}/api/video/enhance-prompt/script/{{script_id}}` | `{"style":"ANIME"}` |
| 31 | Generate Shot Image | POST | `{{base_url}}/api/video/images/shot/{{script_id}}/{{shot_id}}` | `{"style":"CINEMATIC_REALISM"}` |
| 32 | Batch Images (SSE) | GET | `{{base_url}}/api/video/images/script/{{script_id}}/stream?style=CINEMATIC_REALISM` | - |
| 33 | List BGM | GET | `{{base_url}}/api/video/bgm` | - |
| 34 | Export Preview | POST | `{{base_url}}/api/video/export/preview` | `{"scriptId":1,"aspectRatio":"16:9"}` |
| 35 | Export HD | POST | `{{base_url}}/api/video/export/hd` | `{"scriptId":1,"resolution":"1080p","aspectRatio":"9:16","bgmId":1}` |
| 36 | Export History | GET | `{{base_url}}/api/video/exports/{{script_id}}` | - |
| 37 | Generate SRT | POST | `{{base_url}}/api/video/subtitle/generate` | `{"scriptId":1}` |
| 38 | Subtitle Preview | POST | `{{base_url}}/api/video/subtitle/preview` | `{"scriptId":1,"aspectRatio":"16:9"}` |
| 39 | List Platforms | GET | `{{base_url}}/api/video/export/platforms` | - |
| 40 | Export for Platform | POST | `{{base_url}}/api/video/export/platform` | `{"scriptId":1,"platform":"DOUYIN","subtitle":true}` |
| 41 | Generate Cover | POST | `{{base_url}}/api/video/cover/generate` | `{"scriptId":1}` |
| 42 | List Agent Tools | GET | `{{base_url}}/api/video/agent/tools` | - |
| 43 | Director Agent Chat | POST | `{{base_url}}/api/video/agent/chat` | `{"message":"帮我做一个猫咪日常短片","sessionId":"可选","confirmedTools":["exportVideo"]}` |
| 44 | Clear Director Chat | DELETE | `{{base_url}}/api/video/agent/chat/{{agent_session_id}}` | - |
| 45 | Submit Orchestration | POST | `{{base_url}}/api/video/agent/orchestrate` | `{"idea":"猫咪日常治愈系短片","templateId":null}` |
| 46 | Orchestration Status | GET | `{{base_url}}/api/video/agent/orchestrate/{{task_id}}` | - |
| 47 | Confirm Orchestration | POST | `{{base_url}}/api/video/agent/orchestrate/{{task_id}}/confirm` | - |

---

## 十、访问 H2 数据库控制台

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

```sql
-- 查看所有用户
SELECT id, username, created_at FROM users;

-- 查看所有会话
SELECT id, session_id, user_id, title, created_at FROM conversations;

-- 查看某个会话的所有消息
SELECT * FROM messages WHERE session_id = 'your-session-id' ORDER BY message_order;

-- 查看 RAG 文档元信息（持久化模式）
SELECT * FROM rag_document;

-- 查看模型参数预设
SELECT * FROM model_param_presets;

-- 查看图像生成记录
SELECT * FROM image_records ORDER BY create_time DESC;

-- 短视频：故事脚本与镜头
SELECT id, title, theme, status FROM story_scripts;
SELECT id, script_id, shot_order, image_status FROM story_shots ORDER BY script_id, shot_order;

-- 短视频：提示词优化记录 / 导出记录 / BGM
SELECT * FROM image_prompt_optimizations ORDER BY id DESC LIMIT 10;
SELECT id, script_id, resolution, aspect_ratio, status FROM video_exports ORDER BY created_at DESC;
SELECT id, name, category, is_built_in, user_id FROM bgm_tracks;
```

---

## 十一、配置说明

### 11.1 图像生成配置

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key}  # 阿里云百炼平台获取
```

### 11.2 存储配置

```yaml
storage:
  type: local  # local / oss / minio / fastdfs
  local:
    base-path: ./uploads
    access-path: /images
  oss:
    endpoint: ${OSS_ENDPOINT:}
    access-key-id: ${OSS_ACCESS_KEY:}
    access-key-secret: ${OSS_SECRET:}
    bucket-name: ${OSS_BUCKET:}
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET:}
    bucket-name: ${MINIO_BUCKET:ai-images}
```

### 11.3 模型配置

```yaml
spring:
  profiles:
    active: deepseek,openai,h2  # 可组合: ollama/openai/deepseek + h2/postgresql
```

### 11.4 RAG 配置

```yaml
rag:
  embedding-model: ollama    # embedding: ollama / openai / dashscope（通义云端）；配置+重启生效
  store-type: memory         # memory（内存）或 pgvector（持久化）
  dashscope:                 # embedding-model=dashscope 时生效（DeepSeek 无 embedding API，云端向量化用通义）
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v4
    dimensions: 768          # 须与 pgvector.dimensions 一致；不同提供者向量不兼容，切换后清空重传
  pgvector:
    dimensions: 768          # nomic-embed-text / text-embedding-v4 维度（openai 为 1536）
    initialize-schema: true  # 自动创建表
```

### 11.5 上下文长度配置

```yaml
chat:
  max-context-messages: 10   # 保留最近10轮对话
```

### 11.6 AI 短视频工作室配置

```yaml
image:
  generator: dashscope               # 图像生成源：dashscope（默认）/ local / zhipu / siliconflow / openai / ark
  compat:                            # OpenAI 兼容四家（Key 环境变量注入，空=不可用；详见 README 配置节）
    zhipu:
      api-key: ${ZHIPU_API_KEY:}
      model: cogview-3-flash
    siliconflow:
      api-key: ${SILICONFLOW_API_KEY:}
      model: Kwai-Kolors/Kolors
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: gpt-image-1
    ark:
      api-key: ${ARK_API_KEY:}
      model: doubao-seedream-4-0-250828
  local:                              # 本地 SD WebUI 配置
    base-url: http://127.0.0.1:7860
    steps: 25
    cfg-scale: 7.0
    timeout: 120                      # 本地生成超时（秒）
    style-prefix: ""                  # 风格前缀覆盖（留空按模型名套用内置预设：SD1.5/SDXL/FLUX）
    extra-negative-prompt: ""         # 负面词覆盖（留空按模型名套用内置预设）
  generation:
    parallelism: 3                     # 并行生成数（避免云端 API 限流）
    retry-attempts: 1                 # 失败重试次数
    retry-delay-ms: 2000              # 重试间隔（毫秒）
    total-timeout-seconds: 300        # 批量生成总超时（秒）
    default-size: 1280x720           # 默认图片尺寸
  quality:                            # 模块 J：图片质量评估（默认关闭）
    enabled: false                    # 开启后低分图片自动重新生成
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI 兼容多模态端点
    api-key: ${DASHSCOPE_API_KEY:}    # 留空时降级为规则评估
    model: qwen-vl-max                # 多模态评估模型
    threshold: 6.0                    # 及格阈值（0-10）
    max-retries: 2                    # 低分最大重试次数

video:
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}   # FFmpeg 可执行文件路径（Windows 建议绝对路径；需 ≥4.3）
  ffprobe-path: ${FFPROBE_PATH:ffprobe} # ffprobe 路径（BGM 时长探测）
  python-command: ${PYTHON_CMD:python}  # 运行 edge-tts 的 Python 解释器（Windows 建议绝对路径）
  compose-timeout-minutes: 8        # 单次 FFmpeg 命令超时（分钟）
  motion:
    zoom-amount: 0.06                # 运镜缩放幅度；越小越稳
    supersample-factor: 4            # zoompan 超采样上限（消抖）；自适应：1080p→2×/720p→3×/480p→4×；1=关闭
  watermark:                        # 社媒导出：品牌水印
    logo-path: ""                    # Logo 角标图片路径（透明底 PNG，建议宽 ≤300px；留空禁用）
  subtitle:                          # 模块 F：字幕烧录
    font-name: Microsoft YaHei       # 字幕字体（需系统已安装；Linux/Docker 可用 Source Han Sans CN）

bgm:
  library-path: ./data/bgm          # 内置音乐库目录，文件名格式 category__name.mp3
                                    #（如 happy__sunny_morning.mp3），启动时自动扫描注册
```

---

## 十二、注意事项

1. **登录要求**：聊天和文档接口需要先登录，未登录返回 401/403
2. **会话归属**：每个会话绑定创建用户，用户只能操作自己的会话
3. **密码安全**：RSA 加密传输，BCrypt 哈希存储
4. **验证码**：4位随机字母数字图片（答案仅存 Session），一次性使用，不区分大小写；登录限速同用户名+IP 60秒内最多5次失败（超限 429）
5. **SSE 流式**：使用 `POST /api/chat/stream` 获取实时流式回复
6. **RAG 知识库**：上传文档后，在聊天时设置 `ragEnabled: true` 启用上下文增强
7. **上下文限制**：默认保留最近10轮对话，可通过压缩功能释放空间
8. **模型参数**：参数预设按用户和模型维度保存，切换模型时自动应用
9. **文件上传**：最大 10MB，支持 TXT/PDF/MD 等格式
10. **数据库持久化**：H2 文件模式存储在 `./data/chatdb.mv.db`
11. **图像生成**：需配置 `DASHSCOPE_API_KEY`，图像自动转存到配置的存储服务
12. **多存储切换**：通过 `storage.type` 配置切换本地/OSS/MinIO/FastDFS，无需修改业务代码
13. **短视频依赖**：视频合成需 FFmpeg（含 ffprobe），路径由 `video.ffmpeg-path` / `video.ffprobe-path` 配置（默认回退 PATH，可用 `FFMPEG_PATH`/`FFPROBE_PATH` 环境变量覆盖），版本需 ≥4.3（xfade）/≥4.4（BGM 混音）；旁白配音需 `pip install edge-tts`，解释器由 `video.python-command` 指定（可用 `PYTHON_CMD` 覆盖；未安装时自动跳过，输出无声视频）
14. **SSE 批量生成**：使用原生 EventSource 监听 `GET /api/video/images/script/{id}/stream`（EventSource 仅支持 GET），事件类型见 7.5
15. **提示词变化**：更新镜头 imagePrompt 后旧图片引用自动清空，需重新生成图片后才能导出视频
16. **BGM 权限**：用户只能使用内置 BGM 和自己上传的 BGM；导出时自动降低音量（约 -16dB）并淡入淡出
17. **导出前置条件**：所有镜头 imageStatus 必须为 READY，否则返回具体哪些镜头未就绪
18. **字幕烧录**：需系统已安装配置的中文字体（默认微软雅黑）；无旁白时 subtitle 参数自动忽略；烧录随导出进行，无需单独调用
19. **平台导出**：平台预设固定映射 1080p 档位；时长超推荐上限仅提示不拦截；封面默认取最近一次成功导出的中间帧并回写导出记录
20. **图片质量评估**：`image.quality.enabled` 默认 false（关闭时生成流程不变）；开启后低分自动重试≤2 次并保留最高分，评分随镜头 qualityScore 字段返回；未配多模态 API 时降级为规则评估，评估异常不阻塞生成
21. **Agent 工具**：`GET /api/video/agent/tools` 仅内省不执行（匿名可读，沿用 /api/video/** 现有策略）；工具执行入口为 7.12 导演对话，执行时登录校验由服务层保障；工具同步执行于请求线程，异步编排时才需 SecurityContext 传播
22. **导演 Agent**：`POST /api/video/agent/chat` 需登录（服务层校验，匿名 400）；仅 `exportVideo` 设人工确认闸门，`confirmedTools` 仅对当次请求生效（无服务端 pending 令牌）；对话历史内存态重启清空、上限 20 条；多轮工具调用耗时较长，客户端超时建议 ≥ 120s
23. **多 Agent 编排**：`/orchestrate` 系列需登录；任务内存态重启清空、上限 100（逐出时原子复检，运行中任务不可被逐出）、按 userId 归属校验；异步工作线程显式传播 SecurityContext（工具内登录身份可用）；confirm 仅 AWAITING_CONFIRMATION 状态可用且 CAS 防并发双重执行，续跑携带原始任务与待确认入参；专家委托无状态（taskDescription 需携带完整上下文）；evaluateImage 工具仅接受存储相对路径（`agent.tools.self-base-url` 解析同源）；前端 3s 轮询，流水线分钟级耗时属正常
24. **v2.0.0 安全加固**：H2 Console 默认关闭；Actuator 仅暴露 health/info（其余需鉴权）；`/images/**` 匿名可读（UUID 文件名不可枚举，compose 服务端自下载依赖）；模型切换/生成器切换需登录；聊天会话全部读写接口带归属校验；Markdown 渲染经 DOMPurify 消毒；遗留 `POST /api/video/generate|generate-image|generate-all-images|compose` 与 `GET /api/chat/send` 已移除；Docker 部署 PostgreSQL 密码必须经 `.env` 的 `POSTGRES_PASSWORD` 设置

---

## 十三、常见问题

**Q: 为什么聊天接口返回 401？**
A: 需要先登录，调用 `/api/auth/login` 并确保 Cookie 被保存。

**Q: SSE 流式接口如何在前端消费？**
A: 使用 `fetch` + `ReadableStream` 或 `EventSource`（仅支持GET），推荐使用 fetch POST 方式。

**Q: RAG 上下文增强没有效果？**
A: 确认已上传文档，并且 `ragEnabled` 设为 `true`。还需确认 embedding 模型可用：ollama 需 `ollama pull nomic-embed-text`；不想依赖本地 Ollama 可设 `rag.embedding-model: dashscope`（通义云端，复用 DASHSCOPE_API_KEY）或 `openai`，重启生效；切换提供者后需清空旧向量重新上传文档。

**Q: 如何切换AI模型？**
A: Web界面（推荐）、API 调用 `POST /api/model/switch`、或修改配置文件。

**Q: 切换模型后需要重启吗？**
A: 不需要，通过 Web 界面或 API 切换立即生效。

**Q: 模型参数预设如何生效？**
A: 保存后，该用户在该模型下的所有对话自动应用自定义参数。

**Q: RSA 公钥每次一样吗？**
A: 不一样，每次启动应用重新生成。

**Q: 图像生成时返回 500 错误？**
A: 确认 DASHSCOPE_API_KEY 已正确配置且账户有调用额度。可在 `.env` 文件中设置。

**Q: 生成的图片如何访问？**
A: 本地存储模式下图片通过 `/images/日期/文件名` 访问（如 `/images/2026/07/05/uuid_filename.png`）。
切换到 OSS/MinIO 后，`imageUrl` 会返回对应的云存储访问地址。

**Q: 如何切换存储方式？**
A: 修改 `application.yml` 中的 `storage.type` 配置：`local` → `oss` / `minio` / `fastdfs`，无需重启。

**Q: 短视频导出时提示 FFmpeg 错误或超时？**
A: 确认 `video.ffmpeg-path` / `video.ffprobe-path` 指向的可执行文件可用（默认回退 PATH）：`ffmpeg -version`；版本需 ≥4.3（xfade 转场），EVCapture 等自带的 4.2 旧构建不可用。多镜头 1080p 合成耗时较长属正常现象，超时上限由 `video.compose-timeout-minutes` 控制（默认 8 分钟）。BGM 混音需 ffmpeg ≥ 4.4。配音失败时检查 `video.python-command` 指向的解释器已装 edge-tts（Windows 裸 `python` 可能被微软商店别名桩劫持，建议绝对路径）。

**Q: BGM 混音后旁白声音变小了？**
A: 后端使用 `amix normalize=0` 保证旁白原音量，BGM 音量固定降至 15%（约 -16dB）不抢旁白；若听感不适可更换更轻柔的曲目。

**Q: 如何添加内置 BGM？**
A: 将免版权音乐按 `分类__名称.mp3` 命名放入 `./data/bgm/`（如 `calm__peaceful_piano.mp3`），重启后自动注册；也可用前端「上传」按钮或 `POST /api/video/bgm/upload` 添加。

**Q: SSE 批量生成图片为什么是 GET 请求？**
A: 浏览器原生 EventSource 仅支持 GET，故批量生成端点为 `GET /api/video/images/script/{id}/stream`，风格通过 `?style=` 查询参数传入。

**Q: 9:16 竖屏导出的画面如何裁剪？**
A: 每张图片先按目标比例 `scale + crop`（保持原始纵横比居中裁剪），再做 Ken Burns 动效，因此 16:9 图片源导出 9:16 视频不会变形。

**Q: 字幕时间轴是如何计算的？**
A: 每条字幕从上一转场中点开始、到下一转场中点结束（与 xfade 转场同步切换，相邻字幕无缝不重叠）；实际视频时长已扣除转场重叠，与画面严格对齐。

**Q: 字幕烧录后乱码或字体不对？**
A: 字幕字体需系统已安装，默认微软雅黑；Linux/Docker 可在 `video.subtitle.font-name` 改为 `Source Han Sans CN` / `Noto Sans CJK SC`，并用 `POST /api/video/subtitle/preview` 预览确认。

**Q: 如何批量导出多个平台？**
A: 前端「批量三平台」按钮并行调用三次 `POST /api/video/export/platform`（抖音/B站/小红书）；API 用户可自行并发调用该接口（每次一个平台）。FFmpeg 编码为 CPU 密集型，并发 2-3 个即可。

**Q: 如何开启图片质量自动评估？**
A: 配置 `image.quality.enabled: true`（可复用 DASHSCOPE_API_KEY，默认走 qwen-vl-max 多模态端点）。生成后自动按清晰度/相关性/美学打分，低于阈值（默认 6.0）自动重试≤2 次并保留最高分；镜头卡片显示质量徽章。未配 API Key 时降级为分辨率规则评估；不想启用时保持默认 false 即可，生成流程完全不受影响。


## Phase 4 切片 1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/video/characters?scriptId= | 角色卡列表 |
| POST | /api/video/characters?scriptId= | 新建角色卡 {name,featureTable,personality,voice} |
| PUT | /api/video/characters/{id}?scriptId= | 更新角色卡 |
| DELETE | /api/video/characters/{id}?scriptId= | 删除角色卡 |
| POST | /api/video/characters/{id}/reference-image?scriptId= | multipart 定妆参考图（image/*） |
| GET | /api/video/characters/voices | 内置 zh 声线列表 |
| PUT | /api/video/scripts/{id}/style | 脚本级画风锁 {style} |

镜头 Map 增 characterIds（id 数组）、shotSize；脚本 Map 增 style、characters。

## Phase 4 切片 2 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/video/videos/shot/{scriptId}/{shotId} | 单镜头 i2v 同步生成（返回镜头 Map；none 时 400） |
| GET | /api/video/videos/script/{scriptId}/stream | 批量生成 SSE（VIDEO_BATCH_START/SHOT_VIDEO_COMPLETE/VIDEO_BATCH_COMPLETE/ERROR） |
| GET | /api/video/videos/generators | 视频生成器列表（none/dashscope-wan） |
| PUT | /api/video/videos/generator?type= | 切换视频生成器 |
| PUT | /api/video/scripts/{id}/continuity | 连续性模式 {mode: NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME} |

镜头 Map 增 videoUrl/videoFileId/videoStatus（NONE/GENERATING/READY/FAILED）/lastFrameUrl；脚本 Map 增 continuityMode。
配置：video.generator（默认 none）、video.workers（2）、video.retry-attempts（1）、video.wan.model（wan2.7-i2v，可换 wan2.6-i2v 仅首帧）/resolution（720P）/timeout-seconds（600）/poll-interval-ms（5000）。
FAQ：local 存储无法提供公网首帧 URL，i2v 返回 400 并指引切换 storage 为 oss/minio；成本基线 720P/4-5s/中低运动幅度，重试次数默认 1（按秒计费红线）。

## Phase 4 切片 3 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/video/candidates?scriptId=&shotId= | 候选列表（含质量分/selected） |
| POST | /api/video/candidates/generate | 抽卡 {scriptId,shotId,type:IMAGE\|VIDEO}，同步返回候选列表（耗时前端 loading） |
| POST | /api/video/candidates/{id}/select?scriptId= | 人工择优选定，回写主资产（VIDEO 含 probe/末帧） |
| POST | /api/video/images/inpaint | 框选重绘 {shotId,x,y,w,h,instruction}，坐标 0-1；产物入候选返回 |

配置：video.candidates.image-count（默认 3）、video.candidates.video-count（默认 2）；一键编排不抽卡。
FAQ：重绘需 image.generator=wan27（supportsEdit），否则 400；坐标越界（x+w>1 等）400；候选成本=N 倍生成，可降 1；主资产暂取最高分仅为 provisional，选用才是最终决策。

## Phase 4 切片 4：多声线配音与后期增强

无新增端点，存量接口扩展：

|方法|路径|变更|
|---|---|---|
|POST|/api/video/export/hd|请求体增可选 upscale/interpolate/sfx（布尔，缺省全 false）；仅高清导出生效，预览/平台/批量导出不受影响|
|PUT|/api/video/scripts/{id}/shots/{shotId}|请求体增 dialogue（对白文本）/dialogueCharacterId（对白角色，null=旁白声线）|
|GET|/api/video/scripts/{id}|镜头 Map 增 dialogue/dialogueCharacterId；导出结果 video 增 hasSfx/hasEnhance|

curl 示例（超分+音效导出）：

```bash
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d '{"scriptId":1,"resolution":"1080p","aspectRatio":"16:9","subtitle":true,"upscale":true,"sfx":true}'
```

配置：video.sfx.enabled（默认 false，总开关）、sfx.library-path（./data/sfx，预置生成 tools/generate-preset-sfx.ps1）、video.realesrgan-path（空=跳过超分）/realesrgan-model（默认 realesrgan-x4plus-anime）、video.rife-path（空=跳过补帧）/rife-model（模型目录）。
FAQ：对白 TTS 声线=角色卡 voice（PUT /api/video/characters/{id} 可改），旁白默认 zh-CN-YunyangNeural（video.tts.narration-voice 可配，见尾部「生产校准」节）；无对白时导出与存量逐字一致；音效推荐 LLM 失败自动关键词回退（雨/风/走/紧张）；增强工具未配置勾选也安全（静默跳过），执行失败仅 warn 不阻断导出；补帧依赖超分抽帧链，仅勾补帧未配超分会跳过；Real-ESRGAN/RIFE 下载见各自 ncnn-vulkan release，CPU 耗时较长（分钟级/镜），Docker 不预装。

## Phase 4 切片 5 端点（视频转绘）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/video/restyle | multipart：file（mp4/mov，2-10s，≤100MB）+ style（可选，空=默认日系动漫）；同步返回任务 RUNNING |
| GET | /api/video/restyle/{id} | 任务状态（归属校验）：status PENDING/RUNNING/SUCCEEDED/FAILED + resultUrl/error |
| GET | /api/video/restyle | 我的转绘任务列表（createdAt 倒序） |

curl 示例：

```bash
curl -X POST http://localhost:8080/api/video/restyle \
  -F "file=@live.mp4" -F "style=日系动漫风格，赛璐璐着色，保持原有动作与机位"
curl http://localhost:8080/api/video/restyle/1
```

配置：video.restyle.model（默认 wan2.7-videoedit）/timeout-seconds（600）/poll-interval-ms（5000）；复用 DASHSCOPE_API_KEY（空 Key 提交报错）。
FAQ：转绘为独立输出模式（RestyleTask），不混入导出历史；耗时 1-5 分钟，计费=输入+输出时长（720P 基线）；视频输入仅公网 URL/oss 临时 URL（协议不支持 base64），local 存储任务 FAILED 带 oss/minio 切换指引；spike 实测动作/机位保持+动漫化达标；Kling transform 为 2-OPT 备选（需 KLING_API_KEY，本期未实现）；冒烟 -Drestyle=true 默认跳过。

## Phase 5 切片 1：本地 ComfyUI i2v

无新增端点，存量接口扩展：

| 方法 | 路径 | 变更 |
|---|---|---|
| GET | /api/video/videos/generators | 列表增 comfyui（available 取决于 ComfyUI 启动+工作流配置） |
| PUT | /api/video/videos/generator?type=comfyui | 切换本地 ComfyUI i2v |

配置：video.comfyui.base-url（默认 http://127.0.0.1:8188）/workflow-path（默认 docs/comfyui/wan22_i2v_5b.template.json，环境变量 COMFYUI_WORKFLOW）/fps（24）/timeout-seconds（2700，2026-11-17 生产校准自 1800 上调）/poll-interval-ms（3000）。
FAQ：模板无关占位符替换，Phase 0 从 ComfyUI 导出校准后覆盖；local 存储首帧直通（同源取字节），无需 oss/minio；local×dashscope-wan 仍照旧指引 oss/minio；帧数 4n+1、720P→1280×704（实测）/480P→832×480；comfyui 路线 video.workers 建议 1；ComfyUI 未启动 available=false、默认 none 零变化；冒烟 -Dcomfyui=true 默认跳过。

## Phase 5 切片 3：本地 ComfyUI SD1.5+LoRA 分镜

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/video/images/generators | 列表增 comfyui（available 取决于 ComfyUI 启动+模板存在） |
| PUT | /api/video/images/generator?type=comfyui | 切换本地 SD1.5+LoRA 分镜 |
| POST/PUT | /api/video/characters | body 增 loraName/triggerWord（null 不覆盖、空串清空）；GET 列表同增两键 |

配置：image.comfyui.workflow-plain/lora/canny（env COMFYUI_IMG_*）/controlnet-enabled（默认 false）/timeout-seconds 120/poll-interval-ms 1000；base-url 复用 video.comfyui.base-url。
FAQ：LoRA 路由仅当角色卡带 loraName 且生成器 supportsLora 生效，否则存量路径逐字不变；触发词仅非空注入（ShotPromptAssembler 唯一装配点）；ControlNet 参考图同源取字节（local 存储闭环）；720 高→704（实测）；SD1.5 裸 VAEDecode；冒烟 -Dtest=ComfyUiImageRealSmokeTest -Dcomfyui=true 默认跳过。

## Phase 5 Track B：评论杂谈自动化（评论工作台 /commentary-studio）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/video/scripts?format=commentary | 仅列评论脚本（format 分离，存量 AI 短剧不混入） |
| POST | /api/video/scripts/commentary/import | 导入评论 JSON（title/theme/shots[]；shots[].insert.attribution→insertAttribution） |
| POST | /api/video/scripts | 保存脚本 body 可带 format（白名单 null/commentary，保存前置校验+保存后回写）；无独立 PUT /format 端点 |
| POST | /api/video/assets | multipart 上传入库（file/name/tags/trimStart/trimEnd；视频 ffprobe 实测时长+可选裁剪） |
| GET | /api/video/assets | 素材列表（当前用户） |
| DELETE | /api/video/assets/{id} | 删除素材（归属校验） |
| POST | /api/video/assets/scan | 文件夹批量扫描入库（仅读配置目录；约定 epNN_标签_名称.mp4/png） |
| POST | /api/video/scripts/{id}/shots/{shotId}/asset | 镜头绑定素材（assetId/asVideo/durationOverride；kind 护栏+IMAGE 失效旧视频） |
| POST | /api/video/export/hd/async | 异步高清导出（body 同 /export/hd；返 {jobId,status:RUNNING}） |
| GET | /api/video/export/jobs/{id} | 异步导出任务状态（RUNNING/SUCCESS/FAILED + data/message；归属校验） |

配置：video.commentary.max-insert-ratio（0.2，引用占比护栏）/attribution-font（空，出处水印中文字体）/import-dir（空，扫描目录）。
FAQ：引用占比=Σ引用镜时长/Σ总时长，超限导出拒绝（无引用镜恒放行，存量 AI 短剧零影响）；合成 TTS 预探测抬升逐镜有效时长防旁白被截，短片段 -stream_loop -1 循环填充；出处水印 drawtext expansion=none 防 %{…} 注入，中文需配 fontfile；异步导出复用转绘 idiom（单线程池+SecurityContext 传播+futures 终态清理），同步 /export/hd 不变；冒烟 -Dtest=VideoComposeAutoFitTest -Dcommentary=true 默认跳过。

## Phase 5 素材库切片：参考素材与 booru 网搜（2026-11-17）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/video/assets | multipart 上传增 category（CHARACTER/SCENE/INSERT 白名单，非法值 400 fail-fast） |
| GET | /api/video/assets?category= | 素材列表可按 category 过滤 |
| POST | /api/video/assets/scan-reference | 扫描配置目录 video.assets.reference-import-dir（约定 char_角色名_描述.png / scene_场景名_描述.jpg，大小写不敏感，多段标签下划线转逗号，幂等去重；不接受用户路径） |
| GET | /api/video/assets/booru/search?q=&provider=&page= | 网搜（q 必填；多 tag 用 + 连接 AND，空格转下划线是单 tag 内分词；provider 缺省 safebooru，可选 danbooru-safe；page 0 基钳 0-4，每页 20） |
| GET | /api/video/assets/booru/preview?provider=&postId= | 缩略图服务端代理（provider+postId 重查过闸取字节，魔数嗅探 png/jpeg/webp/gif；策略拒绝→400 空体/其余→500 空体，前端按加载失败处理） |
| POST | /api/video/assets/booru/ingest | JSON {provider,postId,category,name,tags}；category 仅 CHARACTER/SCENE；postId 缺失/非法→400（回显截断 32）；字节转存自家存储 booru_ts_uuid.ext + sourceNote |
| POST | /api/video/scripts/{scriptId}/asset-matches | LLM 素材匹配推荐（无 body；返 {source:LLM\|FALLBACK, matches}；matches=[] 合法；推荐不落库，确认走镜头 referenceAssetIds） |
| PUT | /api/video/scripts/{id}/shots/{shotId} | body 增 referenceAssetIds（id 数组，批量 PUT 同贯穿）；写侧三校验（归属+IMAGE+category）静默剔除伪造/他人 id |

curl 示例（登录态 Cookie 略）：

```bash
# 多 tag AND 搜索：1girl + black_hair（+ 需 URL 编码为 %2B）
curl "http://localhost:8080/api/video/assets/booru/search?q=1girl%2Bblack_hair&provider=safebooru&page=0"

# 缩略图预览（服务端代理，直接返图片字节）
curl "http://localhost:8080/api/video/assets/booru/preview?provider=safebooru&postId=123456" -o thumb.jpg

# 勾选入库（仅 CHARACTER/SCENE；name 限 200 回落 booru-{postId}，tags 限 500）
curl -X POST http://localhost:8080/api/video/assets/booru/ingest \
  -H "Content-Type: application/json" \
  -d '{"provider":"safebooru","postId":123456,"category":"CHARACTER","name":"初音未来","tags":"hatsune_miku,vocaloid"}'

# LLM 匹配推荐（无 body；失败降级关键词重叠 source=FALLBACK）
curl -X POST http://localhost:8080/api/video/scripts/1/asset-matches

# 镜头绑定参考素材（确认推荐；绑定不失效旧图，只影响下次生成）
curl -X PUT http://localhost:8080/api/video/scripts/1/shots/10 \
  -H "Content-Type: application/json" \
  -d '{"referenceAssetIds":[3,5]}'
```

契约要点：
- **三道安全闸**：① https + host 精确白名单（video.assets.booru.allowed-hosts）+ 禁重定向；② 预览/入库只认 provider+postId 服务端重查，不信任客户端 URL；③ 下载护栏 10s 连接/30s 读取/≤20MB/image/*；rating 三入口（搜索/预览/入库）复核仅 safe/g/general。
- **错误分流**：策略拒绝 RejectException→400 + 原文可透传；上游/存储/DB 故障→500 + 固定文案「素材取用失败，请稍后重试或更换关键词」（防 SQL 外泄，原文进日志）。
- **sourceNote**：`出处页 | rating=X | tags=...`（URL+rating 前置防截断丢出处，限 500）；写库失败补偿删孤儿文件；外链永不进生成链路。
- **参考图消费闸门**：仅 comfyui（IP-Adapter 模板就位时）与 wan27 生成器消费；每镜最多前 2 张（角色定妆图在前、CHARACTER 优先 SCENE）；消费侧 ReferenceImageResolver 三校验最后防线；INSERT 类禁入生成链路。
- **模板路由**（ComfyUiImageGenerator，refs 优先）：ipadapter+lora 组合 / ipadapter / plain / lora / lora+canny；ipadapter 模板未就位 supportsReferences=false 自动降级不阻塞启动。
- 配置：video.assets.reference-import-dir（空=禁用）/video.assets.booru.allowed-hosts/default-provider；image.comfyui.workflow-ipadapter（docs/comfyui/sd15_ipadapter.template.json，已本机校准 plus_sd15+ViT-H）/workflow-ipadapter-lora（组合待手工校准）/ipadapter-weight（0.7，甜点区 0.65-0.85）。
- FAQ：零结果先减 tag 再换源；个别缩略图加载失败多为上游瞬时限流重试即可；重启后生成器回默认源需重新切换；匹配推荐 matches=[] 是合法结果不是故障。

## 生产校准：尾卡镜 / TTS 语速 / ComfyUI 超时（2026-11-17）

无新增端点，存量接口扩展：

| 方法 | 路径 | 变更 |
|---|---|---|
| PUT | /api/video/scripts/{id}/shots/{shotId} | body 增 endCard（布尔，批量 PUT 同贯穿）；true 时旁路生图 provider，由 EndCardImageRenderer 本地 FFmpeg 渲染纯色底+压字（generatorType=endcard） |
| GET | /api/video/scripts/{id} | 镜头 Map 增 endCard/referenceAssetIds |

配置：
- video.endcard.font（默认 C:/Windows/Fonts/msyh.ttc，中文必须 fontfile 否则文字静默不渲染；Linux/Docker 换 Noto Sans CJK）/color（默认 black，lavfi color 表达式）。
- video.tts.narration-voice（默认 zh-CN-YunyangNeural 纪录片男声，TTS_NARRATION_VOICE 覆盖）/narration-rate（+15%，实测 ~5.5 字/秒）/dialogue-rate（+0%，对白不比旁白快）。
- video.comfyui.timeout-seconds 1800→2700（实测 8s 镜 193f ~25min、9s 镜 217f ~31min 顶旧红线）。

FAQ：台词算术——镜头时长 ≥ 字数/字每秒 + 0.6s，改语速后重算全片；尾卡零 API 成本不占生成队列，适合关注/下集预告 CTA；排产建议短切 1.5-2.5s 为主力、workers=1。
已修复（2026-11-18）：字幕时间轴偏移——合成时按 TTS 实测/真片段实测的有效时长累计重排字幕 cue（重排基准为名义声明时长，覆盖 TTS 抬升与真片段实测漂移两类场景）。

已知限制（留后续切片）：booru preview 对不存在 postId 返 500（应 404）。

## 钩子化脚本（2026-11-18）

无新增端点；POST /api/video/scripts（及一切经 VideoScriptService 的生成路径）行为增强：

- system prompt 增钩子叙事硬约束：首镜 3 秒内抛冲突/悬念（开场旁白 ≤15 字）；每 15-20 秒一个情绪转折；末镜留扣子接尾卡关注 CTA（有尾卡镜时放倒数第二镜）。
- 生成后自动校验台词密度：逐镜时长 ≥ (旁白+对白字数)/5.5 + 0.6s；超密仅服务端 warn 日志，响应结构不变、不阻断保存；导出时 TTS 预探测抬升时长兜底，字幕按有效时长重排（见上节已修复项）。
- 尾卡镜（endCard=true）本地压字不走 TTS，不参与密度校验。

## 竖屏优先（2026-11-18）

新增端点 PUT /api/video/scripts/{id}/aspect，请求体 {"aspectRatio": "9:16"}（白名单 16:9/9:16/1:1，null/空串清空回默认横屏，非法值 400）；响应 success/data=脚本详情（同 PUT /{id}/style idiom）。脚本详情（GET /api/video/scripts/{id} 等一切 scriptToMap 出口）新增 aspectRatio 字段，null 透出 "16:9"。

存量端点行为增强（响应结构不变）：

- 生图（单镜 POST /api/video/images/shot/{scriptId}/{shotId}、批量 SSE GET /api/video/images/script/{scriptId}/stream、抽卡 POST /api/video/candidates/generate）：脚本 aspectRatio=9:16 时尺寸自动切 720x1280（无视 image.generation.default-size），提示词注入 centered subject / vertical framing；多人镜（≥2 角色）强制单人特写替代原景别；尾卡本地渲染同尺寸。其余画幅行为不变。
- 导出（POST /api/video/export/preview、/export/hd、/export/hd/async、平台导出）：请求未传 aspectRatio（null/空）时 fallback 脚本画幅；显式传值优先；双 null 保持存量 16:9。9:16 脚本 720p 默认出 720x1280，导出记录 aspectRatio/width/height 同步落库。

### 脚本自我精修（2026-11-18）

- POST /api/video/scripts/generate：首轮稿默认经 2 轮「四人评审团（导演/编剧/画师/乐师）质疑 → 原硬约束重修」后落库；`video.script.refine-rounds=0` 关闭。
- 脚本步骤模型由 `video.script.model` 指定（默认 deepseek-flash 控成本，质量优先可设 VIDEO_SCRIPT_MODEL=deepseek-v4-pro 升档；思考模式默认开启；仅 deepseek profile 生效），单调用读超时 300s。
- 实测 2 轮精修全程 ≈9.7-13 分钟；前端工作台已接 postLong（AbortController，预算 `video.script.client-timeout-ms` 默认 900s 由页面 data-gen-timeout-ms 注入），客户端不再先断连；超时不报失败而提示刷新列表；e2e 驱动用 node:http 解除 fetch 300s 限制（实测 580s 完整响应）。异步化列入后续切片。
- 降级：评审/重修任一失败或产物不可解析 → 保留当前最优稿，接口不报错。
