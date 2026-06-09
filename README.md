# Spring AI Demo - 多模型智能聊天应用

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，支持多种AI模型（Ollama、OpenAI、DeepSeek等）。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并支持灵活切换不同的AI模型提供商，包括本地部署的 Ollama 模型和在线 API 服务（OpenAI、DeepSeek等）。

### ✨ 核心特性

- ✅ **多轮对话支持** - 自动维护对话上下文，AI 能记住之前的聊天内容
- ✅ **会话管理** - 支持创建、查询、删除多个独立会话
- ✅ **历史持久化** - 使用 PostgreSQL/H2 数据库存储对话历史，可随时查阅
- ✅ **上下文记忆** - 可配置的上下文窗口，保留最近 N 轮对话
- ✅ **RESTful API** - 提供完整的 REST API 接口
- ✅ **Web 界面** - 美观的聊天界面，支持会话列表、实时对话
- ✅ **模型切换** - Web界面直接切换离线模式和在线专家模式

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4.1** - 应用框架
- **Spring AI 1.0.0** - AI 集成框架
- **Spring Data JPA** - 数据持久化
- **Thymeleaf** - 模板引擎
- **PostgreSQL/H2 Database** - 数据库（支持切换）
- **AI Models**: Ollama / OpenAI / DeepSeek（支持切换）
- **Maven** - 项目构建工具

## 前置要求

在运行本项目之前，请确保已安装以下软件：

1. **JDK 17** 或更高版本
2. **Maven 3.6+**
3. **AI模型服务**（至少选择一种）：
   - **Ollama**（本地模型）- 从 [https://ollama.com](https://ollama.com) 下载并安装
   - **OpenAI API Key** - 注册 OpenAI 账号获取
   - **DeepSeek API Key** - 注册 DeepSeek 账号获取

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
# 或其他模型：ollama pull llama3, ollama pull mistral 等
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

Web 界面提供以下功能：
- 📝 **会话管理** - 左侧边栏显示所有会话，点击切换
- ➕ **新建对话** - 点击“新对话”按钮创建新会话
- 💬 **实时聊天** - 输入消息并发送，支持 Enter 发送、Shift+Enter 换行
- 📜 **历史记录** - 自动加载当前会话的历史消息
- 🎨 **友好界面** - 清晰的消息气泡，区分用户和 AI 消息
- 🔄 **模型切换** - 侧边栏底部直接切换AI模型（离线模式/DeepSeek V4 Pro）

### 5. 使用 API 测试

如果你想通过 API 进行测试，可以使用以下方式：

#### 创建新会话
```bash
curl -X POST http://localhost:8080/api/chat/conversation
```

#### 发送消息（多轮对话）
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "your-session-id",
    "message": "你好，请介绍一下自己"
  }'
```

#### 获取对话历史
```bash
curl http://localhost:8080/api/chat/history/your-session-id
```

**详细 API 文档请参考：** [API_TEST.md](API_TEST.md)

## 项目结构

```
spring-ai-demo/
├── src/main/java/com/github/ylyan2015/springaidemo/
│   ├── SpringAiDemoApplication.java    # 应用主类
│   ├── controller/
│   │   ├── ChatController.java         # 聊天控制器（REST API）
│   │   ├── ModelController.java        # 模型管理控制器
│   │   └── PageController.java         # 页面控制器（Web 路由）
│   ├── service/
│   │   └── ChatService.java            # 聊天服务（业务逻辑）
│   ├── entity/
│   │   ├── Conversation.java           # 会话实体
│   │   └── Message.java                # 消息实体
│   └── repository/
│       ├── ConversationRepository.java # 会话数据访问
│       └── MessageRepository.java      # 消息数据访问
├── src/main/resources/
│   ├── static/
│   │   ├── css/
│   │   │   └── chat.css                # 聊天界面样式
│   │   └── js/
│   │       └── chat.js                 # 前端交互逻辑
│   ├── templates/
│   │   └── index.html                  # 主页面模板
│   └── application.yml                 # 应用配置文件
├── pom.xml                             # Maven 配置文件
├── README.md                           # 项目说明文档
└── API_TEST.md                         # API测试指南
```

## 配置说明

### Web界面模型切换（推荐）

应用启动后，可以在Web界面上直接切换AI模型：

1. 打开 http://localhost:8080
2. 在左侧边栏底部找到“模型”下拉菜单
3. 选择需要的模型：
   - **离线模式** - 使用本地Ollama模型，免费、隐私安全
   - **DeepSeek V4 Pro** - 在线专家模式，更强的推理能力

切换后会立即生效，无需重启应用。切换成功会在右上角显示绿色提示。

### 配置文件模型切换

本项目支持多种AI模型，通过 Spring Profile 轻松切换：

#### 支持的模型

1. **Ollama**（本地模型） - `application-ollama.yml`
   - 模型：qwen2.5:7b-instruct, llama3, mistral, gemma2 等
   - 优势：免费、隐私安全、离线可用

2. **OpenAI** - `application-openai.yml`
   - 模型：gpt-3.5-turbo, gpt-4, gpt-4-turbo 等
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
    active: ollama  # 可选: ollama, openai, deepseek
```

**方式二：启动时指定**

```bash
# 使用 Ollama
mvn spring-boot:run -Dspring-boot.run.profiles=ollama

# 使用 OpenAI
mvn spring-boot:run -Dspring-boot.run.profiles=openai

# 使用 DeepSeek
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

**方式三：环境变量**

```bash
export SPRING_PROFILES_ACTIVE=openai
mvn spring-boot:run
```

### 数据库模式切换

本项目支持 PostgreSQL 和 H2 两种数据库模式，可通过以下方式切换：

#### 方式一：修改 application.yml

在 `application.yml` 中修改 `spring.profiles.active` 的值（与模型配置组合使用）：

```yaml
spring:
  profiles:
    active: ollama,h2  # 使用Ollama模型 + H2数据库
    # active: openai,postgresql  # 使用OpenAI + PostgreSQL
