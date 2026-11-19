# Spring AI Demo - 多模型智能聊天应用

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，支持多种AI模型（Ollama、OpenAI、DeepSeek等），内置用户注册登录系统、RAG 知识库、SSE 流式输出、AI 图像生成（通义万相）、多存储服务、**AI 短视频工作室 / 漫剧流水线**（创意想法 → 分镜脚本 → 角色一致性出图 → 真图生视频 → 多声线配音 → BGM/音效 → FFmpeg 合成多比例导出）、**评论工作台**（素材拼接 + 版权护栏）与**参考素材库**（booru 网搜 / LLM 匹配 / IP-Adapter 参考图）、Docker 一键部署等功能。

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
- ✅ **Spring Boot Actuator** - 内置健康检查等运维端点（安全加固后仅对外暴露 health/info）
- ✅ **Docker 一键部署** - 多阶段构建镜像 + docker-compose 编排应用和中间件，开箱即用
- ✅ **AI 回复完成通知** - 浏览器桌面通知 + 提示音 + 页面标题徽标，切走页面也不错过回复
- ✅ **AI 图像生成** - 集成阿里云百炼通义万相模型（wan2.6-t2i），通过提示词生成高品质图片
- ✅ **多存储支持** - 统一存储抽象层，支持本地存储 / 阿里云OSS / MinIO / FastDFS 一键切换
- ✅ **图像生成记录** - 生成记录持久化存储，关联用户与聊天会话，支持历史查询
- ✅ **AI 短视频工作室** - 创意想法 → LLM 生成分镜脚本（可编辑/拖拽排序）→ 提示词 LLM 优化（8 种风格）→ 并行生成画面（SSE 实时进度）→ 480p 快速预览 / 720p/1080p 高清导出
- ✅ **多比例视频导出** - 16:9 横屏(B站) / 9:16 竖屏(抖音) / 1:1 方形(小红书)，预览版带水印、正式版无水印，导出历史可追溯
- ✅ **社媒一键导出** - 抖音/B站/小红书/快手/视频号/YT Shorts 六大平台预设（比例+分辨率+推荐时长校验）、封面帧截取、品牌 Logo 角标、批量三平台导出 + ZIP 打包下载
- ✅ **BGM 配乐** - 内置音乐库（按情感分类，预置 5 首合成免版权音乐）+ LLM 智能推荐 + 自定义上传；自动降音量、淡入淡出、短曲循环
- ✅ **字幕系统** - 旁白自动生成带时间轴字幕（时间轴与转场精确对齐），支持 SRT 单独导出与 ASS 烧录进画面，样式可定制（字体/字号/颜色/描边/位置），多语言字幕（中文/English/中英双语，LLM 翻译）
- ✅ **视频模板系统** - 8 个内置风格模板（产品展示/故事讲述/知识科普/风景旅拍/美食制作/情感语录/科技数码/运动健身），一键应用生成对应风格脚本，支持另存自定义模板
- ✅ **多图像生成后端** - 八源配置或 UI 切换：DashScope 通义万相（默认）/ 本地 SD/FLUX（SD WebUI 协议）/ 智谱 CogView / 硅基流动 / OpenAI / 火山方舟 Seedream / wan2.7 参考图一致性 / 本地 ComfyUI SD1.5+LoRA+IP-Adapter，失败自动重试
- ✅ **角色卡与一致性** - LLM 先人设后分镜自动产出角色卡（名字/英文特征表/性格/声线/LoRA/触发词），定妆参考图上传 + 脚本级画风锁；云端 wan2.7（≤9 参考图）或本地 IP-Adapter（weight 0.7）锁定角色长相
- ✅ **真图生视频（i2v）** - 云端 wan2.7-i2v / 本地 ComfyUI Wan2.2（零 API 成本、素材不外传）双路线；脚本级连续性 NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME，真片段+静图运镜混合合成
- ✅ **抽卡候选与局部重绘** - 每镜图 N=3 / 视频 N=2 候选按质量评分排序，人工择优回写主资产；wan2.7 框选 inpaint 产物入候选不覆盖主图
- ✅ **多声线配音与后期增强** - 对白用角色卡声线、旁白纪录片男声（语速可配）；SFX 分层混音（人声 100%/BGM 15%/SFX 30%）；Real-ESRGAN 超分 + RIFE 补帧可选勾选
- ✅ **视频转绘** - 实拍视频 → wan2.7-videoedit 日系动漫/美漫/水彩风格转绘，独立异步任务不触碰主流水线
- ✅ **参考素材库** - 上传/目录扫描（char_/scene_ 命名约定）/ booru 双源网搜（三道安全闸 + 自家存储转存 + sourceNote 出处可追溯）；LLM 自动匹配推荐，镜头绑定参考图后由 IP-Adapter/wan2.7 消费（每镜前 2 张、人物优先）
- ✅ **评论工作台** - /commentary-studio 独立页面，format 分离脚本管理，素材切片拼接 + TTS 预探测时长对齐 + 出处水印 + 引用占比 ≤20% 版权护栏 + 异步高清导出
- ✅ **尾卡镜** - endCard=true 跳过生图，本地 FFmpeg 纯色底+压字渲染（中文 fontfile），零成本收尾 CTA

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
5. **（可选）FFmpeg ≥ 4.3** - 如需 AI 短视频工作室的视频合成/导出功能（含 ffprobe）。默认裸名走系统 PATH，可通过 `video.ffmpeg-path` / `video.ffprobe-path` 配置或 `FFMPEG_PATH` / `FFPROBE_PATH` 环境变量指定绝对路径；xfade 转场需 ≥ 4.3，BGM 混音需 ≥ 4.4
6. **（可选）Edge TTS** - 如需短视频旁白配音：`pip install edge-tts`（未安装时自动跳过配音）；Python 解释器路径由 `video.python-command` 指定（支持 `PYTHON_CMD` 环境变量覆盖）
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
   - 输入图片验证码（4位字母数字，点击可刷新）
   - 点击"注册"按钮

2. **登录**：
   - 输入用户名和密码
   - 输入图片验证码（4位字母数字，点击可刷新）
   - 点击"登录"按钮

> **安全说明**：密码在传输前会通过 RSA 非对称加密算法加密，服务器端使用 BCrypt 算法存储密码哈希，即使数据库泄露也无法还原明文密码。验证码答案仅存 Session 不随接口返回；登录限速同用户名+IP 60秒内最多5次失败（超限 429）；登录成功后自动更换会话 ID（防会话固定）。

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

- 📝 **脚本管理** - 输入创意想法生成多镜头分镜脚本，脚本持久化保存，可随时加载继续编辑、重命名、删除；可选视频模板（8 个内置风格 + 自定义）一键生成对应风格脚本，脚本可另存为模板
- 🎬 **镜头编辑** - 画面描述/旁白/提示词/镜头运动/时长全字段编辑，支持添加、删除镜头与拖拽排序；镜头运动真实生效（固定=纯静态、推进/推近=zoom in、拉远=zoom out、平移=水平 pan），zoompan 超采样消除逐帧截断抖动
- 🎨 **提示词优化** - 单镜头或批量调用 LLM 将中文场景描述转化为高质量英文图像提示词，内置 8 种视觉风格（电影写实/动漫/水彩/赛博朋克/皮克斯3D/复古/国画/奇幻）
- 🖼️ **并行图片生成** - 八源一键切换：通义万相（默认）/ 本地 SD/FLUX / 智谱 CogView / 硅基流动 / OpenAI / 火山方舟 Seedream（中间四家走通用 OpenAI 兼容实现，本地模型自动套用 SD1.5/SDXL/FLUX 风格预设）/ wan2.7 参考图一致性 / 本地 ComfyUI SD1.5+LoRA+IP-Adapter；SSE 实时进度条、失败自动重试、单镜头重新生成
- ✅ **图片质量评估**（可选）- 多模态 LLM 按清晰度/相关性/美学三维打分（0-10），低分自动重新生成（≤2 次，保留最高分）；未配多模态 API 时降级为规则评估；镜头卡片显示质量徽章（`image.quality.enabled` 开启，默认关闭）
- 🤖 **Agent 工具化**（Phase 3 切片1）- 六项核心能力（脚本生成/提示词优化/图片生成/质量评估/视频导出/BGM 推荐）已包装为 Spring AI 工具（FunctionToolCallback），提供工具清单内省端点，为导演 Agent 编排奠基
- 🎬 **AI 导演对话**（Phase 3 切片2）- ChatClient 挂载六工具自动 ReAct 编排（思考→调用工具→观察→继续），工作室页面内置对话面板；导出视频设人工确认闸门（前端确认按钮/请求 confirmedTools 显式放行）；对话历史按用户+会话内存隔离（上限 20 条，重启清空）
- 🎭 **多 Agent 编排**（Phase 3 切片3）- 编剧/美术/音频/剪辑四专家 Agent（各自 System Prompt + 工具子集）以「专家即工具」委托给导演；「一键流水线」异步编排（提交→轮询→确认续跑），专用线程池显式传播 SecurityContext；任务内存态上限 100、按用户归属校验
- 🧑‍🎨 **角色卡与一致性出图**（Phase 4）- 脚本生成自动产出角色卡（名字/英文特征表/性格/声线/LoRA/触发词），支持定妆参考图上传与脚本级画风锁；wan2.7 生成器吃 ≤9 张参考图，本地 ComfyUI 走 IP-Adapter（weight 0.7）/ LoRA / ControlNet 模板路由
- 🎞️ **真图生视频**（Phase 4/5）- 云端 wan2.7-i2v 或本地 ComfyUI Wan2.2（零成本）；连续性模式让上一镜尾帧链接下一镜首帧；真片段用 ffprobe 实测时长参与混合合成
- 🎲 **抽卡候选与局部重绘**（Phase 4）- 每镜多候选对比模态人工择优；框选 inpaint 修手修脸，产物入候选
- 🎙️ **多声线配音与后期**（Phase 4）- 对白按角色卡声线逐行 TTS、旁白纪录片男声（语速可配）；导出可勾选音效/超分/补帧三增强
- 🖌️ **视频转绘面板**（Phase 4）- 实拍 2-10s 片段上传 → 动漫/美漫/水彩转绘，3s 轮询独立任务
- 🗂️ **参考素材库面板**（2026-11-17）- 上传/扫描入库（CHARACTER/SCENE/INSERT 分类）、booru 网搜（英文 tag 佳，多 tag 用 `+` 连接 AND；空格转下划线是单 tag 内分词）、勾选入库（自家存储+出处）、LLM 匹配推荐 chip 一键采用、镜头绑定参考图（生成时每镜消费前 2 张、人物优先；仅 comfyui/wan27 生成器吃参考图）
- 🔚 **尾卡镜**（2026-11-17）- 镜头勾选 endCard 后跳过生图，本地渲染纯色底+压字（关注/下集预告等 CTA），字体由 `video.endcard.font` 配置
- 🎵 **BGM 配乐** - 内置音乐库按情感分类（轻快/温馨/紧张/史诗等，预置 5 首合成免版权音乐），支持 AI 按脚本主题智能推荐与自定义上传；导出时自动降低音量并淡入淡出
- 📝 **字幕烧录/导出** - 导出时可勾选「烧录字幕」将旁白硬烧录进画面（社媒静音观看友好）；字幕语言可选中文/English/中英双语（LLM 翻译，配音仍为中文）；支持单独导出 SRT 文件与一帧字幕效果预览
- 📲 **社媒平台导出** - 平台下拉一键选抖音/B站/小红书等（自动套用规格并提示超时长）；批量三平台（9:16/16:9/1:1）并行导出，支持 ZIP 打包下载（含封面 + 清单）；一键从导出视频截取封面帧
- 📺 **预览与导出** - 480p 快速预览（带水印）秒级确认效果；720p/1080p 高清导出支持 16:9/9:16/1:1 多比例；导出历史随时重新下载

