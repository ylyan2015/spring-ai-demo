package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.generator.ComfyUiImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5 切片 3：本地 ComfyUI SD1.5+LoRA 真实冒烟（默认跳过；-Dcomfyui=true 执行，实测 ~8-10s/张）。
 * DEFINED_PORT(18081) 保证 serverPort 接线=实际端口（ControlNet 参考图相对 URL 同源取字节）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18081")
class ComfyUiImageRealSmokeTest {

    @Autowired ComfyUiImageGenerator gen;

    /** 非 gated 接线回归：RANDOM_PORT 会注入 server.port=0（切片 1 审查 Major#1 同款） */
    @Test
    void selfPortWiredForRelativeCnRef() {
        assertEquals(18081, org.springframework.test.util.ReflectionTestUtils.getField(gen, "serverPort"));
    }

    @Test
    void localLoraImageSmoke() {
        assumeTrue(Boolean.getBoolean("comfyui"), "真实冒烟需 -Dcomfyui=true");
        assumeTrue(gen.isAvailable(), "需 ComfyUI 启动且模板已校准");
        GeneratedImage img = gen.generateWithLora(
                "saki_chan, 1girl, school uniform, hallway, best quality, anime coloring",
                "low quality, blurry, watermark, text", 1280, 720,
                "saki_chan_lora.safetensors", null);
        assertNotNull(img.getFileId());
        assertEquals("comfyui", img.getGeneratorType());
        assertEquals(1280, img.getWidth());
        assertEquals(704, img.getHeight()); // 本机实测 704 高
    }
}