# Spring AI Demo - 多模型智能聊天应用

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，支持多种AI模型（Ollama、OpenAI、DeepSeek等），内置用户注册登录系统、RAG 知识库、SSE 流式输出、AI 图像生成（通义万相）、多存储服务、**AI 短视频工作室**（创意想法 → 分镜脚本 → 并行生成画面 → BGM 配乐 → FFmpeg 合成多比例导出）、Docker 一键部署等功能。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并支持灵活切换不同的AI模型提供商，包括本地部署的 Ollama 模型和在线 API 服务（OpenAI、DeepSeek等）。同时提供完整的用户认证体系，聊天记录与用户绑定，RAG 知识库支持文档上传和上下文增强，AI 图像生成与多存储管理，以及端到端的 AI 短视频生产流水线，保障数据隐私安全。

### ✨ 核心特性

- ✅ **用户认证系统** - 注册/登录功能，含随机验证码校验
- ✅ **密码安全传输** - RSA 非对称加密传输密码，BCrypt 加密存储
- ✅ **多轮对话支持** - 自动维护对话上下文，AI 能记住之前的聊天内容
- ✅ **会话管理** - 支持创建、查询、删除多个独立会话
- ✅ **聊天绑定用户** - 每个用户的聊天记录独立隔离，仅本人可查看和删除
- ✅ **历史持久化** - 使用 PostgreSQL/H2 数据库存储对话历史，可随时查阅
- ✅ **上下文记忆** - 可配置的上下文窗口，保留最近 N 轮对话
- ✅ **上下文管理** - 上下文使用率监控、AI 智能压缩，延长对话寿命
- ✅ **SSE 流式输出** - Server-Sent Events 实时流式返回 AI 回复，逐字显示
- ✅ **RAG 知识库** - 上传文档（TXT/PDF/MD等），自动解析分块向量化，聊天时注入相关上下文
- ✅ **RAG 持久化** - 支持内存模式（测试用）和 PgVector 持久化模式（生产用），无缝切换
- ✅ **模型参数预设** - 每个用户可自定义 temperature、topP、topK、maxTokens 等参数
- ✅ **IP 时区定位** - 根据客户端 IP 自动获取时区和经纬度，支持本地化天气显示
- ✅ **RESTful API** - 提供完整的 REST API 接口
- ✅ **Web 界面** - 美观的聊天界面，支持会话列表、实时对话、Markdown 渲染
- ✅ **模型切换** - Web界面直接切换离线模式和在线专家模式
- ✅ **Spring Boot Actuator** - 内置健康检查、指标监控等运维端点
- ✅ **Docker 一键部署** - 多阶段构建镜像 + docker-compose 编排应用和中间件，开箱即用
- ✅ **AI 回复完成通知** - 浏览器桌面通知 + 提示音 + 页面标题徽标，切走页面也不错过回复
- ✅ **AI 图像生成** - 集成阿里云百炼通义万相模型（wan2.6-t2i），通过提示词生成高品质图片
- ✅ **多存储支持** - 统一存储抽象层，支持本地存储 / 阿里云OSS / MinIO / FastDFS 一键切换
- ✅ **图像生成记录** - 生成记录持久化存储，关联用户与聊天会话，支持历史查询
- ✅ **AI 短视频工作室** - 创意想法 → LLM 生成分镜脚本（可编辑/拖拽排序）→ 提示词 LLM 优化（8 种风格）→ 并行生成画面（SSE 实时进度）→ 480p 快速预览 / 720p/1080p 高清导出
- ✅ **多比例视频导出** - 16:9 横屏(B站) / 9:16 竖屏(抖音) / 1:1 方形(小红书)，预览版带水印、正式版无水印，导出历史可追溯
- ✅ **社媒一键导出** - 抖音/B站/小红书/快手/视频号/YT Shorts 六大平台预设（比例+分辨率+推荐时长校验）、封面帧截取、品牌 Logo 角标、批量三平台导出
- ✅ **BGM 配乐** - 内置音乐库（按情感分类）+ LLM 智能推荐 + 自定义上传；自动降音量、淡入淡出、短曲循环
- ✅ **字幕系统** - 旁白自动生成带时间轴字幕（时间轴与转场精确对齐），支持 SRT 单独导出与 ASS 烧录进画面，样式可定制（字体/字号/颜色/描边/位置）
- ✅ **多图像生成后端** - DashScope 通义万相（云端）与本地 Stable Diffusion / FLUX（SD WebUI 协议）配置切换，失败自动重试

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4.1** - 应用框架
- **Spring AI 1.0.0** - AI 集成框架
- **Spring Security** - 安全认证框架
- **Spring Data JPA** - 数据持久化
- **Thymeleaf** - 模板引擎
- **PostgreSQL + PgVector** - 数据库 + 向量持久化存储
- **H2 Database** - 轻量级嵌入式数据库（开发模式）
- **AI Models**: Ollama / OpenAI / DeepSeek（支持切换）
- **DashScope 通义万相** - AI 图像生成引擎（wan2.6-t2i）
- **FFmpeg** - 短视频合成引擎（Ken Burns 动效 + xfade 转场 + BGM 混音 + 多比例裁剪）
- **Edge TTS** - 旁白配音（Python CLI，可选，未安装时自动跳过配音）
- **Apache Tika** - 文档解析（RAG: 支持 TXT/PDF/MD 等格式）
- **RSA + BCrypt** - 密码加密方案
- **Lombok** - 代码简化
- **Spring Boot Actuator** - 运维监控
- **Aliyun OSS SDK / MinIO SDK** - 多存储支持
- **Docker + docker-compose** - 容器化部署与服务编排
- **Maven** - 项目构建工具

