# Spring AI Demo with Ollama

一个基于 Spring Boot 3.4.1 和 Spring AI 1.0.0 的简单聊天应用示例，使用 Ollama 作为本地大语言模型服务。

## 项目概述

本项目演示了如何在 Spring Boot 应用中集成 Spring AI 框架，并通过 Ollama 调用本地部署的大语言模型（如 Llama3）实现智能对话功能。

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4.1** - 应用框架
- **Spring AI 1.0.0** - AI 集成框架
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

应用启动后，访问以下 URL 进行测试：

```
http://localhost:8080/chat?message=你好，请介绍一下自己
```

或者使用默认消息：

```
http://localhost:8080/chat
```

## 项目结构

```
spring-ai-demo/
├── src/main/java/com/github/ylyan2015/springaidemo/
│   ├── SpringAiDemoApplication.java    # 应用主类
│   └── controller/
│       └── ChatController.java         # 聊天控制器
├── src/main/resources/
│   └── application.yml                 # 应用配置文件
├── pom.xml                             # Maven 配置文件
└── README.md                           # 项目说明文档
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
        model: llama3                    # 使用的模型名称
```

你可以根据需要修改：
- `base-url`: Ollama 服务的地址
- `model`: 使用的模型名称（如 llama3、mistral、qwen 等）

## API 接口

### 聊天接口

- **URL**: `/chat`
- **方法**: GET
- **参数**: 
  - `message` (可选): 用户输入的消息，默认为 "Hello, how are you?"
- **响应**: 模型生成的回复文本

### 示例请求

```bash
curl "http://localhost:8080/chat?message=什么是Spring%20AI？"
```

## 开发指南

### 添加新的 AI 功能

1. 注入 `ChatClient.Builder`
2. 构建 `ChatClient` 实例
3. 使用 `prompt()` 方法创建对话
4. 调用 `call().content()` 获取响应

示例代码：

```java
@RestController
public class MyController {
    private final ChatClient chatClient;

    public MyController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/my-endpoint")
    public String myMethod(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
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
ollama pull llama3
```

### 3. 端口冲突

如果 8080 端口被占用，可以在 `application.yml` 中修改端口号。

## 参考资料

- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Ollama 官方网站](https://ollama.com)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 许可证

本项目仅供学习和演示使用。

## 作者

com.github.ylyan2015