> **说明**：视频合成依赖 FFmpeg（默认裸名走系统 PATH，可用 `video.ffmpeg-path` / `video.ffprobe-path` 或环境变量 `FFMPEG_PATH` / `FFPROBE_PATH` 指定绝对路径）；旁白配音依赖 Edge TTS（`pip install edge-tts`，未安装时仅输出无声视频；解释器由 `video.python-command` / `PYTHON_CMD` 指定）；本地图片生成支持 SD WebUI 兼容服务（默认 `http://127.0.0.1:7860`）与原生 ComfyUI 工作流协议（默认 `http://127.0.0.1:8188`，需按 docs/comfyui/ 模板校准）两条路线；评论杂谈类创作请使用独立入口 http://localhost:8080/commentary-studio（评论工作台）。预置 BGM 为程序合成的免版权和弦垫音，可用 `tools/generate-preset-bgm.ps1` 重新生成，或将真实免版权音乐按 `category__name.mp3` 命名放入 `data/bgm/` 替换。

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
│   │   ├── WebMvcConfig.java           # Web MVC配置（本地存储静态资源映射）
│   │   ├── ImageGeneratorConfig.java   # 图像生成器装配（八源 + wan27/comfyui 参考图能力）
│   │   ├── VideoGeneratorConfig.java   # 视频生成器装配（none/dashscope-wan/comfyui）
│   │   └── BooruConfig.java            # booru 网搜 HttpClient（禁重定向）与源注册
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
│   │   ├── VideoExportController.java  # 短视频：预览/高清导出/导出历史/ZIP打包
│   │   ├── VideoTemplateController.java # 短视频：视频模板列表/应用/自定义（模块 H）
│   │   ├── VideoAgentController.java   # 短视频：Agent 内省/导演对话/多 Agent 编排（Phase 3 切片1/2/3）
│   │   ├── StoryCharacterController.java # 短视频：角色卡 CRUD/定妆参考图/声线列表（Phase 4）
│   │   ├── ShotCandidateController.java # 短视频：抽卡候选/择优/局部重绘（Phase 4）
│   │   ├── VideoGenerationController.java # 短视频：真 i2v 单镜/SSE/生成器切换（Phase 4/5）
│   │   ├── VideoRestyleController.java # 短视频：视频转绘独立任务（Phase 4）
│   │   ├── MediaAssetController.java   # 短视频：素材库上传/扫描/列表/删除（Track B + 素材库切片）
│   │   ├── BooruAssetController.java   # 短视频：booru 网搜/预览代理/入库（素材库切片）
│   │   └── AssetMatchController.java   # 短视频：LLM 素材匹配推荐（素材库切片）
│   ├── service/
│   │   ├── AuthService.java            # 认证服务（注册/登录逻辑）
│   │   ├── BgmService.java             # 短视频：BGM 音乐库/LLM推荐/上传
│   │   ├── CaptchaService.java         # 验证码服务
│   │   ├── ChatService.java            # 聊天服务（业务逻辑/上下文压缩）
│   │   ├── ImageService.java           # 图像生成服务（调用DashScope + 转存存储）
│   │   ├── ImageGenerationService.java # 短视频：并行生成 + SSE + 重试 + 质量评估重试
│   │   ├── ImagePromptOptimizerService.java # 短视频：LLM 提示词优化
│   │   ├── RagService.java             # RAG 服务（文档解析/向量化/检索）
│   │   ├── StoryScriptService.java     # 短视频：脚本生成/编辑/排序
│   │   ├── VideoExportService.java     # 短视频：预览/高清导出/导出历史/ZIP打包/异步导出
│   │   ├── VideoTemplateService.java   # 短视频：模板预置/应用/自定义（模块 H）
│   │   ├── StoryCharacterService.java  # 短视频：角色卡/定妆参考图/声线（Phase 4）
│   │   ├── ShotCandidateService.java / CandidateGenerationService.java # 短视频：候选择优/抽卡生成（Phase 4）
│   │   ├── VideoGenerationService.java # 短视频：真 i2v 编排/连续性/尾帧回写（Phase 4/5）
│   │   ├── VideoRestyleService.java    # 短视频：转绘异步任务（Phase 4）
│   │   ├── SfxService.java             # 短视频：音效库/LLM 推荐/分层混音（Phase 4）
│   │   ├── MediaAssetService.java      # 短视频：素材库上传/裁剪/双目录扫描（Track B + 素材库）
│   │   ├── BooruSearchService.java     # 短视频：booru 三道闸/预览代理/转存入库（素材库）
│   │   ├── AssetMatchService.java      # 短视频：LLM 素材匹配 + 关键词降级（素材库）
│   │   └── LoginRateLimiter.java       # 登录限流（5 次/60s 按用户名+IP）
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
│   │   ├── VideoExport.java            # 视频导出记录实体（模块 C）
│   │   ├── VideoTemplate.java          # 视频模板实体（模块 H）
│   │   ├── StoryCharacter.java         # 角色卡实体（声线/LoRA/触发词/定妆图，Phase 4）
│   │   ├── ShotCandidate.java          # 抽卡候选实体（Phase 4）
│   │   ├── RestyleTask.java            # 视频转绘任务实体（Phase 4）
│   │   ├── SfxTrack.java               # 音效轨实体（Phase 4）
│   │   └── MediaAsset.java             # 素材库实体（kind/category/sourceNote，Track B + 素材库）
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
│       ├── VideoExportRepository.java  # 导出记录数据访问（模块 C）
│       └── VideoTemplateRepository.java # 视频模板数据访问（模块 H）
├── src/main/java/com/github/ylyan2015/springaidemo/video/
│   ├── Storyboard.java / Shot.java    # 分镜 DTO（Phase 1；Shot 含对白/景别/endCard/参考素材字段）
│   ├── VisualStyle.java               # 8 种视觉风格预设（含负面提示词）
│   ├── PlatformPreset.java           # 社媒平台预设枚举（抖音/B站/小红书等六平台）
│   ├── ImageGenerationProgress.java   # SSE 进度事件
│   ├── CharacterDesign.java / TtsLine.java # 角色设计 DTO / TTS 逐行合成 DTO（Phase 4）
│   ├── ShotPromptAssembler.java       # 提示词唯一装配点（画风锁/景别/角色特征/触发词，Phase 4/5）
│   ├── VideoScriptService.java        # 脚本生成（Phase 1；先人设后分镜）
│   ├── VideoComposeService.java       # FFmpeg 合成 + ComposeOptions（多比例/水印/BGM 混音/字幕烧录）
│   ├── subtitle/                     # 模块 F：字幕系统
│   │   ├── SubtitleService.java        # SRT/ASS 生成 + xfade 对齐时间轴 + 多语言翻译
│   │   ├── SubtitleStyle.java         # 字幕样式（字体/字号/颜色/描边/位置）
│   │   └── SubtitleCue.java           # 字幕条目（文本 + 起止毫秒）
│   ├── quality/                      # 模块 J：图片质量评估
│   │   ├── ImageQualityService.java   # 多模态评分 + 规则降级
│   │   └── ImageQualityScore.java     # 评分 DTO（三维 0-10 + 综合均值）
│   ├── agent/                        # Phase 3 切片1/2/3：工具化 + 导演 + 多 Agent 编排
│   │   ├── AgentToolRequests.java     # 工具输入 record（@ToolParam 描述，含 DelegateTask）
│   │   ├── VideoAgentToolFactory.java # FunctionToolCallback 包装现有服务
│   │   ├── ConfirmationGateToolCallback.java # 人工确认闸门（exportVideo 未确认时拦截）
│   │   ├── DirectorAgentService.java  # 导演 Agent（ChatClient 挂工具 ReAct + 会话历史）
│   │   ├── SpecialistAgentFactory.java # 四专家 Agent + delegateToXxx 委托工具（切片3）
│   │   └── AgentOrchestrationService.java # 异步编排（SecurityContext 传播 + 任务表 + 确认续跑）
│   └── generator/                     # 图片/视频生成抽象（策略+工厂）
│       ├── ImageGenerator.java / GeneratedImage.java / VideoGenerator.java / GeneratedVideo.java
│       ├── DashScopeImageGenerator.java  # 云端通义万相
│       ├── Wan27ImageGenerator.java   # wan2.7 参考图一致性/框选重绘（Phase 4）
│       ├── OpenAiCompatibleImageGenerator.java # 智谱/硅基流动/OpenAI/火山四家通用实现
│       ├── LocalSdImageGenerator.java   # 本地 SD WebUI / FLUX
│       ├── LocalModelStylePreset.java   # SD1.5/SDXL/FLUX 风格预设（模块 G）
│       ├── ComfyUiClient.java / ComfyUiImageGenerator.java # 本地 ComfyUI SD1.5+LoRA+IP-Adapter（Phase 5）
│       ├── ComfyUiVideoGenerator.java / DashScopeWanVideoGenerator.java / NoneVideoGenerator.java # i2v 三路线
│       ├── EndCardImageRenderer.java    # 尾卡镜本地压字渲染（2026-11-17）
│       ├── ReferenceImageResolver.java / LoraResolver.java / LoraSpec.java # 参考图回退链/LoRA 路由
│       ├── BooruProvider.java / SafebooruOrgProvider.java / DanbooruSafeProvider.java # booru 双源适配
│       ├── ImageSizeMapper.java         # 宽高比→各家尺寸枚举映射
│       └── ImageGeneratorFactory.java / VideoGeneratorFactory.java # 配置驱动 + 运行时切换
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
│   │   ├── video-studio.html           # AI 短视频工作室前端（完整工作流 + 素材库/角色卡/候选/转绘面板）
│   │   └── commentary-studio.html      # 评论工作台前端（Track B：素材拼接/出处水印/引用占比徽章）
│   ├── application.yml                 # 主配置文件
│   ├── application-h2.yml              # H2 数据库配置
│   ├── application-postgresql.yml      # PostgreSQL + PgVector 配置
│   ├── application-ollama.yml          # Ollama 模型配置
│   ├── application-openai.yml          # OpenAI 模型配置
│   ├── application-deepseek.yml        # DeepSeek 模型配置
│   └── application-docker.yml          # Docker 部署配置
├── pom.xml                             # Maven 配置文件
├── tools/generate-preset-bgm.ps1       # 预置 BGM 素材生成脚本（FFmpeg 合成免版权音乐）
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
2. 前端获取验证码图片（/api/auth/captcha），显示4位字母数字干扰线图片（答案仅存 Session）
3. 用户填写表单（注册时需输入2遍密码且一致）
4. 前端使用 RSA 公钥加密密码（Web Crypto API, RSA-OAEP + SHA-256）
5. 前端发送加密后的密码 + 用户名 + 验证码到后端
6. 后端验证验证码 → RSA 解密密码 → 校验密码强度 → BCrypt 加密存储
7. 登录/注册成功后建立 HttpSession 会话（自动更换会话 ID 防固定；同用户名+IP 60秒内最多5次失败）
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
  embedding-model: ollama    # embedding 提供者: ollama（本地免费）/ openai / dashscope（通义云端）；配置+重启生效
  # 云端向量化（embedding-model=dashscope）：DeepSeek 官方不提供 embedding API，
  # 在线模式不想依赖本地 Ollama 时用 dashscope（复用 DASHSCOPE_API_KEY）；
  # 不同提供者向量语义空间不兼容，切换后需清空旧向量重新上传文档
  dashscope:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v4
    dimensions: 768          # 须与 rag.pgvector.dimensions 一致（pgvector 模式）
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
3. 配置 embedding 维度（默认 768：nomic-embed-text 与 dashscope text-embedding-v4；openai text-embedding-3-small 为 1536 需同步调整）

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
- **H2 控制台**：默认关闭（安全加固）；本地调试可在 `application-h2.yml` 手动开启 `spring.h2.console.enabled: true` 后访问 http://localhost:8080/h2-console，切勿在生产开启
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
  generator: dashscope               # 图像生成源：dashscope（默认）/ local / zhipu / siliconflow / openai / ark
  # OpenAI 兼容 images 协议四家（通用实现）：Key 经环境变量注入，空 Key 前端显示不可用；
  # 负面词自动拼接进 prompt（协议无 negative 参数）；尺寸按宽高比映射到各家枚举（fixed-sizes 留空=透传）；
  # 响应 url/b64_json 双格式自适应，临时链接自动下载转存到存储服务
  compat:
    zhipu:
      api-key: ${ZHIPU_API_KEY:}        # 智谱 BigModel；cogview-3-flash 免费档
      model: cogview-3-flash
    siliconflow:
      api-key: ${SILICONFLOW_API_KEY:}  # 硅基流动；Kolors 免费档 / FLUX.1-schnell
      model: Kwai-Kolors/Kolors
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: gpt-image-1
    ark:
      api-key: ${ARK_API_KEY:}          # 火山方舟；Seedream 中文语义/文字渲染强
      model: doubao-seedream-4-0-250828
  local:                               # 本地 Stable Diffusion WebUI / ComfyUI 配置
    base-url: http://127.0.0.1:7860    # 本地服务 API 地址
    model: stable-diffusion            # 本地模型名称
    steps: 25                          # 采样步数
    cfg-scale: 7.0                     # 提示词相关性（CFG）
    timeout: 120                       # 本地生成超时（秒）
    style-prefix: ""                   # 风格前缀覆盖（留空时按模型名自动套用内置预设：SD1.5/SDXL/FLUX）
    extra-negative-prompt: ""          # 负面提示词覆盖（留空时按模型名自动套用内置预设）
  wan27:                               # Phase 4：wan2.7 参考图一致性出图（复用 DASHSCOPE_API_KEY，空 Key 不可用）
    model: wan2.7-image                # ≤9 张参考图；框选 inpaint 同模型
    timeout-seconds: 120
  comfyui:                             # Phase 5：本地 ComfyUI SD1.5+LoRA+IP-Adapter 分镜（零云，实测 ~8s/张）
    workflow-plain: docs/comfyui/sd15_storyboard.template.json          # 无 LoRA 角色纯 prompt 模板
    workflow-lora: docs/comfyui/sd15_lora_storyboard.template.json      # 角色 LoRA 模板
    workflow-canny: docs/comfyui/sd15_lora_canny.template.json          # ControlNet 锁机位（gated）
    workflow-ipadapter: docs/comfyui/sd15_ipadapter.template.json       # 参考图模板（IP-Adapter plus_sd15 + ViT-H，需本机校准）
    workflow-ipadapter-lora: docs/comfyui/sd15_ipadapter_lora.template.json  # IP-Adapter+LoRA 组合（需手工校准，未就位自动降级）
    ipadapter-weight: 0.7              # 参考强度甜点区 0.65-0.85；过高会把参考图抄成贴图
    controlnet-enabled: false          # 开启后定妆参考图经 canny 锁机位（+~10s/张）
    timeout-seconds: 120               # base-url 复用 video.comfyui.base-url
    poll-interval-ms: 1000
  generation:
    parallelism: 3                     # 并行生成数（避免云端 API 限流）
    retry-attempts: 1                 # 失败重试次数
    retry-delay-ms: 2000              # 重试间隔（毫秒）
    total-timeout-seconds: 300        # 批量生成总超时（秒）
    default-size: 1280x720           # 默认图片尺寸（16:9 横屏）
  quality:                             # 模块 J：图片质量评估（默认关闭）
    enabled: false                     # 开启后低分图片自动重新生成（关闭时生成流程不变）
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI 兼容多模态端点
    api-key: ${DASHSCOPE_API_KEY:}     # 多模态 API Key（留空时降级为规则评估）
    model: qwen-vl-max                 # 多模态评估模型
    threshold: 6.0                     # 及格阈值（0-10）
    max-retries: 2                     # 低分最大重试次数（保留最高分一张）

