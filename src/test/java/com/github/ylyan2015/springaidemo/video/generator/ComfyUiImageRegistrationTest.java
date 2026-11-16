package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.SpringAiDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 切片 3：comfyui 图像 Bean 全上下文注册 + 默认零变化（dashscope 激活）
 */
@SpringBootTest(classes = SpringAiDemoApplication.class)
class ComfyUiImageRegistrationTest {

    @Autowired ImageGeneratorFactory factory;

    @Test
    void comfyuiRegisteredAndDefaultUnchanged() {
        assertTrue(factory.getAllGenerators().containsKey("comfyui"));
        // 默认零变化：comfyui 不自动激活（active 随环境 Key 回退，不依赖外部事实——切片 1 审查 Minor#2 同款）
        assertNotEquals("comfyui", factory.getActiveType());
        // 可用性断言不依赖外部环境；探测逻辑由 mock 单测覆盖
    }
}