## 前置要求

### 方式一：Docker 部署（推荐，零依赖）

仅需安装 Docker 和 docker-compose，无需 JDK、Maven、PostgreSQL 等任何本地环境：

1. **Docker** - 从 [https://www.docker.com/get-started](https://www.docker.com/get-started) 下载安装
2. **Docker Compose** - Docker Desktop 已内置

### 方式二：本地开发

1. **JDK 17** 或更高版本
2. **Maven 3.6+**
3. **AI模型服务**（至少选择一种）：
   - **Ollama**（本地模型）- 从 [https://ollama.com](https://ollama.com) 下载并安装
   - **OpenAI API Key** - 注册 OpenAI 账号获取
   - **DeepSeek API Key** - 注册 DeepSeek 账号获取
4. **（可选）DashScope API Key** - 如需图像生成功能，注册阿里云百炼平台获取
5. **（可选）FFmpeg** - 如需 AI 短视频工作室的视频合成/导出功能（需在 PATH 中，含 ffprobe）
6. **（可选）Edge TTS** - 如需短视频旁白配音：`pip install edge-tts`（未安装时自动跳过配音）
7. **（可选）PostgreSQL + pgvector 扩展** - 如需 RAG 持久化存储

## 快速开始

### 🐳 Docker 一键部署（推荐）

只需 3 步即可启动所有服务（应用 + PostgreSQL/pgvector + 可选 Ollama）：

```bash
# 1. 克隆项目
git clone <repository-url>
cd spring-ai-demo

# 2. 配置 API Key
cp .env.example .env
# 编辑 .env 文件，填入真实的 DeepSeek / OpenAI / DashScope API Key

# 如需使用 AI 图像生成功能，请设置 DASHSCOPE_API_KEY（阿里云百炼平台获取）

# 3. 启动所有服务
docker compose up -d
```

启动后访问 http://localhost:8080 即可使用。

#### 启用 Ollama 本地模型（可选）

```bash
docker compose --profile ollama up -d

# 拉取模型（首次需要）
docker exec spring-ai-ollama ollama pull qwen2.5:7b-instruct
```

#### 常用 Docker 命令

```bash
# 查看服务状态
docker compose ps

# 查看应用日志
docker compose logs -f app

# 重新构建镜像并启动
docker compose up -d --build

# 停止所有服务
docker compose down

# 停止并清除数据卷（重置数据库）
docker compose down -v
```

> **说明**：docker-compose 会自动激活 `docker` Spring profile，该 profile 内部自动激活 `postgresql`、`deepseek`、`openai` 子 profile，并将数据库和 Ollama 地址替换为 Docker 容器服务名。

### 💻 本地开发部署

#### 1. 克隆项目

```bash
git clone <repository-url>
cd spring-ai-demo
```

#### 2. 准备AI模型服务

根据你选择的模型类型进行配置：

#### 选项A：使用 Ollama（本地模型）
```bash
# 启动 Ollama 服务
ollama serve

# 在另一个终端中拉取模型
ollama pull qwen2.5:7b-instruct
# RAG embedding 模型（可选）
ollama pull nomic-embed-text
```

#### 选项B：使用 OpenAI
获取 API Key 后，设置环境变量：
```bash
export OPENAI_API_KEY=sk-your-api-key-here
```

#### 选项C：使用 DeepSeek
获取 API Key 后，设置环境变量：
```bash
export DEEPSEEK_API_KEY=your-api-key-here
```

#### 3. 构建并运行应用

```bash
# 使用 Maven 构建项目
mvn clean install

# 运行应用
mvn spring-boot:run
```

或者直接运行主类 `SpringAiDemoApplication`。

#### 4. 使用 Web 界面（推荐）

应用启动后，直接在浏览器中访问：

```
http://localhost:8080
```

系统会自动跳转到登录/注册页面。首次使用请先注册账号。

#### 注册与登录

1. **注册账号**：
   - 点击"注册"标签页
   - 输入用户名
   - 输入密码（须包含大写字母、小写字母和数字，6-50位）
   - 再次输入密码确认
   - 输入页面显示的5位随机验证码
   - 点击"注册"按钮

2. **登录**：
   - 输入用户名和密码
   - 输入页面显示的5位随机验证码
   - 点击"登录"按钮

> **安全说明**：密码在传输前会通过 RSA 非对称加密算法加密，服务器端使用 BCrypt 算法存储密码哈希，即使数据库泄露也无法还原明文密码。

#### 聊天功能

Web 界面提供以下功能：
- 👤 **用户信息** - 左下角显示当前登录用户名，支持退出登录
- 📝 **会话管理** - 左侧边栏显示当前用户的所有会话，点击切换
- ➕ **新建对话** - 点击"新对话"按钮创建新会话
- 💬 **流式聊天** - SSE 实时流式输出 AI 回复，逐字显示
- 📜 **历史记录** - 自动加载当前会话的历史消息
- 🗑️ **删除会话** - 用户只能删除自己的会话
- 🎨 **Markdown 渲染** - 支持代码高亮、表格、列表等 Markdown 格式
- 🔄 **模型切换** - 侧边栏底部直接切换AI模型
- 📊 **上下文监控** - 实时显示上下文使用率，支持 AI 智能压缩
- 🔔 **AI 回复通知** - 发送消息后可切走，AI 回复完成后通过桌面通知 + 提示音 + 标题徽标提醒

#### RAG 知识库

Web 界面右侧提供知识库面板：
- 📄 **文档上传** - 支持 TXT、PDF、MD 等格式，最大 10MB
- 📋 **文档列表** - 查看已上传的文档及分块数量
- 🗑️ **文档删除** - 删除不需要的文档及其向量数据
- 🔀 **上下文增强** - 开启/关闭 RAG 知识库上下文注入

#### AI 短视频工作室

访问 http://localhost:8080/video-studio 即可使用（需登录）：

完整工作流：**创意想法 → 分镜脚本 → 提示词优化 → 并行生成画面（SSE 实时进度） → 快速预览 / BGM 配乐 / 多比例高清导出**

- 📝 **脚本管理** - 输入创意想法生成多镜头分镜脚本，脚本持久化保存，可随时加载继续编辑、重命名、删除
- 🎬 **镜头编辑** - 画面描述/旁白/提示词/镜头运动/时长全字段编辑，支持添加、删除镜头与拖拽排序
- 🎨 **提示词优化** - 单镜头或批量调用 LLM 将中文场景描述转化为高质量英文图像提示词，内置 8 种视觉风格（电影写实/动漫/水彩/赛博朋克/皮克斯3D/复古/国画/奇幻）
- 🖼️ **并行图片生成** - 云端通义万相与本地 Stable Diffusion / FLUX 一键切换；SSE 实时进度条、失败自动重试、单镜头重新生成
- 🎵 **BGM 配乐** - 内置音乐库按情感分类（轻快/温馨/紧张/史诗等），支持 AI 按脚本主题智能推荐与自定义上传；导出时自动降低音量并淡入淡出
- 📝 **字幕烧录/导出** - 导出时可勾选「烧录字幕」将旁白硬烧录进画面（社媒静音观看友好）；支持单独导出 SRT 文件与一帧字幕效果预览
- 📲 **社媒平台导出** - 平台下拉一键选抖音/B站/小红书等（自动套用规格并提示超时长）；批量三平台（9:16/16:9/1:1）并行导出；一键从导出视频截取封面帧
- 📺 **预览与导出** - 480p 快速预览（带水印）秒级确认效果；720p/1080p 高清导出支持 16:9/9:16/1:1 多比例；导出历史随时重新下载

> **说明**：视频合成依赖 FFmpeg；旁白配音依赖 Edge TTS（`pip install edge-tts`，未安装时仅输出无声视频）；本地图片生成依赖 SD WebUI 兼容服务（默认 `http://127.0.0.1:7860`）。

#### 5. 使用 API 测试

如果你想通过 API 进行测试，请先注册并登录：

#### 获取 RSA 公钥
```bash
curl http://localhost:8080/api/auth/public-key
```

#### 获取验证码
```bash
curl http://localhost:8080/api/auth/captcha
```

#### 注册用户
```bash
# 密码需用 RSA 公钥加密后传输，详见 API_TEST.md
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"<RSA加密后的密码>","confirmPassword":"<RSA加密后的密码>","captcha":"验证码"}'
```

#### 流式聊天（SSE）
```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "your-session-id",
    "message": "你好，请介绍一下自己",
    "ragEnabled": false
  }'
```

#### 发送消息（非流式）
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id",
    "message": "你好，请介绍一下自己"
  }'
```

**详细 API 文档请参考：** [API_TEST.md](API_TEST.md)

## 项目结构

```
spring-ai-demo/
├── src/main/java/com/github/ylyan2015/springaidemo/
│   ├── SpringAiDemoApplication.java    # 应用主类
│   ├── config/
│   │   ├── AiModelConfig.java          # AI模型配置（向量存储双模式切换）
│   │   ├── RsaKeyPairGenerator.java    # RSA密钥对生成器
│   │   ├── SecurityConfig.java         # Spring Security配置
│   │   ├── StorageConfig.java          # 存储服务工厂（local/oss/minio/fastdfs）
│   │   ├── StorageProperties.java      # 存储配置属性
│   │   └── WebMvcConfig.java           # Web MVC配置（本地存储静态资源映射）
│   ├── controller/
│   │   ├── AuthController.java         # 认证控制器（注册/登录/验证码）
│   │   ├── BgmController.java          # 短视频：BGM 列表/推荐/上传
│   │   ├── SubtitleController.java    # 短视频：SRT 导出/字幕预览
│   │   ├── SocialMediaExportController.java # 短视频：平台导出/封面截帧
│   │   ├── ChatController.java         # 聊天控制器（REST API + SSE）
│   │   ├── DocumentController.java     # 知识库文档管理控制器
│   │   ├── ImageController.java        # 图像生成控制器（REST API）
│   │   ├── ImageGenerationController.java  # 短视频：图片生成/SSE 进度/生成器切换
│   │   ├── ImagePromptOptimizerController.java # 短视频：提示词优化
│   │   ├── ModelController.java        # 模型管理控制器（切换/参数预设）
│   │   ├── ModelService.java           # 模型管理服务（动态切换/参数预设）
│   │   ├── PageController.java         # 页面控制器（Web 路由）
│   │   ├── StoryScriptController.java  # 短视频：分镜脚本/镜头增删改/排序
│   │   ├── TimeZoneController.java     # 时区控制器（IP定位）
│   │   └── VideoExportController.java  # 短视频：预览/高清导出/导出历史
│   ├── service/
│   │   ├── AuthService.java            # 认证服务（注册/登录逻辑）
│   │   ├── BgmService.java             # 短视频：BGM 音乐库/LLM推荐/上传
│   │   ├── CaptchaService.java         # 验证码服务
│   │   ├── ChatService.java            # 聊天服务（业务逻辑/上下文压缩）
│   │   ├── ImageService.java           # 图像生成服务（调用DashScope + 转存存储）
│   │   ├── ImageGenerationService.java # 短视频：并行生成 + SSE + 重试
│   │   ├── ImagePromptOptimizerService.java # 短视频：LLM 提示词优化
│   │   ├── RagService.java             # RAG 服务（文档解析/向量化/检索）
│   │   ├── StoryScriptService.java     # 短视频：脚本生成/编辑/排序
│   │   └── VideoExportService.java     # 短视频：预览/高清导出/导出历史
│   ├── entity/
│   │   ├── Bgm.java                    # BGM 实体（短视频模块 D）
│   │   ├── Conversation.java           # 会话实体（绑定userId）
│   │   ├── ImagePromptOptimizer.java   # 提示词优化记录实体（模块 B）
│   │   ├── ImageRecord.java            # 图像生成记录实体
│   │   ├── Message.java                # 消息实体
│   │   ├── ModelParamPreset.java       # 模型参数预设实体
│   │   ├── RagDocument.java            # RAG 文档元信息实体（持久化）
│   │   ├── StoryScript.java / StoryShot.java # 脚本/镜头实体（模块 A）
│   │   ├── User.java                   # 用户实体
│   │   └── VideoExport.java            # 视频导出记录实体（模块 C）
│   ├── exception/
│   │   ├── ImageGenerationException.java # 图像生成异常
│   │   └── StorageException.java         # 存储异常
│   ├── repository/
│       ├── BgmRepository.java          # BGM 数据访问（模块 D）
│       ├── ConversationRepository.java # 会话数据访问
│       ├── ImagePromptOptimizerRepository.java # 提示词优化记录数据访问（模块 B）
│       ├── ImageRecordRepository.java  # 图像生成记录数据访问
│       ├── MessageRepository.java      # 消息数据访问
│       ├── ModelParamPresetRepository.java # 参数预设数据访问
│       ├── RagDocumentRepository.java  # RAG 文档元信息数据访问
│       ├── StoryScriptRepository.java / StoryShotRepository.java # 脚本/镜头数据访问（模块 A）
│       ├── UserRepository.java         # 用户数据访问
│       └── VideoExportRepository.java  # 导出记录数据访问（模块 C）
├── src/main/java/com/github/ylyan2015/springaidemo/video/
│   ├── Storyboard.java / Shot.java    # 分镜 DTO（Phase 1）
│   ├── VisualStyle.java               # 8 种视觉风格预设（含负面提示词）
│   ├── PlatformPreset.java           # 社媒平台预设枚举（抖音/B站/小红书等六平台）
│   ├── ImageGenerationProgress.java   # SSE 进度事件
│   ├── VideoScriptService.java        # 脚本生成（Phase 1）
│   ├── VideoComposeService.java       # FFmpeg 合成 + ComposeOptions（多比例/水印/BGM 混音/字幕烧录）
│   ├── VideoController.java           # 旧一次性流程 API（保留兼容）
│   ├── subtitle/                     # 模块 F：字幕系统
│   │   ├── SubtitleService.java        # SRT/ASS 生成 + xfade 对齐时间轴
│   │   ├── SubtitleStyle.java         # 字幕样式（字体/字号/颜色/描边/位置）
│   │   └── SubtitleCue.java           # 字幕条目（文本 + 起止毫秒）
│   └── generator/                     # 图片生成抽象（策略+工厂）
│       ├── ImageGenerator.java / GeneratedImage.java
│       ├── DashScopeImageGenerator.java  # 云端通义万相
│       ├── LocalSdImageGenerator.java   # 本地 SD WebUI / FLUX
│       └── ImageGeneratorFactory.java   # 配置驱动 + 运行时切换
├── src/main/java/com/github/ylyan2015/springaidemo/dto/
│   ├── ImageGenerateRequest.java       # 图像生成请求DTO
│   └── ImageGenerateResponse.java      # 图像生成响应DTO
├── src/main/java/com/github/ylyan2015/springaidemo/storage/
│   ├── StorageService.java             # 存储服务接口（统一抽象层）
│   ├── LocalStorageService.java        # 本地存储实现
│   ├── OssStorageService.java          # 阿里云OSS存储实现
│   ├── MinioStorageService.java        # MinIO存储实现
│   └── FastDfsStorageService.java      # FastDFS存储实现（骨架代码）
├── src/main/resources/
│   ├── static/
│   │   ├── css/
│   │   │   └── chat.css                # 聊天界面样式
│   │   └── js/
│   │       └── chat.js                 # 前端交互逻辑（SSE/RAG/Markdown/通知）
│   ├── templates/
│   │   ├── index.html                  # 主聊天页面模板
│   │   ├── login.html                  # 登录/注册页面模板
│   │   └── video-studio.html           # AI 短视频工作室前端（完整工作流界面）
│   ├── application.yml                 # 主配置文件
│   ├── application-h2.yml              # H2 数据库配置
│   ├── application-postgresql.yml      # PostgreSQL + PgVector 配置
│   ├── application-ollama.yml          # Ollama 模型配置
│   ├── application-openai.yml          # OpenAI 模型配置
│   ├── application-deepseek.yml        # DeepSeek 模型配置
│   └── application-docker.yml          # Docker 部署配置
├── pom.xml                             # Maven 配置文件
├── Dockerfile                          # Docker 多阶段构建
├── docker-compose.yml                  # docker-compose 服务编排
├── .env.example                        # API Key 环境变量模板
├── .dockerignore                       # Docker 构建排除规则
├── README.md / README-en.md            # 项目说明文档（中/英文）
└── API_TEST.md / API_TEST-en.md        # API测试指南（中/英文）
```

## 用户认证流程

```
注册/登录流程：
1. 前端获取 RSA 公钥（/api/auth/public-key）
2. 前端获取验证码（/api/auth/captcha），显示5位随机字母数字
3. 用户填写表单（注册时需输入2遍密码且一致）
4. 前端使用 RSA 公钥加密密码（Web Crypto API, RSA-OAEP + SHA-256）
5. 前端发送加密后的密码 + 用户名 + 验证码到后端
6. 后端验证验证码 → RSA 解密密码 → 校验密码强度 → BCrypt 加密存储
7. 登录/注册成功后建立 HttpSession 会话
```

## 配置说明

### Web界面模型切换（推荐）

应用启动后，可以在Web界面上直接切换AI模型：

1. 打开 http://localhost:8080
2. 在左侧边栏底部找到"模型"下拉菜单
3. 选择需要的模型：
   - **离线模式** - 使用本地Ollama模型，免费、隐私安全
   - **DeepSeek V3** - 在线专家模式，更强的推理能力
   - **OpenAI GPT** - OpenAI 官方模型，稳定可靠

切换后会立即生效，无需重启应用。切换成功会在右上角显示绿色提示。

### 配置文件模型切换

本项目支持多种AI模型，通过 Spring Profile 轻松切换：

#### 支持的模型

1. **Ollama**（本地模型） - `application-ollama.yml`
   - 模型：qwen2.5:7b-instruct, llama3, mistral, gemma2 等
   - 优势：免费、隐私安全、离线可用

2. **OpenAI** - `application-openai.yml`
   - 模型：gpt-4o-mini, gpt-4, gpt-4-turbo 等
   - 需要 API Key

3. **DeepSeek** - `application-deepseek.yml`
   - 模型：deepseek-chat, deepseek-coder
   - 使用 OpenAI 兼容 API
   - 需要 API Key

#### 切换方式

**方式一：修改 application.yml**

```yaml
spring:
  profiles:
    active: ollama,openai,h2  # 可选: ollama, openai, deepseek + h2 或 postgresql
```

**方式二：启动时指定**

```bash
# 使用 Ollama
mvn spring-boot:run -Dspring-boot.run.profiles=ollama,h2

# 使用 DeepSeek
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek,h2

# 使用 DeepSeek + OpenAI + PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek,openai,postgresql
```

**方式三：环境变量**

```bash
export SPRING_PROFILES_ACTIVE=deepseek,openai,postgresql
mvn spring-boot:run
```

### 数据库模式切换

本项目支持 PostgreSQL 和 H2 两种数据库模式：

```yaml
spring:
  profiles:
    active: deepseek,h2          # H2 轻量数据库（开发/测试）
    # active: deepseek,postgresql  # PostgreSQL（生产环境）
```

### RAG 知识库配置

RAG（检索增强生成）功能支持文档上传和上下文增强，通过以下配置控制：

```yaml
rag:
  embedding-model: ollama    # embedding 提供者: ollama（本地免费）或 openai
  store-type: memory         # 向量存储模式: memory（内存）或 pgvector（持久化）
```

#### RAG 存储模式

| 模式 | 配置值 | 适用场景 | 数据持久性 |
|------|--------|----------|-----------|
| 内存模式 | `memory`（默认） | 开发/测试 | 服务重启后清空 |
| PgVector 持久化 | `pgvector` | 生产环境 | 服务重启后保留 |

**切换到 PgVector 持久化模式：**

1. 确保 PostgreSQL 已安装 pgvector 扩展
2. 激活 `postgresql` profile（自动设置 `rag.store-type: pgvector`）
3. 配置 embedding 维度（默认 768，nomic-embed-text 维度）

```yaml
# application-postgresql.yml 已自动配置
rag:
  store-type: pgvector
  pgvector:
    dimensions: 768
    initialize-schema: true
```

> **Docker 快速启动 PgVector：**
> ```bash
> docker run -it --rm --name postgres -p 5432:5432 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector
> ```

### 模型参数预设

每个用户可以针对每个模型自定义参数（temperature、topP、topK、maxTokens），参数保存在数据库中，切换模型时自动应用。

### H2 配置

H2 使用文件持久化模式，数据保存在 `./data/chatdb.mv.db` 文件中：

- **数据存储位置**：项目根目录下的 `data` 文件夹
- **H2 控制台**：访问 http://localhost:8080/h2-console
- **JDBC URL**：`jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- **用户名**：`sa`
- **密码**：(留空)

### 图像生成配置

通过阿里云百炼 DashScope 通义万相模型生成图像，需配置 API Key：

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key}  # 从阿里云百炼平台获取
```

> **获取 API Key**：登录 [阿里云百炼平台](https://bailian.console.aliyun.com/) → 模型广场 → 通义万相 → 获取 API Key

**图像生成请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `prompt` | string | 是 | - | 图像描述提示词 |
| `size` | string | 否 | `1024x1024` | 图像尺寸（如 1024x1024、720x1280） |
| `n` | int | 否 | `1` | 生成数量（1~4） |
| `quality` | string | 否 | - | 图像质量（标准/高清） |
| `style` | string | 否 | - | 图像风格（写实/插画等） |
| `sessionId` | string | 否 | - | 关联聊天会话ID |

### 存储配置

图像生成后自动转存到配置的存储服务，支持四种存储方式一键切换：

```yaml
storage:
  type: local  # 存储类型：local（本地）/ oss（阿里云OSS）/ minio（MinIO）/ fastdfs（FastDFS）
  local:
    base-path: ./uploads     # 本地文件存储根目录
    access-path: /images     # 本地文件访问路径前缀
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

