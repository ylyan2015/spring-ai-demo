package com.github.ylyan2015.springaidemo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AI模型配置类
 * 根据不同的Profile激活不同的模型客户端
 * 通过 rag.store-type 配置切换向量存储：memory（内存，测试用）或 pgvector（持久化，生产用）
 */
@Configuration
public class AiModelConfig {

    /**
     * 解析 EmbeddingModel：根据 rag.embedding-model 配置选择 ollama（默认）或 openai
     */
    private EmbeddingModel resolveEmbeddingModel(ApplicationContext ctx, String embeddingProvider) {
        if ("openai".equalsIgnoreCase(embeddingProvider)) {
            return ctx.getBean("openAiEmbeddingModel", EmbeddingModel.class);
        } else {
            return ctx.getBean("ollamaEmbeddingModel", EmbeddingModel.class);
        }
    }

    /**
     * 内存向量存储（测试/开发模式）
     * 服务重启后数据清空，适合快速迭代和调试
     * 激活条件：rag.store-type=memory 或未配置时默认使用
     */
    @Bean
    @ConditionalOnProperty(name = "rag.store-type", havingValue = "memory", matchIfMissing = true)
    public VectorStore vectorStore(
            ApplicationContext ctx,
            @Value("${rag.embedding-model:ollama}") String embeddingProvider) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(ctx, embeddingProvider);
        System.out.println("✓ RAG VectorStore [内存模式] 使用 Embedding 提供者: " + embeddingProvider);
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * PgVector 持久化向量存储（生产模式）
     * 数据存储在 PostgreSQL 中，服务重启后数据保留
     * 激活条件：rag.store-type=pgvector，需同时激活 postgresql profile
     */
    @Bean
    @ConditionalOnProperty(name = "rag.store-type", havingValue = "pgvector")
    public VectorStore pgVectorStore(
            JdbcTemplate jdbcTemplate,
            ApplicationContext ctx,
            @Value("${rag.embedding-model:ollama}") String embeddingProvider,
            @Value("${rag.pgvector.dimensions:768}") int dimensions,
            @Value("${rag.pgvector.initialize-schema:true}") boolean initializeSchema) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(ctx, embeddingProvider);
        System.out.println("✓ RAG VectorStore [PgVector持久化模式] 使用 Embedding 提供者: " + embeddingProvider
                + ", 维度: " + dimensions);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .build();
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
