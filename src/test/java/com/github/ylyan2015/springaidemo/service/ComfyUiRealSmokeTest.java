package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.ComfyUiVideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedVideo;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5 切片 1：本地 ComfyUI 真实冒烟（默认跳过；用户 Phase 0 环境就绪后 -Dcomfyui=true 执行）。
 * DEFINED_PORT(18080) 保证 Bean 的 server.port 接线=实际端口，首帧相对 URL 同源取字节可达；480P×2s 控制渲染成本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18080")
class ComfyUiRealSmokeTest {

    @Autowired ComfyUiVideoGenerator gen;
    @Autowired StorageService storage;

    /** 非 gated 接线回归：RANDOM_PORT 会注入 server.port=0 致同源取字节指向端口 0（审查 Major#1） */
    @Test
    void selfPortWiredForRelativeFirstFrame() {
        assertEquals(18080, org.springframework.test.util.ReflectionTestUtils.getField(gen, "serverPort"));
    }

    @Test
    void localI2vSmoke() throws Exception {
        assumeTrue(Boolean.getBoolean("comfyui"), "真实冒烟需 -Dcomfyui=true");
        assumeTrue(gen.isAvailable(), "需 ComfyUI 启动且工作流已校准");
        Path png = Files.createTempFile("comfyui-smoke-", ".png");
        // stdout 必须丢弃/消费，否则管道满致 waitFor 死锁（审查教训）
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=duration=1:size=640x360:rate=24", "-frames:v", "1", png.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, p.waitFor());
        String fileId = storage.upload(Files.newInputStream(png), "smoke.png", "image/png");
        GeneratedVideo v = gen.generate(new VideoGenerationRequest(
                "slow gentle motion, subtle camera", storage.getAccessUrl(fileId), null, 2, "720P"));
        assertNotNull(v.getFileId());
        assertEquals("comfyui", v.getGeneratorType());
    }
}