| 存储类型 | 配置值 | 适用场景 |
|----------|--------|----------|
| 本地存储 | `local`（默认） | 开发/测试，无需外部依赖 |
| 阿里云OSS | `oss` | 生产环境，高可用对象存储 |
| MinIO | `minio` | 自建对象存储，兼容S3 |
| FastDFS | `fastdfs` | 传统分布式文件系统 |

### AI 短视频工作室配置

短视频工作流（脚本 → 图片 → BGM → 导出）的配置集中在 `application.yml`：

```yaml
image:
  generator: dashscope               # 图像生成源：dashscope（云端通义万相）/ local（本地 SD/FLUX）
  local:                               # 本地 Stable Diffusion WebUI / ComfyUI 配置
    base-url: http://127.0.0.1:7860    # 本地服务 API 地址
    model: stable-diffusion            # 本地模型名称
    steps: 25                          # 采样步数
    cfg-scale: 7.0                     # 提示词相关性（CFG）
    timeout: 120                       # 本地生成超时（秒）
  generation:
    parallelism: 3                     # 并行生成数（避免云端 API 限流）
    retry-attempts: 1                 # 失败重试次数
    retry-delay-ms: 2000              # 重试间隔（毫秒）
    total-timeout-seconds: 300        # 批量生成总超时（秒）
    default-size: 1280x720           # 默认图片尺寸（16:9 横屏）

video:
  compose-timeout-minutes: 8        # 单次 FFmpeg 命令超时（分钟）；1080p 多镜头建议 ≥8
  watermark:                        # 社媒导出：品牌水印
    logo-path: ""                    # Logo 角标图片路径（透明底 PNG，建议宽 ≤300px，叠在右下角；留空禁用）
  subtitle:                          # 模块 F：字幕烧录
    font-name: Microsoft YaHei       # 字幕字体（需系统已安装；Linux/Docker 可用 Source Han Sans CN）

bgm:
  library-path: ./data/bgm          # 内置音乐库目录；文件名格式 category__name.mp3
                                    #（如 happy__sunny_morning.mp3），分类：happy/sad/epic/calm/tense，
                                    # 启动时自动扫描注册；用户上传保存在 uploads/ 子目录
```

