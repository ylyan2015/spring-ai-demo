package com.github.ylyan2015.springaidemo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI模型配置类
 * 根据不同的Profile激活不同的模型客户端
 * 通过 rag.store-type 配置切换向量存储：memory（内存，测试用）或 pgvector（持久化，生产用）
 */
@Configuration
public class AiModelConfig {

    /**
     * DashScope 云端 embedding 配置（rag.embedding-model=dashscope 时使用）。
     * DeepSeek 官方不提供 embedding API，云端向量化走通义 OpenAI 兼容端点。
     */
    record DashScopeEmbeddingSettings(String baseUrl, String apiKey, String model, int dimensions) {}

    /**
     * OpenAI 云端 embedding 配置（rag.embedding-model=openai 时使用；自建客户端，不依赖自动配置 Bean）
     */
    record OpenAiEmbeddingSettings(String baseUrl, String apiKey, String model, int dimensions) {}

    /**
     * DashScope embedding 配置绑定（供 VectorStore Bean 注入；默认值与 application.yml 一致）
     */
    @Bean
    public DashScopeEmbeddingSettings dashScopeEmbeddingSettings(
            @Value("${rag.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${rag.dashscope.api-key:}") String apiKey,
            @Value("${rag.dashscope.model:text-embedding-v4}") String model,
            @Value("${rag.dashscope.dimensions:768}") int dimensions) {
        return new DashScopeEmbeddingSettings(baseUrl, apiKey, model, dimensions);
    }

    /**
     * OpenAI embedding 配置绑定（api-key 由 openai profile 或 OPENAI_API_KEY 环境变量提供）
     */
    @Bean
    public OpenAiEmbeddingSettings openAiEmbeddingSettings(
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${rag.openai.embedding-model:text-embedding-3-small}") String model,
            @Value("${rag.openai.dimensions:1536}") int dimensions) {
        return new OpenAiEmbeddingSettings(baseUrl, apiKey, model, dimensions);
    }

    /**
     * 解析 EmbeddingModel：根据 rag.embedding-model 配置选择 ollama（默认）/ openai / dashscope（配置+重启生效）
     */
    EmbeddingModel resolveEmbeddingModel(ApplicationContext ctx, String embeddingProvider,
                                         DashScopeEmbeddingSettings dashScope, OpenAiEmbeddingSettings openAi) {
        if ("dashscope".equalsIgnoreCase(embeddingProvider)) {
            return buildOpenAiCompatibleEmbedding(dashScope.baseUrl(), dashScope.apiKey(),
                    dashScope.model(), dashScope.dimensions(), "DASHSCOPE_API_KEY");
        } else if ("openai".equalsIgnoreCase(embeddingProvider)) {
            return buildOpenAiCompatibleEmbedding(openAi.baseUrl(), openAi.apiKey(),
                    openAi.model(), openAi.dimensions(), "OPENAI_API_KEY（或激活 openai profile）");
        } else {
            return ctx.getBean("ollamaEmbeddingModel", EmbeddingModel.class);
        }
    }

    /**
     * 自建 OpenAI 兼容 embedding 客户端（DashScope/OpenAI 共用；不依赖自动配置 Bean，
     * 因 spring.ai.model.embedding 开关全局为 ollama）；Key 缺失 fail-fast
     */
    private EmbeddingModel buildOpenAiCompatibleEmbedding(String baseUrl, String apiKey,
                                                          String model, int dimensions, String keyHint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("rag.embedding-model 云端提供者需要设置环境变量 " + keyHint);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
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
            @Value("${rag.embedding-model:ollama}") String embeddingProvider,
            DashScopeEmbeddingSettings dashScope,
            OpenAiEmbeddingSettings openAi) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(ctx, embeddingProvider, dashScope, openAi);
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
            DashScopeEmbeddingSettings dashScope,
            OpenAiEmbeddingSettings openAi,
            @Value("${rag.pgvector.dimensions:768}") int dimensions,
            @Value("${rag.pgvector.initialize-schema:true}") boolean initializeSchema) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(ctx, embeddingProvider, dashScope, openAi);
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
