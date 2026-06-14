package com.github.ylyan2015.springaidemo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
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
     * RAG 向量存储（内存向量库，应用重启后清空）
     * 通过 rag.embedding-model 配置选择 embedding 提供者：ollama（默认）或 openai
     */
    @Bean
    public VectorStore vectorStore(
            ApplicationContext ctx,
            @Value("${rag.embedding-model:ollama}") String embeddingProvider) {
        EmbeddingModel embeddingModel;
        if ("openai".equalsIgnoreCase(embeddingProvider)) {
            embeddingModel = ctx.getBean("openAiEmbeddingModel", EmbeddingModel.class);
        } else {
            embeddingModel = ctx.getBean("ollamaEmbeddingModel", EmbeddingModel.class);
        }
        System.out.println("✓ RAG VectorStore 使用 Embedding 提供者: " + embeddingProvider);
        return SimpleVectorStore.builder(embeddingModel).build();
    }

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
