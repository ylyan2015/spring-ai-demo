# Spring AI Demo with Ollama

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的智能聊天应用示例，使用 Ollama 作为本地大语言模型服务。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并通过 Ollama 调用本地部署的大语言模型实现智能对话功能。

### ✨ 核心特性

- ✅ **多轮对话支持** - 自动维护对话上下文，AI 能记住之前的聊天内容
- ✅ **会话管理** - 支持创建、查询、删除多个独立会话
- ✅ **历史持久化** - 使用 H2 数据库存储对话历史，可随时查阅
- ✅ **上下文记忆** - 可配置的上下文窗口，保留最近 N 轮对话
- ✅ **RESTful API** - 提供完整的 REST API 接口

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4.1** - 应用框架
- **Spring AI 1.0.0** - AI 集成框架
- **Spring Data JPA** - 数据持久化
- **H2 Database** - 内存数据库
- **Ollama** - 本地大语言模型服务
- **Maven** - 项目构建工具

## 前置要求

在运行本项目之前，请确保已安装以下软件：

1. **JDK 17** 或更高版本
2. **Maven 3.6+**
3. **Ollama** - 需要从 [https://ollama.com](https://ollama.com) 下载并安装
4. **Llama3 模型** - 通过 `ollama pull llama3` 命令下载

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd spring-ai-demo
```

### 2. 启动 Ollama 服务

确保 Ollama 服务正在运行：

```bash
# 启动 Ollama 服务
ollama serve

# 在另一个终端中拉取 Llama3 模型
ollama pull llama3
```

### 3. 构建并运行应用

```bash
# 使用 Maven 构建项目
mvn clean install

# 运行应用
mvn spring-boot:run
```

或者直接运行主类 `SpringAiDemoApplication`。

### 4. 测试聊天功能

应用启动后，可以使用以下 API 进行测试：

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
│   │   └── ChatController.java         # 聊天控制器（REST API）
│   ├── service/
│   │   └── ChatService.java            # 聊天服务（业务逻辑）
│   ├── entity/
│   │   ├── Conversation.java           # 会话实体
│   │   └── Message.java                # 消息实体
│   └── repository/
│       ├── ConversationRepository.java # 会话数据访问
│       └── MessageRepository.java      # 消息数据访问
├── src/main/resources/
│   └── application.yml                 # 应用配置文件
├── pom.xml                             # Maven 配置文件
├── README.md                           # 项目说明文档
└── API_TEST.md                         # API测试指南
```

## 配置说明

主要配置在 `application.yml` 文件中：

```yaml
server:
  port: 8080

spring:
  ai:
    ollama:
      base-url: http://localhost:11434  # Ollama 服务地址
      chat:
        model: qwen2.5:7b-instruct      # 使用的模型名称
  
  # H2 数据库配置
  datasource:
    url: jdbc:h2:mem:chatdb
    driver-class-name: org.h2.Driver
    username: sa
    password:
  
  # JPA 配置
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
  
  # H2 Console (访问 http://localhost:8080/h2-console)
  h2:
    console:
      enabled: true
      path: /h2-console

# Chat 配置
chat:
  max-context-messages: 10  # 保留最近10轮对话作为上下文
```

你可以根据需要修改：
- `base-url`: Ollama 服务的地址
- `model`: 使用的模型名称（如 llama3、mistral、qwen 等）
- `max-context-messages`: 上下文记忆长度，控制 AI 能记住多少轮对话

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

### 2. 模型未找到

确保已下载指定的模型：
```bash
ollama pull qwen2.5:7b-instruct
```

### 3. 端口冲突

如果 8080 端口被占用，可以在 `application.yml` 中修改端口号。

### 4. AI 不记得之前的对话

确保你使用了相同的 `sessionId`。如果不传 sessionId，每次都会创建新会话。

### 5. 如何查看数据库中的数据？

访问 H2 控制台：http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:chatdb`
- Username: `sa`
- Password: (留空)

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

com.github.ylyan2015