video:
  ffmpeg-path: ${FFMPEG_PATH:ffmpeg}   # FFmpeg 可执行文件路径（默认裸名走 PATH，不在 PATH 时写绝对路径；需 ≥4.3）
  ffprobe-path: ${FFPROBE_PATH:ffprobe} # ffprobe 路径（BGM 时长探测用）
  python-command: ${PYTHON_CMD:python}  # 运行 edge-tts 的 Python 解释器（Windows 建议绝对路径，避开微软商店别名桩）
  compose-timeout-minutes: 8        # 单次 FFmpeg 命令超时（分钟）；1080p 多镜头建议 ≥8
  motion:
    zoom-amount: 0.06                # 运镜缩放幅度；越小画面越稳
    supersample-factor: 4            # zoompan 超采样倍数上限（消抖）；自适应封顶中间长边≤3840：1080p→2×/720p→3×/480p→4×；1=关闭
  watermark:                        # 社媒导出：品牌水印
    logo-path: ""                    # Logo 角标图片路径（透明底 PNG，建议宽 ≤300px，叠在右下角；留空禁用）
  subtitle:                          # 模块 F：字幕烧录
    font-name: Microsoft YaHei       # 字幕字体（需系统已安装；Linux/Docker 可用 Source Han Sans CN）
  generator: none                    # 真 i2v 源：none（默认静图运镜）/ dashscope-wan（云端）/ comfyui（本地零成本）
  workers: 2                         # i2v 并行线程；comfyui 本地路线建议 1（单卡串行队列）
  retry-attempts: 1                  # i2v 失败重试（按秒计费，成本红线）
  candidates:                        # Phase 4：抽卡候选数（成本敏感可降 1；一键编排不抽卡）
    image-count: 3
    video-count: 2
  wan:                               # 云端 wan2.7-i2v（复用 DASHSCOPE_API_KEY）
    model: wan2.7-i2v                # 首尾帧；wan2.6-i2v 仅首帧
    resolution: 720P                 # 成本基线：720P/5s/中低运动幅度
    timeout-seconds: 600
    poll-interval-ms: 5000
  comfyui:                           # Phase 5：本地 ComfyUI Wan2.2 i2v（零 API 成本、素材不外传）
    base-url: http://127.0.0.1:8188
    workflow-path: docs/comfyui/wan22_i2v_5b.template.json  # 占位符模板；从 ComfyUI 导出校准后覆盖（COMFYUI_WORKFLOW）
    fps: 24                          # 帧数自动对齐 4n+1；720P→1280×704 / 480P→832×480
    timeout-seconds: 2700            # 8-9s 长镜单卡可达 ~30min；短切 1.5-2.5s 为主力
    poll-interval-ms: 3000
  tts:                               # 旁白/对白声线与语速（edge-tts rate 偏移）
    narration-voice: zh-CN-YunyangNeural  # 纪录片男声（TTS_NARRATION_VOICE 覆盖）
    narration-rate: "+15%"           # 旁白提速档（实测 ~5.5 字/秒）；镜头时长 ≥ 字数/字每秒 + 0.6s
    dialogue-rate: "+0%"             # 对白不比旁白快，快了就变成报幕
  commentary:                        # Track B：评论工作台（/commentary-studio）
    max-insert-ratio: 0.2            # 引用占比护栏：引用镜时长/总时长超限拒绝导出
    attribution-font: ""             # 出处水印中文字体文件（如 C:/Windows/Fonts/msyh.ttc）
    import-dir: ""                   # 素材批量扫描目录（epNN_标签_名称.mp4/png 约定；空=禁用）
  assets:                            # 参考素材库（2026-11-17）
    reference-import-dir: ""         # char_角色名_描述.png / scene_场景名_描述.jpg 约定扫描目录（空=禁用）
    booru:
      allowed-hosts: safebooru.org,safebooru.donmai.us,cdn.donmai.us  # 图片 host 白名单（https+精确匹配+禁重定向）
      default-provider: safebooru    # 主源：safebooru / danbooru-safe
  endcard:                           # 尾卡镜：endCard=true 跳过生图，本地纯色底+压字
    font: C:/Windows/Fonts/msyh.ttc  # 中文必须 fontfile（Linux/Docker 换 Noto Sans CJK）
    color: black                     # 底色（lavfi color 表达式）
  sfx:
    enabled: false                   # 音效推荐混音总开关（开启后导出勾选“音效”才生效）
  realesrgan-path: ""                # Real-ESRGAN ncnn-vulkan 绝对路径（空=跳过超分）
  realesrgan-model: realesrgan-x4plus-anime  # 动漫风格推荐 x4plus-anime
  rife-path: ""                      # RIFE ncnn-vulkan 绝对路径（空=跳过补帧；依赖超分抽帧链）
  rife-model: ""                     # RIFE 模型目录（如 rife-v4.6）
  restyle:                           # Phase 4：视频转绘（复用 DASHSCOPE_API_KEY，空 Key 不可用）
    model: wan2.7-videoedit
    timeout-seconds: 600
    poll-interval-ms: 5000

