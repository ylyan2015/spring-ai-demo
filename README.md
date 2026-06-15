# Spring AI Demo - 多模型智能聊天应用

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，支持多种AI模型（Ollama、OpenAI、DeepSeek等），内置用户注册登录系统、RAG 知识库、SSE 流式输出等功能。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并支持灵活切换不同的AI模型提供商，包括本地部署的 Ollama 模型和在线 API 服务（OpenAI、DeepSeek等）。同时提供完整的用户认证体系，聊天记录与用户绑定，RAG 知识库支持文档上传和上下文增强，保障数据隐私安全。

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
- **Apache Tika** - 文档解析（RAG: 支持 TXT/PDF/MD 等格式）
- **RSA + BCrypt** - 密码加密方案
- **Lombok** - 代码简化
- **Spring Boot Actuator** - 运维监控
- **Maven** - 项目构建工具

## 前置要求

在运行本项目之前，请确保已安装以下软件：

1. **JDK 17** 或更高版本
2. **Maven 3.6+**
3. **AI模型服务**（至少选择一种）：
   - **Ollama**（本地模型）- 从 [https://ollama.com](https://ollama.com) 下载并安装
   - **OpenAI API Key** - 注册 OpenAI 账号获取
   - **DeepSeek API Key** - 注册 DeepSeek 账号获取
4. **（可选）PostgreSQL + pgvector 扩展** - 如需 RAG 持久化存储

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd spring-ai-demo
```

### 2. 准备AI模型服务

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

### 3. 构建并运行应用

```bash
# 使用 Maven 构建项目
mvn clean install

# 运行应用
mvn spring-boot:run
```

或者直接运行主类 `SpringAiDemoApplication`。

### 4. 使用 Web 界面（推荐）

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

#### RAG 知识库

Web 界面右侧提供知识库面板：
- 📄 **文档上传** - 支持 TXT、PDF、MD 等格式，最大 10MB
- 📋 **文档列表** - 查看已上传的文档及分块数量
- 🗑️ **文档删除** - 删除不需要的文档及其向量数据
- 🔀 **上下文增强** - 开启/关闭 RAG 知识库上下文注入

### 5. 使用 API 测试

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
│   │   └── SecurityConfig.java         # Spring Security配置
│   ├── controller/
│   │   ├── AuthController.java         # 认证控制器（注册/登录/验证码）
│   │   ├── ChatController.java         # 聊天控制器（REST API + SSE）
│   │   ├── DocumentController.java     # 知识库文档管理控制器
│   │   ├── ModelController.java        # 模型管理控制器（切换/参数预设）
│   │   ├── ModelService.java           # 模型管理服务（动态切换/参数预设）
│   │   ├── PageController.java         # 页面控制器（Web 路由）
│   │   └── TimeZoneController.java     # 时区控制器（IP定位）
│   ├── service/
│   │   ├── AuthService.java            # 认证服务（注册/登录逻辑）
│   │   ├── CaptchaService.java         # 验证码服务
│   │   ├── ChatService.java            # 聊天服务（业务逻辑/上下文压缩）
│   │   └── RagService.java             # RAG 服务（文档解析/向量化/检索）
│   ├── entity/
│   │   ├── Conversation.java           # 会话实体（绑定userId）
│   │   ├── Message.java                # 消息实体
│   │   ├── ModelParamPreset.java       # 模型参数预设实体
│   │   ├── RagDocument.java            # RAG 文档元信息实体（持久化）
│   │   └── User.java                   # 用户实体
│   └── repository/
│       ├── ConversationRepository.java # 会话数据访问
│       ├── MessageRepository.java      # 消息数据访问
│       ├── ModelParamPresetRepository.java # 参数预设数据访问
│       ├── RagDocumentRepository.java  # RAG 文档元信息数据访问
│       └── UserRepository.java         # 用户数据访问
├── src/main/resources/
│   ├── static/
│   │   ├── css/
│   │   │   └── chat.css                # 聊天界面样式
│   │   └── js/
│   │       └── chat.js                 # 前端交互逻辑（SSE/RAG/Markdown）
│   ├── templates/
│   │   ├── index.html                  # 主聊天页面模板
│   │   └── login.html                  # 登录/注册页面模板
│   ├── application.yml                 # 主配置文件
│   ├── application-h2.yml              # H2 数据库配置
│   ├── application-postgresql.yml      # PostgreSQL + PgVector 配置
│   ├── application-ollama.yml          # Ollama 模型配置
│   ├── application-openai.yml          # OpenAI 模型配置
│   └── application-deepseek.yml        # DeepSeek 模型配置
├── pom.xml                             # Maven 配置文件
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

### 完整配置示例

#### 示例1：Ollama + H2（本地开发）
```yaml
spring:
  profiles:
    active: ollama,h2
rag:
  store-type: memory
```

#### 示例2：DeepSeek + OpenAI + H2（在线模式开发）
```yaml
spring:
  profiles:
    active: deepseek,openai,h2
```

#### 示例3：DeepSeek + PostgreSQL（生产部署）
```yaml
spring:
  profiles:
    active: deepseek,openai,postgresql
rag:
  store-type: pgvector
  pgvector:
    dimensions: 768
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

**详细 API 文档和示例请参考：** [API_TEST.md](API_TEST.md)

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

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Spring Security 官方文档](https://spring.io/projects/spring-security)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

ylyan2015