**关键说明：**

- **视频合成依赖 FFmpeg**：需在 PATH 中（含 ffprobe）；未安装时导出会失败并给出错误提示
- **旁白配音依赖 Edge TTS**：`pip install edge-tts`；未安装时自动跳过配音，仅输出无声视频
- **BGM 音乐库**：将免版权音乐按 `category__name.mp3` 命名放入 `./data/bgm/` 即自动注册，也可通过前端上传按钮添加
- **字幕**：字幕时间轴与 xfade 转场精确对齐；字体需系统已安装（默认微软雅黑，`video.subtitle.font-name` 可改）；烧录使用 FFmpeg subtitles 滤镜（libass，常用构建均已内置）
- **导出比例**：16:9 横屏(B站) / 9:16 竖屏(抖音) / 1:1 方形(小红书)，预览固定 480p + 水印，正式导出 720p/1080p 无水印
- **平台导出**：平台预设自动映射 1080p 分辨率；封面默认截取最近一次成功导出的中间帧；Logo 角标需配置 `video.watermark.logo-path` 后在导出请求中传 `withLogo: true` 生效

### 完整配置示例

#### 示例1：Ollama + H2（本地开发）
```
spring:
  profiles:
    active: ollama,h2
rag:
  store-type: memory
```

#### 示例2：DeepSeek + OpenAI + H2（在线模式开发）
```
spring:
  profiles:
    active: deepseek,openai,h2
```