```

**注意**：可以同时指定多个 profile，用逗号分隔。第一个是模型类型，第二个是数据库类型。

#### 方式二：启动时指定

```bash
# 使用 PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=ollama,postgresql

# 使用 H2
mvn spring-boot:run -Dspring-boot.run.profiles=ollama,h2

# 使用 OpenAI + PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=openai,postgresql
```

#### 方式三：通过环境变量

```bash
export SPRING_PROFILES_ACTIVE=deepseek,postgresql
mvn spring-boot:run
```

### PostgreSQL 配置

如果使用 PostgreSQL，请确保：

1. 已安装并启动 PostgreSQL 服务
2. 创建数据库：`CREATE DATABASE chatdb;`
3. 在 `application-postgresql.yml` 中配置正确的用户名和密码

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatdb
    driver-class-name: org.postgresql.Driver
    username: postgres
    password: postgres  # 修改为你的密码
```

### H2 配置

H2 使用文件持久化模式，数据会保存在 `./data/chatdb.mv.db` 文件中：

- **数据存储位置**：项目根目录下的 `data` 文件夹
- **H2 控制台**：访问 http://localhost:8080/h2-console
- **JDBC URL**：`jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- **用户名**：`sa`
- **密码**：(留空)

**优势**：
- ✅ 数据持久化，应用重启后数据不丢失
- ✅ 无需安装额外的数据库服务
- ✅ 适合开发和小型项目

### 完整配置示例

#### 示例1：Ollama + H2（默认配置）
```yaml
spring:
  profiles:
    active: ollama,h2
```

#### 示例2：OpenAI + PostgreSQL
```yaml
spring:
  profiles:
    active: openai,postgresql
```

#### 示例3：DeepSeek + H2
```yaml
spring:
  profiles:
    active: deepseek,h2
```

你可以根据需要修改：
- **模型配置**：在对应的 `application-{model}.yml` 文件中修改
  - Ollama: `base-url`, `model`
  - OpenAI: `api-key`, `model`
  - DeepSeek: `base-url`, `api-key`, `model`
- `max-context-messages`: 上下文记忆长度，控制 AI 能记住多少轮对话
- `port`: 应用运行端口

## API 接口

### 1. 创建会话

- **URL**: `/api/chat/conversation`
- **方法**: POST
- **响应**: 返回新创建的会话ID

### 2. 发送消息

- **URL**: `/api/chat/send`
- **方法**: POST
- **请求体**:
  ```json
  {
    "sessionId": "会话ID（可选，不传则自动创建）",
    "message": "用户消息"
  }
  ```
- **响应**: AI 回复内容

### 3. 获取对话历史

- **URL**: `/api/chat/history/{sessionId}`
- **方法**: GET
- **响应**: 该会话的所有历史消息

### 4. 删除会话

- **URL**: `/api/chat/conversation/{sessionId}`
- **方法**: DELETE
- **响应**: 操作结果

**详细 API 文档和示例请参考：** [API_TEST.md](API_TEST.md)
```

## 开发指南

### 多轮对话工作原理

1. **会话创建**：每个对话会话有唯一 ID（UUID）
2. **消息存储**：用户消息和 AI 回复都保存到数据库
3. **上下文构建**：发送新消息时，自动获取最近 N 轮对话历史
4. **AI 调用**：将完整对话历史发送给 AI 模型，实现上下文记忆
5. **结果保存**：AI 回复也保存到数据库，供后续使用

### 添加新的 AI 功能

可以参考 `ChatService` 中的实现：

```java
@Service
public class MyService {
    private final ChatClient chatClient;
    
    public String processMessage(String userMessage) {
        // 构建消息列表
        List<Message> messages = Arrays.asList(
            new SystemMessage("你是一个专业的助手"),
            new UserMessage(userMessage)
        );
        
        // 调用 AI
        return chatClient.prompt()
                .messages(messages)
                .call()
                .content();
    }
}
```

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

### 3. 模型切换失败

- 确保对应的模型服务已配置（Ollama服务启动或API Key正确）
- 检查网络连接（在线模型需要联网）
- 查看控制台日志获取详细错误信息

### 4. 端口冲突

如果 8080 端口被占用，可以在 `application.yml` 中修改端口号。

### 5. AI 不记得之前的对话

确保你使用了相同的 `sessionId`。如果不传 sessionId，每次都会创建新会话。

### 6. 如何切换模型？

#### 方式一：Web界面切换（推荐）

直接在Web界面上切换，无需重启应用：
1. 打开 http://localhost:8080
2. 在左侧边栏底部选择模型
3. 切换后立即生效

#### 方式二：配置文件切换

参考上面的“配置文件模型切换”章节，可以通过修改配置文件、命令行参数或环境变量来切换。

### 7. 如何查看数据库中的数据？

#### H2 数据库

访问 H2 控制台：http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE`
- Username: `sa`
- Password: (留空)

**注意**：H2 使用文件持久化模式，数据存储在 `./data/chatdb.mv.db` 文件中，应用重启后数据不会丢失。

#### PostgreSQL 数据库

使用任何 PostgreSQL 客户端工具连接：
- Host: `localhost`
- Port: `5432`
- Database: `chatdb`
- Username: `postgres`
- Password: `postgres`（或你配置的密码）

常用工具推荐：
- pgAdmin
- DBeaver
- DataGrip
- psql 命令行工具

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

ylyan2015