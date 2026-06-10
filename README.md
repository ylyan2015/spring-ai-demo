# Spring AI Demo - 多模型智能聊天应用

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，支持多种AI模型（Ollama、OpenAI、DeepSeek等），内置用户注册登录系统。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并支持灵活切换不同的AI模型提供商，包括本地部署的 Ollama 模型和在线 API 服务（OpenAI、DeepSeek等）。同时提供完整的用户认证体系，聊天记录与用户绑定，保障数据隐私安全。

### ✨ 核心特性

- ✅ **用户认证系统** - 注册/登录功能，含随机验证码校验
- ✅ **密码安全传输** - RSA 非对称加密传输密码，BCrypt 加密存储
- ✅ **多轮对话支持** - 自动维护对话上下文，AI 能记住之前的聊天内容
- ✅ **会话管理** - 支持创建、查询、删除多个独立会话
- ✅ **聊天绑定用户** - 每个用户的聊天记录独立隔离，仅本人可查看和删除
- ✅ **历史持久化** - 使用 PostgreSQL/H2 数据库存储对话历史，可随时查阅
- ✅ **上下文记忆** - 可配置的上下文窗口，保留最近 N 轮对话
- ✅ **RESTful API** - 提供完整的 REST API 接口
- ✅ **Web 界面** - 美观的聊天界面，支持会话列表、实时对话
- ✅ **模型切换** - Web界面直接切换离线模式和在线专家模式

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4.1** - 应用框架
- **Spring AI 1.0.0** - AI 集成框架
- **Spring Security** - 安全认证框架
- **Spring Data JPA** - 数据持久化
- **Thymeleaf** - 模板引擎
- **PostgreSQL/H2 Database** - 数据库（支持切换）
- **AI Models**: Ollama / OpenAI / DeepSeek（支持切换）
- **RSA + BCrypt** - 密码加密方案
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
- 💬 **实时聊天** - 输入消息并发送，支持 Enter 发送、Shift+Enter 换行
- 📜 **历史记录** - 自动加载当前会话的历史消息
- 🗑️ **删除会话** - 用户只能删除自己的会话
- 🎨 **友好界面** - 清晰的消息气泡，区分用户和 AI 消息
- 🔄 **模型切换** - 侧边栏底部直接切换AI模型

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

#### 发送消息（需要已登录）
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
│   │   ├── AiModelConfig.java          # AI模型配置
│   │   ├── RsaKeyPairGenerator.java    # RSA密钥对生成器
│   │   └── SecurityConfig.java         # Spring Security配置
│   ├── controller/
│   │   ├── AuthController.java         # 认证控制器（注册/登录/验证码）
│   │   ├── ChatController.java         # 聊天控制器（REST API）
│   │   ├── ModelController.java        # 模型管理控制器
│   │   └── PageController.java         # 页面控制器（Web 路由）
│   ├── service/
│   │   ├── AuthService.java            # 认证服务（注册/登录逻辑）
│   │   ├── CaptchaService.java         # 验证码服务
│   │   └── ChatService.java            # 聊天服务（业务逻辑）
│   ├── entity/
│   │   ├── Conversation.java           # 会话实体（绑定userId）
│   │   ├── Message.java                # 消息实体
│   │   └── User.java                   # 用户实体
│   └── repository/
│       ├── ConversationRepository.java # 会话数据访问
│       ├── MessageRepository.java      # 消息数据访问
│       └── UserRepository.java         # 用户数据访问
├── src/main/resources/
│   ├── static/
│   │   ├── css/
│   │   │   └── chat.css                # 聊天界面样式
│   │   └── js/
│   │       └── chat.js                 # 前端交互逻辑
│   ├── templates/
│   │   ├── index.html                  # 主聊天页面模板
│   │   └── login.html                  # 登录/注册页面模板
│   └── application.yml                 # 应用配置文件
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
4. 前端使用 RSA 公钥加密密码（Web Crypto API）
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
| `/api/chat/conversation` | POST | 创建新会话 |
| `/api/chat/conversations` | GET | 获取当前用户的所有会话 |
| `/api/chat/send` | POST | 发送消息 |
| `/api/chat/history/{sessionId}` | GET | 获取对话历史 |
| `/api/chat/conversation/{sessionId}` | DELETE | 删除会话（仅本人） |

### 模型管理接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/model/current` | GET | 获取当前模型 |
| `/api/model/available` | GET | 获取可用模型列表 |
| `/api/model/switch` | POST | 切换模型 |

**详细 API 文档和示例请参考：** [API_TEST.md](API_TEST.md)

## 开发指南

### 多轮对话工作原理

1. **用户登录**：用户注册/登录后建立 Session
2. **会话创建**：每个对话会话有唯一 ID（UUID），并绑定到当前用户
3. **消息存储**：用户消息和 AI 回复都保存到数据库
4. **上下文构建**：发送新消息时，自动获取最近 N 轮对话历史
5. **AI 调用**：将完整对话历史发送给 AI 模型，实现上下文记忆
6. **结果保存**：AI 回复也保存到数据库，供后续使用

### 添加新的 AI 功能

可以参考 `ChatService` 中的实现：

```java
@Service
public class MyService {
    private final ChatClient chatClient;
    
    public String processMessage(String userMessage) {
        List<Message> messages = Arrays.asList(
            new SystemMessage("你是一个专业的助手"),
            new UserMessage(userMessage)
        );
        
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

参考上面的"配置文件模型切换"章节，可以通过修改配置文件、命令行参数或环境变量来切换。

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

### 8. 注册时提示"验证码错误"？

验证码区分大小写不敏感，但请确保在获取验证码后尽快输入。验证码一次性使用，提交后即失效，请重新获取。

### 9. 忘记密码怎么办？

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