#### 示例3：DeepSeek + PostgreSQL（生产部署）
```
spring:
  profiles:
    active: deepseek,openai,postgresql
rag:
  store-type: pgvector
  pgvector:
    dimensions: 768
```

#### 示例4：Docker 部署（docker-compose 自动激活）
```
# application-docker.yml 自动包含以下 profile
spring:
  profiles:
    include: postgresql,deepseek,openai
# 无需手动配置，docker compose up 即可使用
```

## API 接口概览

### 认证接口（无需登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/auth/public-key` | GET | 获取 RSA 公钥 |
| `/api/auth/captcha` | GET | 获取5位随机验证码 |
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/logout` | POST | 退出登录 |
| `/api/auth/user` | GET | 获取当前登录用户信息 |

### 聊天接口（需要登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/chat/stream` | POST | 流式发送消息（SSE） |
| `/api/chat/send` | POST | 发送消息（非流式） |
| `/api/chat/send` | GET | 发送消息（兼容旧接口） |
| `/api/chat/conversation` | POST | 创建新会话 |
| `/api/chat/conversations` | GET | 获取当前用户的所有会话 |
| `/api/chat/history/{sessionId}` | GET | 获取对话历史 |
| `/api/chat/conversation/{sessionId}` | DELETE | 删除会话（仅本人） |
| `/api/chat/compress/{sessionId}` | POST | 压缩上下文（AI 摘要） |
| `/api/chat/context-usage/{sessionId}` | GET | 获取上下文使用率 |

