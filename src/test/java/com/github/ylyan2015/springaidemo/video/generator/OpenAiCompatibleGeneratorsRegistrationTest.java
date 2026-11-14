package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.config.ImageGeneratorConfig;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 四家 OpenAI 兼容提供者注册测试：配置类产出四个生成器 Bean，
 * 工厂自动收录（List 注入），配置驱动与运行时切换沿用现有机制，万相保持默认。
 */
class OpenAiCompatibleGeneratorsRegistrationTest {

    private final ImageGeneratorConfig config = new ImageGeneratorConfig();
    private final StorageService storage = mock(StorageService.class);

    private List<ImageGenerator> fourProviders() {
        return List.of(
                config.zhipuImageGenerator(storage, "https://open.bigmodel.cn/api/paas/v4",
                        "sk-z", "cogview-3-flash", "1024x1024,1344x768,768x1344"),
                config.siliconflowImageGenerator(storage, "https://api.siliconflow.cn/v1",
                        "sk-s", "Kwai-Kolors/Kolors", ""),
                config.openAiImageGenerator(storage, "https://api.openai.com/v1",
                        "sk-o", "gpt-image-1", "1024x1024,1536x1024,1024x1536"),
                config.arkImageGenerator(storage, "https://ark.cn-beijing.volces.com/api/v3",
                        "sk-a", "doubao-seedream-4-0-250828", ""));
    }

    @Test
    void fourProvidersHaveDistinctTypesAndNames() {
        List<ImageGenerator> providers = fourProviders();

        assertEquals(List.of("zhipu", "siliconflow", "openai", "ark"),
                providers.stream().map(ImageGenerator::getType).toList());
        assertTrue(providers.stream().allMatch(g -> g.getName() != null && !g.getName().isBlank()));
        assertTrue(providers.stream().allMatch(ImageGenerator::isAvailable), "Key 非空时应可用");
    }

    @Test
    void factoryRegistersAllSixAndSwitchesAtRuntime() {
        ImageGenerator dashscope = mock(ImageGenerator.class);
        org.mockito.Mockito.when(dashscope.getType()).thenReturn("dashscope");
        ImageGenerator local = mock(ImageGenerator.class);
        org.mockito.Mockito.when(local.getType()).thenReturn("local");

        List<ImageGenerator> all = new java.util.ArrayList<>(List.of(dashscope, local));
        all.addAll(fourProviders());
        ImageGeneratorFactory factory = new ImageGeneratorFactory(all);

        assertEquals(6, factory.getAllGenerators().size());
        factory.switchGenerator("zhipu");
        assertEquals("zhipu", factory.getActiveType());
        factory.switchGenerator("ark");
        assertEquals("ark", factory.getActiveType());
        assertThrows(IllegalArgumentException.class, () -> factory.switchGenerator("midjourney"));
    }

    @Test
    void emptyKeyProviderIsUnavailable() {
        ImageGenerator noKey = config.zhipuImageGenerator(storage,
                "https://open.bigmodel.cn/api/paas/v4", "", "cogview-3-flash", "");
        assertFalse(noKey.isAvailable());
    }
}