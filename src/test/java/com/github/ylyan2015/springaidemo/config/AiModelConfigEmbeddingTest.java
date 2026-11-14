package com.github.ylyan2015.springaidemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RAG embedding 提供者切换测试（dashscope 云端选项）：
 * rag.embedding-model 支持 ollama / openai / dashscope（配置+重启生效）；
 * dashscope 分支自建 OpenAI 兼容客户端（不依赖 openai profile），Key 缺失 fail-fast。
 */
class AiModelConfigEmbeddingTest {

    private final AiModelConfig config = new AiModelConfig();

    private AiModelConfig.DashScopeEmbeddingSettings settings(String apiKey) {
        return new AiModelConfig.DashScopeEmbeddingSettings(
                "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, "text-embedding-v4", 768);
    }

    private AiModelConfig.OpenAiEmbeddingSettings openAiSettings(String apiKey) {
        return new AiModelConfig.OpenAiEmbeddingSettings(
                "https://api.openai.com", apiKey, "text-embedding-3-small", 1536);
    }

    @Test
    void dashscopeBranchBuildsOpenAiCompatibleEmbeddingModel() {
        ApplicationContext ctx = mock(ApplicationContext.class);

        EmbeddingModel model = config.resolveEmbeddingModel(ctx, "dashscope", settings("sk-test"), openAiSettings("sk-test"));

        assertInstanceOf(OpenAiEmbeddingModel.class, model);
        // 不调 dimensions()：其默认实现会发真实 embedding 请求探维；维度正确性由真实连通验证保障
        // dashscope 分支自建客户端，不依赖 Spring 自动配置 Bean（无需 openai profile）
        verifyNoInteractions(ctx);
    }

    @Test
    void dashscopeBranchFailsFastWithoutApiKey() {
        ApplicationContext ctx = mock(ApplicationContext.class);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.resolveEmbeddingModel(ctx, "dashscope", settings(""), openAiSettings("sk-test")));
        assertTrue(e.getMessage().contains("DASHSCOPE_API_KEY"), "应提示设置环境变量: " + e.getMessage());
    }

    @Test
    void openaiBranchBuildsSelfManagedClient() {
        ApplicationContext ctx = mock(ApplicationContext.class);

        EmbeddingModel model = config.resolveEmbeddingModel(ctx, "openai", settings("sk-test"), openAiSettings("sk-test"));

        assertInstanceOf(OpenAiEmbeddingModel.class, model);
        // 自建客户端，不依赖自动配置 Bean（spring.ai.model.embedding 全局为 ollama）
        verifyNoInteractions(ctx);
    }

    @Test
    void openaiBranchFailsFastWithoutApiKey() {
        ApplicationContext ctx = mock(ApplicationContext.class);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.resolveEmbeddingModel(ctx, "openai", settings("sk-test"), openAiSettings("")));
        assertTrue(e.getMessage().contains("OPENAI_API_KEY"), "应提示设置环境变量: " + e.getMessage());
    }

    @Test
    void ollamaBranchStillResolvesAutoConfiguredBean() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        EmbeddingModel ollama = mock(EmbeddingModel.class);
        when(ctx.getBean("ollamaEmbeddingModel", EmbeddingModel.class)).thenReturn(ollama);

        assertSame(ollama, config.resolveEmbeddingModel(ctx, "ollama", settings("sk-test"), openAiSettings("sk-test")));
    }

    @Test
    void memoryVectorStoreBeanAcceptsDashscope() {
        ApplicationContext ctx = mock(ApplicationContext.class);

        VectorStore store = config.vectorStore(ctx, "dashscope", settings("sk-test"), openAiSettings("sk-test"));

        assertInstanceOf(SimpleVectorStore.class, store);
    }
}