### 知识库文档接口（需要登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/documents/upload` | POST | 上传文档到知识库 |
| `/api/documents/list` | GET | 获取所有已上传文档列表 |
| `/api/documents/{docId}` | DELETE | 删除指定文档 |

### 模型管理接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/model/current` | GET | 获取当前模型 |
| `/api/model/available` | GET | 获取可用模型列表 |
| `/api/model/switch` | POST | 切换模型 |
| `/api/model/params` | GET | 获取用户所有模型的参数预设 |
| `/api/model/params/{modelKey}` | GET | 获取某模型的参数预设 |
| `/api/model/params/{modelKey}` | POST | 保存/更新模型参数预设 |

### 时区接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/timezone` | GET | 根据IP获取时区和经纬度 |

### 图像生成接口（需要登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/image/generate` | POST | 生成图像（通义万相wan2.6-t2i + 自动存储） |
| `/api/image/records` | GET | 获取当前用户的所有图像生成记录 |
| `/api/image/records/{sessionId}` | GET | 获取指定会话中的图像生成记录 |

### AI 短视频工作室接口（需要登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/video/scripts/generate` | POST | 输入创意想法生成分镜脚本并持久化 |
| `/api/video/scripts` | GET/POST | 脚本列表 / 保存脚本 |
| `/api/video/scripts/{id}` | GET/PUT/DELETE | 脚本详情（含镜头）/ 重命名 / 删除 |
| `/api/video/scripts/{id}/shots` | PUT/POST | 批量更新镜头 / 添加镜头 |
| `/api/video/scripts/{id}/shots/{shotId}` | PUT/DELETE | 更新镜头 / 删除镜头 |
| `/api/video/scripts/{id}/shots/reorder` | PUT | 镜头拖拽排序 |
| `/api/video/enhance-prompt` | POST | 单个提示词 LLM 优化（8 种风格） |
| `/api/video/enhance-prompt/script/{scriptId}` | POST | 批量优化脚本所有镜头提示词 |
| `/api/video/enhance-prompt/styles` | GET | 视觉风格列表 |
| `/api/video/enhance-prompt/history` | GET | 提示词优化历史 |
| `/api/video/images/shot/{scriptId}/{shotId}` | POST | 单镜头图片生成 |
| `/api/video/images/script/{scriptId}/stream` | GET | SSE 批量并行生成（实时进度） |
| `/api/video/images/generators` | GET | 可用图像生成器列表（含可用性） |
| `/api/video/images/generator` | PUT | 运行时切换图像生成器 |
| `/api/video/export/preview` | POST | 480p 快速预览（带水印，crf 28，可选 BGM/字幕） |
| `/api/video/export/hd` | POST | 高清导出（720p/1080p × 16:9/9:16/1:1，可选 BGM/字幕烧录/Logo） |
| `/api/video/exports/{scriptId}` | GET | 导出历史（含失败原因与封面） |
| `/api/video/bgm` | GET | BGM 列表（内置 + 用户上传） |
| `/api/video/bgm/recommend?theme=` | GET | LLM 按主题智能推荐 BGM |
| `/api/video/bgm/upload` | POST | 上传自定义 BGM（multipart） |
| `/api/video/subtitle/generate` | POST | 生成 SRT 字幕文件（时间轴按转场对齐） |
| `/api/video/subtitle/preview` | POST | 渲染一帧字幕效果预览图（可选样式覆盖） |
| `/api/video/export/platforms` | GET | 社媒平台预设列表（六平台规格） |
| `/api/video/export/platform` | POST | 按平台预设一键导出（抖音/B站/小红书/快手/视频号/YT Shorts） |
| `/api/video/cover/generate` | POST | 从导出视频截取封面帧（默认中间帧，可指定时刻/导出记录） |
| `/api/video/generate` / `/api/video/compose` | POST | 旧一次性流程 API（保留兼容） |

