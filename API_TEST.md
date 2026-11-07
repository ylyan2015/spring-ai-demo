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
  "captcha": "aB3xK"
}
```

> 验证码一次性使用，不区分大小写。

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

---

### 2.3 发送消息（GET - 兼容旧接口）

```bash
curl "http://localhost:8080/api/chat/send?message=你好&sessionId=your-session-id"
```

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

> 视频合成依赖 FFmpeg（含 ffprobe），旁白配音依赖 Edge TTS（`pip install edge-tts`，未安装时自动跳过配音）。

### 7.1 生成分镜脚本

输入创意想法，LLM 生成多镜头分镜脚本并持久化保存：

```bash
curl -X POST http://localhost:8080/api/video/scripts/generate \
  -H "Content-Type: application/json" \
  -d '{"idea": "一只橘猫的温馨日常，适合社交平台投放"}'
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
# 单镜头生成（style 可选；本地 SD 生成器会附加负面提示词）
curl -X POST http://localhost:8080/api/video/images/shot/1/11 \
  -H "Content-Type: application/json" \
  -d '{"style": "CINEMATIC_REALISM"}'

# 生成器列表（含可用性检测）
curl http://localhost:8080/api/video/images/generators

# 运行时切换生成器（type: dashscope / local）
curl -X PUT "http://localhost:8080/api/video/images/generator?type=local"
```

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
| `SHOT_COMPLETE` | `{shotId, imageUrl, completedShots, failedShots, totalShots}` |
| `SHOT_FAILED` | `{shotId, completedShots, failedShots, message}` |
| `COMPLETE` | `{completedShots, failedShots, message}` |
| `ERROR` | `{message}` |

> 后端并行度由 `image.generation.parallelism` 控制（默认 3），失败自动重试（`image.generation.retry-attempts`）。

---

### 7.6 视频预览与导出

```bash
# 480p 快速预览（低分辨率 + 水印，秒级出片确认效果）
curl -X POST http://localhost:8080/api/video/export/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "16:9", "bgmId": null, "subtitle": true}'

# 高清导出（720p/1080p，可选 BGM 与字幕烧录）
curl -X POST http://localhost:8080/api/video/export/hd \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "resolution": "1080p", "aspectRatio": "9:16", "bgmId": 2, "subtitle": true}'

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

**分辨率 × 比例 → 输出尺寸：**

| 分辨率\比例 | 16:9 横屏(B站) | 9:16 竖屏(抖音) | 1:1 方形(小红书) |
|--------------|----------------|----------------|----------------|
| 480p（预览） | 854x480 | 480x854 | 480x480 |
| 720p | 1280x720 | 720x1280 | 720x720 |
| 1080p | 1920x1080 | 1080x1920 | 1080x1080 |

> - 所有镜头图片必须为 READY 状态才可导出
> - 预览版叠加半透明 "PREVIEW" 水印、crf 28；正式版无水印、crf 23
> - 高清导出成功后脚本状态自动变为 EXPORTED
> - 合成流程：图片下载 → Ken Burns 动效 + xfade 转场 → Edge TTS 旁白（可用时）→ BGM 混音（可选，音量 15% + 2s 淡入/3s 淡出）→ 字幕烧录（可选）→ 存储

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

> **内置音乐库**：将免版权音乐按 `分类__名称.mp3` 命名放入 `./data/bgm/` 目录（如 `happy__sunny_morning.mp3`），启动时自动扫描注册。分类：`happy`（轻快）/ `sad`（忧伤）/ `epic`（史诗）/ `calm`（舒缓）/ `tense`（紧张）。

---

### 7.8 字幕系统（SRT 导出 / 效果预览）

根据镜头旁白自动生成带时间轴的字幕（时间轴与 xfade 转场精确对齐：转场中点切换，相邻字幕无缝不重叠）：

```bash
# 生成 SRT 字幕文件（上传存储 + 返回内容，社媒平台可直接上传）
curl -X POST http://localhost:8080/api/video/subtitle/generate \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1}'

# 渲染一帧字幕效果预览图（首镜头图片 + 当前比例 + 第一条字幕）
curl -X POST http://localhost:8080/api/video/subtitle/preview \
  -H "Content-Type: application/json" \
  -d '{"scriptId": 1, "aspectRatio": "9:16", "style": {"fontSize": 28, "position": "bottom"}}'
```