sfx:
  library-path: ./data/sfx          # 音效库目录；文件名格式 category__name.mp3，
                                    # 分类：wind/rain/footsteps/heartbeat，启动时自动扫描注册

bgm:
  library-path: ./data/bgm          # 内置音乐库目录；文件名格式 category__name.mp3
                                    #（如 happy__sunny_morning.mp3），分类：happy/sad/epic/calm/tense，
                                    # 启动时自动扫描注册；用户上传保存在 uploads/ 子目录
```

**关键说明：**

- **视频合成依赖 FFmpeg**：路径由 `video.ffmpeg-path` / `video.ffprobe-path` 指定（默认回退 PATH 中的 `ffmpeg`/`ffprobe`，可用 `FFMPEG_PATH`/`FFPROBE_PATH` 环境变量覆盖）；未安装/路径错误时导出会失败并给出错误提示。**版本需 ≥ 4.3**（xfade 转场），BGM 混音需 ≥ 4.4（amix normalize）；EVCapture 等软件自带的旧版（≈4.2）无 xfade，不可用
- **旁白配音依赖 Edge TTS**：`pip install edge-tts`；调用命令由 `video.python-command` 指定（可用 `PYTHON_CMD` 覆盖；Windows 上裸 `python` 可能被微软商店“应用执行别名”桩劫持，建议写解释器绝对路径）；未安装时自动跳过配音，仅输出无声视频
- **BGM 音乐库**：将免版权音乐按 `category__name.mp3` 命名放入 `./data/bgm/` 即自动注册，也可通过前端上传按钮添加
- **字幕**：字幕时间轴与 xfade 转场精确对齐；字体需系统已安装（默认微软雅黑，`video.subtitle.font-name` 可改）；烧录使用 FFmpeg subtitles 滤镜（libass，常用构建均已内置）
- **导出比例**：16:9 横屏(B站) / 9:16 竖屏(抖音) / 1:1 方形(小红书)，预览固定 480p + 水印，正式导出 720p/1080p 无水印
- **平台导出**：平台预设自动映射 1080p 分辨率；封面默认截取最近一次成功导出的中间帧；Logo 角标需配置 `video.watermark.logo-path` 后在导出请求中传 `withLogo: true` 生效
- **本地模型风格预设**：本地生成时按 `image.local.model` 模型名自动套用 SD1.5/SDXL/FLUX 内置风格前缀与负面词（FLUX 不套用负面词），可用 `image.local.style-prefix` / `image.local.extra-negative-prompt` 覆盖；仅作用于本地生成器，云端 DashScope 不受影响
- **图片质量评估**：`image.quality.enabled: true` 开启后，每张图片由多模态 LLM 打分（清晰度/相关性/美学，0-10），低于 `threshold` 自动重试（≤ `max-retries` 次，评分反馈并入负面提示词，保留最高分）；未配 API Key 或调用失败时降级为分辨率规则评估；评估异常不阻塞生成；评分随镜头详情与 SSE 进度返回，前端显示质量徽章
- **本地 ComfyUI 双协议**：图像侧（SD1.5+LoRA+IP-Adapter）与视频侧（Wan2.2 i2v）均走原生工作流协议（/upload/image → /prompt → /history → /view），模板占位符替换、与具体工作流解耦；未启动/模板未校准时 available=false 自动降级，干净启动不受影响；重启后生成器回默认源，需在前端下拉重新切换
- **参考图消费闸门**：镜头绑定的参考素材（referenceAssetIds）仅由 comfyui（IP-Adapter 模板就位时）与 wan27 生成器消费；每镜最多取前 2 张（角色定妆图在前、CHARACTER 优先 SCENE）；绑定/更换不失效旧图，只影响下次生成；INSERT 类素材禁入生成链路（仅供评论工作台拼接）
- **booru 网搜三道闸**：① https + host 精确白名单 + 禁重定向；② 预览/入库只认 provider+postId 服务端重查，不信任客户端 URL；③ 下载护栏 10s 连接/30s 读取/≤20MB/image/*；rating 复核覆盖搜索/预览/入库三入口（仅 safe/g/general）；入库字节转存自家存储，sourceNote 记录出处页+分级+tags，外链永不进生成链路
- **TTS 语速与时长算术**：旁白默认 YunyangNeural +15%（实测 ~5.5 字/秒），镜头时长应 ≥ 字数/字每秒 + 0.6s；改语速后需重算全片时长；评论工作台合成另有 TTS 预探测自动抬升有效时长防旁白被截
- **尾卡镜**：endCard=true 的镜头旁路生图 provider，由 EndCardImageRenderer 本地 FFmpeg 渲染纯色底+压字；中文必须配置 `video.endcard.font` 字体文件，否则文字静默不渲染

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
| `/api/auth/captcha` | GET | 获取验证码图片（4位字母数字，答案仅存 Session） |
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/logout` | POST | 退出登录 |
| `/api/auth/user` | GET | 获取当前登录用户信息 |

