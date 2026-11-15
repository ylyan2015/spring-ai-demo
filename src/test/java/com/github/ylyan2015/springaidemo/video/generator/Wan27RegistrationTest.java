package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.config.ImageGeneratorConfig;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 4 切片 1：wan27 注册/工厂切换/空 Key 不可用（干净启动安全）
 */
class Wan27RegistrationTest {

    private final ImageGeneratorConfig config = new ImageGeneratorConfig();
    private final StorageService storage = mock(StorageService.class);

    @Test
    void factoryRegistersWan27AndSwitches() {
        Wan27ImageGenerator wan27 = config.wan27ImageGenerator(storage,
                "https://dashscope.aliyuncs.com/api/v1", "sk-test", "wan2.7-image", 120);
        ImageGeneratorFactory factory = new ImageGeneratorFactory(List.of(wan27));
        assertTrue(factory.getAllGenerators().containsKey("wan27"));
        factory.switchGenerator("wan27");
        assertEquals("wan27", factory.getActiveType());
        assertTrue(factory.getActiveGenerator().supportsReferences());
    }

    @Test
    void emptyKeyWan27Unavailable() {
        Wan27ImageGenerator noKey = config.wan27ImageGenerator(storage,
                "https://dashscope.aliyuncs.com/api/v1", "", "wan2.7-image", 120);
        assertFalse(noKey.isAvailable());
    }
}