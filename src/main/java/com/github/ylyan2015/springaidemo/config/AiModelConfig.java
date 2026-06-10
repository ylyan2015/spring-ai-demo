package com.github.ylyan2015.springaidemo.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * AI模型配置类
 * 根据不同的Profile激活不同的模型客户端
 */
@Configuration
public class AiModelConfig {

    /**
     * Ollama模型配置（本地模型）
     */
    @Configuration
    @Profile("ollama")
    static class OllamaConfig {
        // Spring AI会自动配置Ollama的ChatClient
    }

    /**
     * OpenAI模型配置
     */
    @Configuration
    @Profile("openai")
    static class OpenAiConfig {
        // Spring AI会自动配置OpenAI的ChatClient
    }

    /**
     * DeepSeek模型配置（使用OpenAI兼容API）
     */
    @Configuration
    @Profile("deepseek")
    static class DeepSeekConfig {
        // Spring AI会自动配置OpenAI兼容的ChatClient
    }
}