### 聊天接口（需要登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/chat/stream` | POST | 流式发送消息（SSE） |
| `/api/chat/send` | POST | 发送消息（非流式） |
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
| `/api/model/switch` | POST | 切换模型（需登录） |
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
| `/api/video/scripts/{id}/style` | PUT | 脚本级画风锁（Phase 4） |
| `/api/video/scripts/{id}/continuity` | PUT | 连续性模式 NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME（Phase 4） |
| `/api/video/scripts/commentary/import` | POST | 导入评论 JSON（format=commentary，Track B） |
| `/api/video/scripts/{id}/shots/{shotId}/asset` | POST | 镜头绑定素材（片段/主视觉，Track B） |
| `/api/video/scripts/{scriptId}/asset-matches` | POST | LLM 素材匹配推荐（不落库，确认走镜头 referenceAssetIds） |
| `/api/video/characters` | GET/POST | 角色卡列表 / 创建（含 loraName/triggerWord，Phase 4） |
| `/api/video/characters/{id}` | PUT/DELETE | 角色卡更新 / 删除 |
| `/api/video/characters/{id}/reference-image` | POST | 定妆参考图上传（multipart） |
| `/api/video/characters/voices` | GET | TTS 声线列表 |
| `/api/video/candidates` | GET | 镜头候选列表（scriptId+shotId） |
| `/api/video/candidates/generate` | POST | 抽卡生成（IMAGE N=3 / VIDEO N=2） |
| `/api/video/candidates/{id}/select` | POST | 人工择优回写主资产 |
| `/api/video/images/inpaint` | POST | 框选局部重绘（产物入候选） |
| `/api/video/videos/shot/{scriptId}/{shotId}` | POST | 单镜头真 i2v 生成（Phase 4/5） |
| `/api/video/videos/script/{scriptId}/stream` | GET | SSE 批量 i2v 进度 |
| `/api/video/videos/generators` | GET | 视频生成器列表（none/dashscope-wan/comfyui） |
| `/api/video/videos/generator` | PUT | 运行时切换视频生成器 |
| `/api/video/restyle` | POST/GET | 视频转绘提交（multipart file+style）/ 我的任务列表 |
| `/api/video/restyle/{id}` | GET | 转绘任务状态轮询 |
| `/api/video/assets` | POST/GET | 素材上传（file/name/tags/category/trim）/ 列表（可按 category 过滤） |
| `/api/video/assets/{id}` | DELETE | 删除素材（归属校验） |
| `/api/video/assets/scan` | POST | 评论素材目录扫描（epNN_标签_名称 约定） |
| `/api/video/assets/scan-reference` | POST | 参考素材目录扫描（char_/scene_ 约定，幂等去重） |
| `/api/video/assets/booru/search` | GET | booru 网搜（q 多 tag 用 + 连接；provider；page 0-4，每页 20） |
| `/api/video/assets/booru/preview` | GET | 缩略图服务端代理（provider+postId，过闸取字节） |
| `/api/video/assets/booru/ingest` | POST | 网搜素材入库（自家存储转存 + sourceNote 出处） |
| `/api/video/export/hd/async` | POST | 异步高清导出（返 jobId，Track B） |
| `/api/video/export/jobs/{id}` | GET | 异步导出任务状态（RUNNING/SUCCESS/FAILED） |
| `/api/video/export/batch-download` | POST | 批量三平台导出 + ZIP 打包下载 |
| `/api/video/templates` | GET | 视频模板列表（8 内置 + 自定义） |
| `/api/video/templates/{id}` | GET/DELETE | 模板详情 / 删除 |
| `/api/video/templates/{id}/apply` | POST | 一键应用模板生成脚本 |
| `/api/video/templates/custom` | POST | 脚本另存为自定义模板 |
| `/api/video/agent/tools` | GET | Agent 工具清单（名称/描述/输入 Schema，Phase 3 切片1） |
| `/api/video/agent/chat` | POST | AI 导演对话（ReAct 工具编排；exportVideo 需 confirmedTools 确认，Phase 3 切片2） |
| `/api/video/agent/chat/{sessionId}` | DELETE | 清空导演对话历史（Phase 3 切片2） |
| `/api/video/agent/orchestrate` | POST | 一键成片：提交多 Agent 异步编排任务（Phase 3 切片3） |
| `/api/video/agent/orchestrate/{taskId}` | GET | 查询编排任务状态（轮询，Phase 3 切片3） |
| `/api/video/agent/orchestrate/{taskId}/confirm` | POST | 确认续跑：放行 exportVideo 闸门（Phase 3 切片3） |

> 视频工作室页面：http://localhost:8080/video-studio ；评论工作台：http://localhost:8080/commentary-studio （详见 [API_TEST.md](API_TEST.md) 第七节与尾部 Phase 节）

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
| `app` | 本地构建 (Dockerfile) | Spring AI 应用，多阶段构建，JRE 17 Alpine 运行时；镜像内置 FFmpeg、Python+edge-tts（venv）、Noto CJK 字体与预置 BGM，短视频全功能开箱即用 |
| `postgres` | `pgvector/pgvector:pg16` | PostgreSQL + pgvector 扩展，RAG 向量持久化 |
| `ollama` | `ollama/ollama` | 本地 AI 模型服务，可选启用 |

服务之间通过内部网络 `ai-net` 通信，应用通过服务名访问中间件（`postgres:5432`、`ollama:11434`）。

- **健康检查**：postgres 配置了 `pg_isready` 健康检查，app 等待数据库就绪后才启动
- **数据持久化**：`pgdata`、`ollama-data`、`appdata`（预置 BGM/运行数据）、`uploads`（生成文件）命名卷保证容器重建不丢数据
- **Profile 控制**：ollama 服务使用 `profiles: [ollama]`，默认不启动，需 `--profile ollama` 显式启用
- **安全运行**：容器以非 root 用户 `appuser` 运行应用
- **短视频依赖**：镜像已装 FFmpeg（≥6.x，含 xfade/libass）与 edge-tts，compose 自动注入 `FFMPEG_PATH`/`FFPROBE_PATH`/`PYTHON_CMD`；字幕字体由 docker profile 自动切为 `Noto Sans CJK SC`；`.env` 需配置 `DASHSCOPE_API_KEY`（图像生成/质量评估）并设置强 `POSTGRES_PASSWORD`（必填，compose 无硬编码默认值，未设置将拒绝启动）

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

H2 控制台默认关闭（安全加固）。本地调试可在 `application-h2.yml` 开启 `spring.h2.console.enabled: true` 后访问 http://localhost:8080/h2-console：
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

#### PostgreSQL 数据库

使用任何 PostgreSQL 客户端工具连接：
- Host: `localhost`, Port: `5432`, Database: `chatdb`

### 6. RAG 上传文档失败

- 确认文件大小不超过 10MB
- 确认 Ollama embedding 模型已下载：`ollama pull nomic-embed-text`
- 或使用云端 embedding：设置 `rag.embedding-model: dashscope`（通义 text-embedding-v4，复用 DASHSCOPE_API_KEY，无需本地 Ollama）或 `openai`；重启生效，切换后清空旧向量重新上传

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

- 确认 `video.ffmpeg-path` / `video.ffprobe-path` 指向的可执行文件存在且可运行（默认回退 PATH）：`ffmpeg -version`
- **版本需 ≥ 4.3**（xfade 转场滤镜），BGM 混音需 ≥ 4.4（`amix normalize` 参数）；EVCapture 等自带的 4.2 旧构建无 xfade，请安装新版（如 BtbN win64-gpl 构建）并修改配置路径
- 多镜头合成较慢属于正常现象（1080p 可能需要几分钟），超时上限由 `video.compose-timeout-minutes` 控制（默认 8 分钟）
- 旁白配音失败时检查 `video.python-command` 指向的解释器已装 edge-tts（`python -m edge_tts --version`）；Windows 裸 `python` 可能被微软商店别名桩劫持（退出码 49），请写绝对路径

### 15. 如何使用本地 Stable Diffusion / FLUX / ComfyUI 生成短视频画面？

1. **SD WebUI 路线**：启动本地 SD WebUI（或 OllamaDiffuser 等兼容服务），确认可访问 `http://127.0.0.1:7860`；配置 `image.generator: local` 或前端下拉切换
2. **ComfyUI 路线（原生工作流协议，已支持）**：启动 ComfyUI（默认 `http://127.0.0.1:8188`），按 docs/comfyui/ 参考模板从本机导出校准后覆盖 `image.comfyui.workflow-*`；配置 `image.generator: comfyui` 或前端下拉切换；支持 SD1.5+角色 LoRA、IP-Adapter 参考图（weight 0.7）与 ControlNet 锁机位（gated）
3. 本地生成原生支持负面提示词（Negative Prompt），SD WebUI 路线按模型名自动套用风格预设（SD1.5/SDXL/FLUX），画面风格更可控且无 API 成本；可用 `image.local.style-prefix` / `image.local.extra-negative-prompt` 自定义覆盖
4. 视频侧同理：`video.generator: comfyui` 走本地 Wan2.2 i2v（零 API 成本），需校准 `video.comfyui.workflow-path`；单卡建议 `video.workers: 1`

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