**generate 响应：**
```json
{
  "success": true,
  "data": {
    "srtUrl": "/images/2026/11/07/script_1_subtitle_1730966400000.srt",
    "srtContent": "1\n00:00:00,000 --> 00:00:03,600\n清晨的第一缕阳光\n\n...",
    "cueCount": 5
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

> 字幕烧录随导出进行（7.6 的 `subtitle` 参数，FFmpeg subtitles 滤镜 + libass）；前端工作流界面亦提供「烧录字幕」开关、「导出SRT」与「字幕预览」按钮。

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
  embedding-model: ollama    # embedding: ollama 或 openai
  store-type: memory         # memory（内存）或 pgvector（持久化）
  pgvector:
    dimensions: 768          # nomic-embed-text 维度
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
  generator: dashscope               # 图像生成源：dashscope（云端通义万相）/ local（本地 SD/FLUX）
  local:                              # 本地 SD WebUI 配置
    base-url: http://127.0.0.1:7860
    steps: 25
    cfg-scale: 7.0
    timeout: 120                      # 本地生成超时（秒）
  generation:
    parallelism: 3                     # 并行生成数（避免云端 API 限流）
    retry-attempts: 1                 # 失败重试次数
    retry-delay-ms: 2000              # 重试间隔（毫秒）
    total-timeout-seconds: 300        # 批量生成总超时（秒）
    default-size: 1280x720           # 默认图片尺寸

video:
  compose-timeout-minutes: 8        # 单次 FFmpeg 命令超时（分钟）
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
4. **验证码**：5位随机字母数字，一次性使用，不区分大小写
5. **SSE 流式**：使用 `POST /api/chat/stream` 获取实时流式回复
6. **RAG 知识库**：上传文档后，在聊天时设置 `ragEnabled: true` 启用上下文增强
7. **上下文限制**：默认保留最近10轮对话，可通过压缩功能释放空间
8. **模型参数**：参数预设按用户和模型维度保存，切换模型时自动应用
9. **文件上传**：最大 10MB，支持 TXT/PDF/MD 等格式
10. **数据库持久化**：H2 文件模式存储在 `./data/chatdb.mv.db`
11. **图像生成**：需配置 `DASHSCOPE_API_KEY`，图像自动转存到配置的存储服务
12. **多存储切换**：通过 `storage.type` 配置切换本地/OSS/MinIO/FastDFS，无需修改业务代码
13. **短视频依赖**：视频合成需 FFmpeg（含 ffprobe）在 PATH 中；旁白配音需 `pip install edge-tts`（未安装时自动跳过，输出无声视频）
14. **SSE 批量生成**：使用原生 EventSource 监听 `GET /api/video/images/script/{id}/stream`（EventSource 仅支持 GET），事件类型见 7.5
15. **提示词变化**：更新镜头 imagePrompt 后旧图片引用自动清空，需重新生成图片后才能导出视频
16. **BGM 权限**：用户只能使用内置 BGM 和自己上传的 BGM；导出时自动降低音量（约 -16dB）并淡入淡出
17. **导出前置条件**：所有镜头 imageStatus 必须为 READY，否则返回具体哪些镜头未就绪
18. **字幕烧录**：需系统已安装配置的中文字体（默认微软雅黑）；无旁白时 subtitle 参数自动忽略；烧录随导出进行，无需单独调用

---

## 十三、常见问题

**Q: 为什么聊天接口返回 401？**
A: 需要先登录，调用 `/api/auth/login` 并确保 Cookie 被保存。

**Q: SSE 流式接口如何在前端消费？**
A: 使用 `fetch` + `ReadableStream` 或 `EventSource`（仅支持GET），推荐使用 fetch POST 方式。

**Q: RAG 上下文增强没有效果？**
A: 确认已上传文档，并且 `ragEnabled` 设为 `true`。还需确认 embedding 模型可用。

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
A: 确认 `ffmpeg -version` 可用（含 ffprobe）。多镜头 1080p 合成耗时较长属正常现象，超时上限由 `video.compose-timeout-minutes` 控制（默认 8 分钟）。BGM 混音需 ffmpeg ≥ 4.4。

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
