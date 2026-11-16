package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.SpringAiDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 切片 1：comfyui Bean 全上下文注册 + 默认零变化（none 激活）+ 干净启动安全（未启动 ComfyUI 时不可用）
 */
@SpringBootTest(classes = SpringAiDemoApplication.class)
class ComfyUiRegistrationTest {

    @Autowired VideoGeneratorFactory factory;

    @Test
    void comfyuiRegisteredAndDefaultNone() {
        assertTrue(factory.getAllGenerators().containsKey("comfyui"));
        assertEquals("none", factory.getActiveType()); // yml 默认不变
        // 可用性断言不依赖外部环境（ComfyUI 启动后全量回归仍绿）；探测逻辑由 mock 单测覆盖
    }
}