> 视频工作室页面：http://localhost:8080/video-studio （详见 [API_TEST.md](API_TEST.md) 第七节）

## 开发指南

### 多轮对话工作原理

1. **用户登录**：用户注册/登录后建立 Session
2. **会话创建**：每个对话会话有唯一 ID（UUID），并绑定到当前用户
3. **消息存储**：用户消息和 AI 回复都保存到数据库
4. **上下文构建**：发送新消息时，自动获取最近 N 轮对话历史
5. **RAG 增强**：如开启 RAG，自动检索知识库相关片段注入上下文
6. **AI 调用**：将完整对话历史发送给 AI 模型（支持流式/非流式）
7. **结果保存**：AI 回复也保存到数据库，供后续使用

### 上下文管理

- **上下文使用率**：实时监控当前上下文占配额的比例
- **AI 压缩**：当上下文接近满时，自动将较早的对话总结为一条摘要，释放空间
- **可配置窗口**：通过 `chat.max-context-messages` 控制保留的对话轮数

### RAG 工作流程

1. **文档上传**：用户通过 API 或 Web 界面上传文档（TXT/PDF/MD）
2. **解析分块**：Apache Tika 解析文档 → TokenTextSplitter 智能分块
3. **向量化存储**：EmbeddingModel 生成向量 → 存入 VectorStore（内存/PgVector）
4. **相似度检索**：聊天时根据用户问题检索 Top-K 相关片段
5. **上下文注入**：将检索到的片段作为 SystemMessage 注入对话

### Docker 部署架构

`docker-compose.yml` 定义了 3 个服务：

| 服务 | 镜像 | 说明 |
|------|------|------|
| `app` | 本地构建 (Dockerfile) | Spring AI 应用，多阶段构建，JRE 17 Alpine 运行时 |
| `postgres` | `pgvector/pgvector:pg16` | PostgreSQL + pgvector 扩展，RAG 向量持久化 |
| `ollama` | `ollama/ollama` | 本地 AI 模型服务，可选启用 |

服务之间通过内部网络 `ai-net` 通信，应用通过服务名访问中间件（`postgres:5432`、`ollama:11434`）。

- **健康检查**：postgres 配置了 `pg_isready` 健康检查，app 等待数据库就绪后才启动
- **数据持久化**：`pgdata` 和 `ollama-data` 命名卷保证重启不丢数据
- **Profile 控制**：ollama 服务使用 `profiles: [ollama]`，默认不启动，需 `--profile ollama` 显式启用
- **安全运行**：容器以非 root 用户 `appuser` 运行应用