### 18. booru 网搜素材怎么用？多 tag 搜索为什么零结果？

- 工作室「参考素材库」面板输入英文 tag 搜索（如 `hatsune_miku`），勾选后「入库选中」转存到自家存储（sourceNote 自动记录出处页/分级/tags）
- **多 tag 语义**：空格会被转成下划线（单个 tag 内分词，如 `black hair`→`black_hair`）；多个 tag 请用 `+` 连接表示 AND（如 `1girl+black_hair`）；tag 越具体结果越少，零结果时先减 tag 再换源（provider=danbooru-safe）
- 缩略图走服务端代理（浏览器直连源站常失败）；个别缩略图加载失败多为上游瞬时限流，重试即可
- 入库仅支持 CHARACTER/SCENE 两类参考素材；非 safe 分级、非白名单 host 一律拒绝（三道闸，见配置说明）

### 19. 如何提高角色一致性（漫剧主角不崩脸）？

- **云端路线**：`image.generator: wan27` + 角色卡定妆参考图（≤9 张），参考图回退链自动处理 local/oss 存储
- **本地路线**：`image.generator: comfyui` + 角色 LoRA（角色卡填 loraName/triggerWord）；无 LoRA 时用 IP-Adapter 参考图模板（需本机校准 `sd15_ipadapter.template.json`，weight 默认 0.7，过高会抄成贴图、过低不像）
- 镜头绑定参考素材后生成时自动消费（每镜前 2 张、人物优先）；注意重启后生成器回默认源，需重新切换；默认 zhipu 等纯文生图源不吃参考图
- 构图/机位要求高时可开 `image.comfyui.controlnet-enabled: true`（canny 锁机位，+~10s/张）；崩手崩脸用框选 inpaint 修（产物入候选人工择优）

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Spring Security 官方文档](https://spring.io/projects/spring-security)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

ylyan2015


## Phase 4 切片 1：角色卡与图像一致性（2026-11-15）

- 角色卡：脚本生成时 LLM 先人设后分镜，自动产出角色卡（名字/英文特征表/性格/声线）；/api/video/characters 支持 CRUD、定妆参考图上传（multipart）、GET /voices 声线列表。
- 脚本级画风锁：PUT /api/video/scripts/{id}/style；镜头增景别（CLOSE/MEDIUM/LONG）与出场角色关联。
- 一致性出图：新 wan27 图像生成器（DashScope wan2.7-image，≤9 参考图，URL/base64），image.generator=wan27 切换；参考图回退链：公网 URL→presigned→local base64→纯文本+特征表降级。
- 配置：image.wan27.model（默认 wan2.7-image）、image.wan27.timeout-seconds（120），复用 DASHSCOPE_API_KEY（空 Key 不可用，干净启动安全）。
- Agent：编剧专家增 generateCharacters 工具（7 工具）。
- 冒烟：mvn test -Dtest=Wan27RealSmokeTest -Dwan27=true（默认跳过）。


## Phase 4 切片 2：真图生视频与首尾帧连续性（2026-11-15）

- 真 i2v：新 DashScope wan2.7-i2v 视频生成器（异步提交+轮询、首/尾帧 media、产物转存 mp4），video.generator=dashscope-wan 切换；默认 none 保持静图运镜合成（存量导出逐帧不变）。
- 连续性模式：脚本级 NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME（PUT /api/video/scripts/{id}/continuity）；PREV_LAST_FRAME 以上一镜尾帧链接下一镜首帧顺序生成；GENERATED_LAST_FRAME 用 wan27 按下一镜提示词延伸尾帧（末镜不生成）。
- 合成升级：真片段+静图混合合成；真片段用 ffprobe 实测时长、无 zoompan；xfade 总时长=Σ时长−(n−1)×0.8；i2v READY 回写 durationSeconds 并抽取尾帧转存。
- 配置：video.generator（none/dashscope-wan）、video.workers（默认 2）、video.retry-attempts（默认 1）、video.wan.model/base-url/resolution/timeout-seconds/poll-interval-ms；复用 DASHSCOPE_API_KEY（空 Key 不可用）。
- 首帧 URL 回退：公网 URL 直用→非 local 存储 presigned→local 存储返回 400 并指引切换 oss/minio。
- Agent：美术专家增 generateShotVideo 工具（8 工具）。
- 冒烟：mvn test -Dtest=WanI2vRealSmokeTest -Di2v=true（默认跳过）。


## Phase 4 切片 3：抽卡多候选与局部重绘（2026-11-15）

- 抽卡候选：每镜图 N=3/视频 N=2 顺序生成（video.candidates.image-count/video-count 可配，成本敏感可降 1），质量评分排序，暂取最高分回写主资产（前端标“待择优”），人工选用为最终决策；一键编排不抽卡（N=1）。
- 局部重绘：wan2.7-image 框选编辑（bbox_list 绝对像素，相对坐标 0-1 自动换算），重绘产物入候选不直接覆盖主图；不支持 edit 的生成器/坐标越界返回 400。
- 端点：/api/video/candidates（列表/抽卡生成/选用）、/api/video/images/inpaint；前端候选对比模态 + 重绘框选弹层。
- Agent：美术专家增 inpaintImage 工具（9 工具），重绘可推荐/执行，选择仍由用户完成。
- 冒烟：mvn test -Dtest=Wan27CandidateSmokeTest -Dwan27=true（默认跳过）。


## Phase 4 切片 4：多角色配音与后期增强（2026-11-15）

- 对白/旁白分离：镜头增对白字段与对白角色关联（脚本生成 LLM 自动产出，前端可编辑）；TTS 按行合成：对白用角色卡声线、旁白沿用 zh-CN-YunxiNeural，镜内拼接、镜间静音对齐；无对白时行为与存量逐字一致。
- 字幕含对白：字幕文本=对白+旁白（对白在前），中/英/双语与 SRT 导出同步生效。
- 音效分层混音：预置 4 条免版权音效（wind/rain/footsteps/heartbeat，tools/generate-preset-sfx.ps1 lavfi 合成，启动扫描 data/sfx 自动注册）；LLM 单次批量推荐每镜音效分类，失败/未配置关键词回退；混音分层人声 100%/BGM 15%/SFX 30%（amix normalize=0 防人声减半），adelay 按镜头起始定位。
- 超分/补帧导出增强：Real-ESRGAN 2× 超分 + RIFE 补帧（帧数翻倍，输出帧率同步翻倍 30→60fps 保时长；抽帧→超分→补帧→重编码保音轨）；工具未配置静默跳过、执行失败 warn 跳过不阻断导出；仅高清导出生效，预览/平台/Agent 导出零变化。
- 导出新参数：POST /api/video/export/hd 增可选 upscale/interpolate/sfx（缺省全 false）；前端 action-bar 增超分/补帧/音效三勾选，镜头编辑增对白与对白角色。
- 配置：video.sfx.enabled（默认 false）、sfx.library-path（./data/sfx）、video.realesrgan-path/model（默认 realesrgan-x4plus-anime）、video.rife-path/model（空=跳过）；增强工具下载见 Real-ESRGAN/RIFE ncnn-vulkan release，CPU 耗时较长，Docker 镜像不预装。


## Phase 4 切片 5：视频转绘新输出模式（2026-11-15）

- 视频转绘：实拍视频上传（mp4/mov、2-10 秒、≤100MB）→ wan2.7-videoedit 风格转绘（日系动漫/美漫/水彩预设）→ 独立输出模式；一致性由实拍天然锁定，不触碰主 pipeline。
- 独立任务：RestyleTask 实体（VideoExport.script_id NOT NULL 存量约束不可放宽，转绘无脚本归属）；异步提交+轮询+产物转存，SecurityContext 传播 idiom 同编排；失败仅任务 FAILED 不影响其他功能。
- 端点：POST /api/video/restyle（multipart file+style）、GET /api/video/restyle/{id}、GET /api/video/restyle（我的任务）；前端转绘面板 3s 轮询（仅未终态时）+结果播放/下载。
- 配置：video.restyle.model（默认 wan2.7-videoedit）/timeout-seconds（600）/poll-interval-ms（5000），复用 DASHSCOPE_API_KEY（空 Key 不可用）。
- spike 验收（2026-11-15 实测）：动作/机位保持+画风动漫化达标；计费=输入+输出时长（720P 基线）；视频输入仅公网 URL/oss 临时 URL，不支持 base64→local 存储 FAILED 带 oss/minio 指引；Kling transform 为 2-OPT 备选（需 KLING_API_KEY，本期不实现）。
- 冒烟：mvn test -Dtest=VideoRestyleRealSmokeTest -Drestyle=true（默认跳过；local 存储验证错误路径）。


## Phase 5 切片 1：本地 ComfyUI 图生视频（零成本路线）（2026-11-15）

- 本地 i2v：新 ComfyUiVideoGenerator（type=comfyui）接本地 ComfyUI（Wan2.2 TI2V-5B，Apache 2.0 可商用），零 API 成本、无限速、素材不外传；video.generator=comfyui 或前端下拉切换（/generators 自动收录）。
- 模板无关：工作流 JSON 占位符替换（{{FIRST_FRAME}}/{{PROMPT}}/{{FRAMES}}/{{WIDTH}}/{{HEIGHT}}/{{FPS}}/{{SEED}}），参考模板 docs/comfyui/wan22_i2v_5b.template.json；Phase 0 环境搭建后从 ComfyUI 导出校准覆盖（video.comfyui.workflow-path / COMFYUI_WORKFLOW）。
- 本地存储闭环：resolveMediaUrl 增 local×comfyui 相对路径直通分支，生成器同源（127.0.0.1:server.port）取首帧字节，绕开公网 URL/oss-minio 约束；local×dashscope-wan 存量行为不变。
- 协议：/upload/image → /prompt → /history/{id} → /view；帧数自动对齐 4n+1；分辨率档 720P→1280×704（本机实测）/ 480P→832×480；模板锁定 VAEDecodeTiled（裸 VAEDecode 本机假死）+ 核心 CreateVideo/SaveVideo。
- 配置：video.comfyui.base-url（默认 http://127.0.0.1:8188）/workflow-path/fps（24）/timeout-seconds（1800）/poll-interval-ms（3000）；ComfyUI 未启动或工作流未配置时 isAvailable=false，干净启动不受影响，默认 none 零变化。workflow-path 相对路径基于启动 CWD，打包部署设绝对路径（COMFYUI_WORKFLOW）。
- 冒烟：mvn test -Dtest=ComfyUiRealSmokeTest -Dcomfyui=true（默认跳过；需 ComfyUI 启动且工作流校准）。排产：comfyui 路线 video.workers 建议 1（单卡队列串行）；实测 29f≈155s/57f≈382s/121f≈722s，短切 1.5–2.5s 为主力。

## Phase 5 切片 3：本地图像侧 SD1.5+LoRA 分镜（全链路零云依赖）（2026-11-15）

- 本地分镜：图像侧增 ComfyUiImageGenerator（type=comfyui），anything-v5 + 角色 LoRA 身份一致性（实测 ~8s/张，22 步 CFG7 euler/normal）；image.generator=comfyui 或前端下拉切换；无 LoRA 角色走 plain 模板，存量源行为逐字不变。
- 角色卡：loraName/triggerWord 两字段全链（entity/service/controller/前端对话框输入·回填·清空）；触发词经唯一装配点 ShotPromptAssembler 注入（仅非空）。
- LoRA 路由：ImageGenerator 增 supportsLora/generateWithLora 能力旗标；LoraResolver 取首个带 LoRA 角色；generateWithRetry 单 dispatch 点 + 候选抽卡路径同路由。
- ControlNet 锁机位（gated）：image.comfyui.controlnet-enabled 默认 false；开启后角色定妆参考图经 canny（0.4/0.8、strength 0.8，+~10s/张）；三模板 docs/comfyui/sd15_storyboard / sd15_lora_storyboard / sd15_lora_canny.template.json。
- 配置：image.comfyui.workflow-plain/lora/canny（env COMFYUI_IMG_*）/timeout-seconds 120/poll-interval-ms 1000；base-url 复用 video.comfyui.base-url；720 高→704（实测）；SD1.5 用裸 VAEDecode（tiled 强制仅 Wan）。
- 冒烟：mvn test -Dtest=ComfyUiImageRealSmokeTest -Dcomfyui=true（默认跳过；实测 2/2 绿，产物 1280×704）。全量 346 绿。

## Phase 5 Track B：评论杂谈自动化（评论工作台）（2026-11-16）

面向动漫影视杂谈/漫评类创作（区别于 /video-studio 的 AI 短剧集自动模式）：用素材图片/视频切片拼接 + 字幕 + 旁白，独立页面入口，两轨分开。

- 评论工作台：/commentary-studio（需登录），脚本列表按 format=commentary 分离（存量 AI 短剧脚本不混入）；支持粘贴/文件导入评论 JSON（shots[].insert.attribution→出处）。
- 素材库：上传入库（视频可选裁剪 trimStart/trimEnd，ffprobe 实测时长）/列表/删除；文件夹批量扫描入库（仅读配置目录 video.commentary.import-dir，文件名约定 epNN_标签_名称.mp4/png→tags="epNN,标签"，非法跳过）。
- 镜头素材绑定：视频素材→绑为片段（回写实测时长），图片素材→绑为主视觉（自动按 kind 判定，旧视频失效防混入）。
- 合成自动对齐：TTS 预探测逐镜有效时长=max(镜头时长, ceil(旁白音频实测))，防旁白被 -shortest 截断；真片段短于有效时长时 -stream_loop -1 循环填充 + trim 截断。
- 出处水印 + 版权护栏：引用镜（insertAttribution 非空）左下角叠加出处水印（中文需配置 video.commentary.attribution-font）；导出前置校验引用占比=Σ引用镜时长/Σ总时长 > video.commentary.max-insert-ratio（默认 0.2）拒绝；页面引用占比徽章预算示（服务端权威）。
- 异步导出：POST /api/video/export/hd/async 提交即返 jobId，后台单线程 worker 合成，GET /api/video/export/jobs/{id} 轮询 RUNNING/SUCCESS/FAILED；同步 /export/hd 不变。
- 发片工具：tools/auto-commentary-concat.js（Node）按脚本时间轴（xfade=0.8）拼接主体+插入素材并烧录出处水印；主体画幅须与脚本 aspectRatio 一致，drawtext 显式 fontfile 中文字形。
- 配置：video.commentary.max-insert-ratio（0.2）/attribution-font（空）/import-dir（空）。冒烟：mvn test -Dtest=VideoComposeAutoFitTest -Dcommentary=true（真实 FFmpeg loop，默认跳过）。

## Phase 5 素材库切片：参考素材与 booru 网搜（2026-11-17）

面向漫剧角色/场景一致性：把「找参考图 → 绑定镜头 → 参考图出图」打通成闭环，与 Track B 评论素材库共用 MediaAsset 实体但用途分离（CHARACTER/SCENE 进生成链路，INSERT 仅供评论拼接）。

- S1 参考素材入库：上传增 category（CHARACTER/SCENE/INSERT 白名单 fail-fast）；新端点 POST /api/video/assets/scan-reference 扫描配置目录 video.assets.reference-import-dir（文件名约定 char_角色名_描述.png / scene_场景名_描述.jpg，大小写不敏感，多段标签下划线转逗号，幂等去重）。
- S2 镜头绑定：镜头增 referenceAssetIds 字段（PUT 镜头/批量 PUT 均贯穿）；写侧三校验（归属+IMAGE+category∈{CHARACTER,SCENE}），伪造/他人 id 静默剔除；消费侧 ReferenceImageResolver 同校验最后防线；绑定/更换不失效旧图旧视频（只影响下次生成）。
- S3 IP-Adapter 参考图出图：ComfyUiImageGenerator 模板路由 refs 优先（ipadapter+lora 组合 / ipadapter / plain / lora / lora+canny）；参考图消费闸门每镜前 2 张（角色定妆图在前、CHARACTER 优先 SCENE）；ipadapter 模板未就位 supportsReferences=false 自动降级不阻塞启动；新模板 docs/comfyui/sd15_ipadapter.template.json（已本机校准，plus_sd15 + ViT-H）/ sd15_ipadapter_lora.template.json（组合待手工校准）；配置 image.comfyui.workflow-ipadapter/ipadapter-lora/ipadapter-weight（0.7）。
- S4 booru 网搜入库：GET /api/video/assets/booru/search（q/provider/page，每页 20、≤5 页）/ GET preview（服务端代理过闸取字节，魔数嗅探 png/jpeg/webp/gif）/ POST ingest（provider+postId 重查，字节转存自家存储，sourceNote=出处页|rating|tags）；双源 safebooru（主）/ danbooru-safe；三道闸：https+host 精确白名单+禁重定向 / 不信任客户端 URL / 下载护栏 10s+30s+≤20MB+image/*；rating 三入口复核仅 safe/g/general；策略拒绝 400 可透传、内部故障 500 固定文案防 SQL 外泄。
- 自动匹配：POST /api/video/scripts/{scriptId}/asset-matches 由 LLM 批量判断镜头×素材（name/tags 文本）相关性，失败降级关键词重叠（source=LLM/FALLBACK）；推荐不落库，前端 chip 一键采用后走 updateShot.referenceAssetIds 确认。
- 前端：工作室素材库面板（上传/扫描/网搜/勾选入库/匹配 chip/绑定管理）；booru 搜索框 placeholder 提示多 tag 用 + 连接。
- 验收：全量门禁绿；真实 story（GTA6 十三年等待）生产测试 + 浏览器 UI 全链路验证（网搜 hatsune_miku → 入库 → 绑定 → comfyui 生图目视继承初音特征 → HD 导出播放）。

## 生产校准：尾卡镜 / TTS 语速 / ComfyUI 超时（2026-11-17）

真实 story 整片试产后的三项校准（存量行为兼容，默认值已按实测调优）：

- 尾卡镜：镜头增 endCard 字段，true 时旁路生图 provider 由 EndCardImageRenderer 本地 FFmpeg 渲染纯色底+压字（关注/下集预告 CTA）；配置 video.endcard.font（中文必须 fontfile）/color；零 API 成本、不占生成队列。
- TTS 声线与语速：新配置 video.tts.narration-voice（默认 zh-CN-YunyangNeural 纪录片男声）/narration-rate（+15%，实测 ~5.5 字/秒）/dialogue-rate（+0%）；台词算术：镜头时长 ≥ 字数/字每秒 + 0.6s，改语速后重算全片。
- ComfyUI i2v 超时上调：video.comfyui.timeout-seconds 1800→2700（实测 8s 镜 193f ~25min、9s 镜 217f ~31min 顶旧红线）；排产建议短切 1.5-2.5s 为主力、workers=1。
- 已修复（2026-11-18）：字幕时间轴偏移——合成时按 TTS 实测/真片段实测的有效时长累计重排字幕 cue（重排基准为名义声明时长，覆盖 TTS 抬升与真片段实测漂移两类场景）。
- 已知限制（留后续切片）：booru preview 对不存在 postId 返 500（应 404）。

## 钩子化脚本：叙事硬约束 + 台词密度自动校验（2026-11-18）

P0 留存基本盘，对所有脚本生成生效（VideoScriptService system prompt，story 脚本/模板引导/素材清单路径均继承）：

- 钩子叙事硬约束：首镜 3 秒内抛冲突/悬念（开场旁白 ≤15 字，禁风景铺垫/慢热引入）；每 15-20 秒一个情绪转折（冲突升级/信息反转/情绪落差）；末镜留扣子接尾卡关注 CTA（有尾卡镜时扣子放倒数第二镜）。
- 台词密度自动校验：生成后逐镜检查时长 ≥ (旁白+对白字数)/5.5 + 0.6s（YunyangNeural +15% 实测语速基准）；超密只 warn 不阻断（LLM 输出不可强控，合成时 TTS 预探测抬升时长兜底）；尾卡镜本地压字不走 TTS 跳过。
- 测试：VideoScriptServiceHookTest（prompt 约束契约 + 密度算术边界/对白合计/尾卡跳过/null 安全，经变异复证）。

## 竖屏优先：脚本级画幅 + 竖构图提示词 + 多人镜拆特写（2026-11-18）

P0 短视频平台适配（抖音/快手/视频号竖屏信息流），存量横屏脚本行为逐字不变：

- 脚本级画幅字段：StoryScript 增 aspectRatio（白名单 16:9/9:16/1:1，null=默认横屏）；PUT /api/video/scripts/{id}/aspect 更新（空值清空）；脚本详情响应新增 aspectRatio 字段（null 透出 "16:9"）。
- 生图尺寸自动切换：脚本画幅 9:16 时单镜/批量/尾卡/抽卡四处装配点统一切 720x1280（无视 image.generation.default-size）；其余画幅沿用配置尺寸（非法/缺失退 1280x720）。
- 竖构图提示词：9:16 时提示词装配点（ShotPromptAssembler）注入 centered subject / vertical framing；多人镜（≥2 角色）强制单人特写（single character close-up, only one person in frame，替代原景别，规避 SD1.5 多人构图脸崩）；单人/空镜保留原景别。
- 导出画幅 fallback：预览/高清/异步导出请求未指定 aspectRatio 时自动沿用脚本画幅（显式请求优先，双 null 保持存量 16:9）；9:16 脚本默认导出 720x1280。
- 测试：ShotPromptAssemblerPortraitTest / ImageGenerationPortraitSizeTest / StoryScriptServiceAspectTest / VideoExportAspectFallbackTest（26 用例，四处变异均独红复证）；全量门禁 602 绿。

### 脚本自我精修：生成-评价-生成（2026-11-18）

分镜脚本首轮生成后，默认经过 2 轮「四人评审团（导演/编剧/画师/乐师单 prompt 串联质疑，不改写）→ 原硬约束下重修」，迭代提升钩子、台词密度、imagePrompt 与声画匹配质量。
- 配置 `video.script.refine-rounds`（默认 2，0=关闭）；每轮 +2 次 LLM 调用，总耗时与 token 约为直出 3-5 倍。
- 脚本生成专用模型 `video.script.model`（默认 `deepseek-flash` 控成本——2026-11-18 成本拍板，v4-pro 约 ×3-5 元；质量优先可设环境变量 `VIDEO_SCRIPT_MODEL=deepseek-v4-pro` 升档；仅 deepseek profile 生效，空=跟随全局 chat 模型；V4 系思考模式默认开启无需额外参数），单调用读超时 `video.script.read-timeout-seconds` 默认 300s。
- 优雅降级：评审/重修任一调用失败或产物不可解析 → 保留当前最优稿返回，精修绝不拖垮生成。
- 前端长等待接线（2026-11-18 补）：工作台脚本生成（含模板应用）走 postLong + AbortController，预算由 `video.script.client-timeout-ms`（默认 900s）服务端注入页面；超时提示「服务端仍在精修，稍后刷新列表」而非误报失败；e2e 驱动改 node:http 解除内置 fetch 300s 限制（实测客户端全程等住 580s 响应不断连）；异步化列入后续切片。
- 测试：VideoScriptServiceRefineTest / ModelServiceScriptClientTest（11 用例，7 处变异均独红复证）+ VideoStudioPageRenderTest 长等待接线断言 + VideoScriptDefaultsTest 配置默认值钉住；全量门禁 621 绿。

## B 站发布元数据：派生 + 润色 + 发布包（2026-11-19）

计划 `docs/superpowers/plans/2026-11-19-publish-metadata-bilibili.md`。目标很小：导出完视频后，B 站投稿页要逐项粘贴的那堆东西（标题/分区/标签/简介/创作声明/合集/置顶评论/时间戳）不用再回头翻脚本、也不用猜平台限到哪里。

- 规格表集流（`PublishSpec`）：标题 80 字符 / 标签 10 个 / 简介 2000 字（随分区变）、动漫杂谈 tid=253 与影视杂谈 tid=182、`?tid=` 灰度口径、禁用词表——**复核日期标在行内**，平台改规则只改这一个文件，前端计数器通过 `GET /api/video/publish/spec` 跟随，不硬编码。
- 确定性派生（`PublishMetaDeriver`，零模型依赖）：简介五节（【本片事实】【素材出处】【AI 使用说明】【不构成投资建议】【时间轴】）全部从脚本台账原文照抄，出处去重，无数字的主观句不进事实节；时间戳用最近一次导出的压缩轴（`video_export.shot_starts_csv`），无该列时退回名义轴并标 `approxTimestamps`。
- LLM 只碰标题候选与钩子，带四道护栏（长度/禁用词/数字量级/集数豁免）：候选里每个「数字+紧跟量级单位」必须逐字来自事实集，否则整条丢弃（不截断）；任一失败降级模板兜底。`llm=false` 零调用，`GET /{id}/meta` 恒不烧模型。
- 存库（`publish_meta`，脚本×平台一行）+ 部分更新（未传键保留原值）+ 400/500 分流（`PublishRejectException` 携 `violations:[{field,code,message}]` 供前端定位红框）。
- 导出联动（`PublishPackService`）：非预览导出成功后写 `uploads/final/<标题>-发布包.txt`（响应带 `publishPackUrl`），批量 ZIP 每条附一份发布包并在 `manifest.txt` 加四列；附属失败只 warn，绝不把已合成完的出片改判失败；人工存过的行只补空。
- 面板：评论工作台 `/commentary-studio` 新增发布区（计数器取 `/spec`、重复 tag 标灰、分节折叠、一键复制整包、violation 定位红框）。
- 端到端实跑（#738 真实脚本）与人工稿逐节对照见 `docs/story/FX/发布元数据-久留美.md` §9（`docs/story/` 本地不入库，clone 里没有；仓库内版本见计划末尾「执行记录」Task 9 行）：结构已对齐，差距全在取舍（事实条目粒度、标签分层、章节挑选、置顶评论）——**护栏只保证不编数字，不保证数字仍是真的**。
- 本期不做什么：不对接 B 站开放平台 API 自动投稿、不回填播放/点赞数据、不做抖音/小红书等其他平台规格（加平台只需在 `PublishSpec` 添一个工厂方法）。
- 顺手修掉一个存量缺陷：`POST /api/video/export/batch-download` 因签名写成 `ResponseEntity<?>`（泛型被擦除，Spring MVC 不认领 `StreamingResponseBody`）自 2026-11-14 起一直 500，工作台「打包下载全部」按钮从未跑通——只有 T9 的真实请求端到端才打到（service 层单测直接调 `writeBatchZip` 看不见这一层）。
- 测试：`PublishSpecTest` / `PublishMetaServiceTest` / `PublishMetaDeriverTest` / `PublishMetaLlmServiceTest` / `PublishMetaControllerTest` / `PublishPackServiceTest` / `VideoExportPublishPackTest` / `VideoExportBatchZipControllerTest`，逐 Task 变异复证记录见计划末尾「执行记录」；全量门禁 723 绿。e2e：`node tools/e2e-publish-meta.js`。