## 常见问题

### 1. 连接 Ollama 失败

确保 Ollama 服务正在运行：
```bash
ollama serve
```

并确认已下载指定的模型：
```bash
ollama pull qwen2.5:7b-instruct
```

### 2. OpenAI/DeepSeek API 调用失败

- 检查 API Key 是否正确设置
- 确认网络连接正常
- 查看账户余额是否充足

### 3. 端口冲突

如果 8080 端口被占用，可以在 `application.yml` 中修改端口号。

### 4. AI 不记得之前的对话

确保你使用了相同的 `sessionId`。如果不传 sessionId，每次都会创建新会话。

### 5. 如何查看数据库中的数据？

#### H2 数据库

访问 H2 控制台：http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

#### PostgreSQL 数据库

使用任何 PostgreSQL 客户端工具连接：
- Host: `localhost`, Port: `5432`, Database: `chatdb`

### 6. RAG 上传文档失败

- 确认文件大小不超过 10MB
- 确认 Ollama embedding 模型已下载：`ollama pull nomic-embed-text`
- 或使用 OpenAI embedding：设置 `rag.embedding-model: openai`

### 7. 忘记密码怎么办？

当前版本暂不支持密码重置功能。可以直接在数据库中删除用户记录后重新注册：
```sql
DELETE FROM users WHERE username = 'your-username';
```

### 8. Docker 构建缓慢

首次构建需要下载 Maven 依赖，后续构建利用 Docker 层缓存（`dependency:go-offline`），速度会快很多。如果网络较慢，可配置 Maven 国内镜像，在 `Dockerfile` 构建阶段添加：
```dockerfile
COPY settings.xml /root/.m2/settings.xml
```

### 9. Docker 容器无法连接外网 API

确保 `.env` 文件中的 API Key 已正确配置，且 Docker 宿主机网络正常：
```bash
cat .env  # 确认 API Key 非占位符
```

### 10. 图像生成失败怎么办？

- 确认已正确配置 `DASHSCOPE_API_KEY`（在 `.env` 或 `application.yml` 中）
- 确认阿里云百炼账户有通义万相的调用权限和余额
- 确认网络可以访问 `dashscope.aliyuncs.com`
- 检查应用日志中是否有具体的错误信息

### 11. 生成的图片存储在哪里？

默认存储在本地 `./uploads/` 目录下，按日期分文件夹存储。可通过 `storage.type` 配置切换为
阿里云OSS、MinIO 或 FastDFS。本地存储的图片通过静态资源映射 `/images/**` 访问。

### 12. 如何查看图像生成历史记录？

- API：`GET /api/image/records` 获取当前用户的所有历史记录
- 数据库：`SELECT * FROM image_records WHERE user_id = ? ORDER BY create_time DESC`

### 13. 短视频工作室如何添加内置 BGM？

将免版权音乐文件按 `分类__名称.mp3` 命名放入 `./data/bgm/` 目录（如 `happy__sunny_morning.mp3`），
支持的分类：`happy`（轻快）/ `sad`（忧伤）/ `epic`（史诗）/ `calm`（舒缓）/ `tense`（紧张）；
启动时自动扫描注册。也可以在短视频工作室页面点击「上传」按钮添加自定义 BGM（支持 mp3/wav/ogg/m4a/flac）。

### 14. 短视频导出失败提示 FFmpeg 相关错误？

- 确认 `ffmpeg` 和 `ffprobe` 已安装且在系统 PATH 中：`ffmpeg -version`
- 多镜头合成较慢属于正常现象（1080p 可能需要几分钟），超时上限由 `video.compose-timeout-minutes` 控制（默认 8 分钟）
- BGM 混音需 ffmpeg ≥ 4.4（`amix normalize` 参数），老版本请升级

### 15. 如何使用本地 Stable Diffusion / FLUX 生成短视频画面？

1. 启动本地 SD WebUI（或 ComfyUI 等兼容服务），确认可访问 `http://127.0.0.1:7860`
2. 修改配置 `image.generator: local`，或在前端「图像生成器」下拉中运行时切换
3. 本地生成原生支持负面提示词（Negative Prompt），画面风格更可控且无 API 成本

### 16. 字幕烧录后乱码或字体不对？

- 字幕默认使用微软雅黑（`Microsoft YaHei`），需系统已安装该字体；Linux/Docker 环境可改为已安装的中文字体：
  `video.subtitle.font-name: Source Han Sans CN`（或 Noto Sans CJK SC）
- 烧录使用 FFmpeg subtitles 滤镜（libass），常用发行版构建均已内置；如报错无 libass 请更换完整版 FFmpeg
- 也可通过 `POST /api/video/subtitle/preview` 传 `style` 字段自定义字体/字号/颜色/描边/位置后预览确认

### 17. 如何启用品牌 Logo 角标与封面图？

- **Logo**：将透明底 PNG（建议宽 ≤300px）放到本地路径，配置 `video.watermark.logo-path: D:/assets/logo.png`，
  然后在前端勾选「Logo」或导出请求中传 `"withLogo": true`；未配置时自动跳过不报错
- **封面**：导出成功后（前端结果区或导出历史）点「封面」即可从该视频截帧；默认取中间帧，
  也可调 `POST /api/video/cover/generate` 传 `timeOffset`（秒）指定时刻、`exportId` 指定某次导出；封面地址会回写进导出记录

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Spring Security 官方文档](https://spring.io/projects/spring-security)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

ylyan2015
