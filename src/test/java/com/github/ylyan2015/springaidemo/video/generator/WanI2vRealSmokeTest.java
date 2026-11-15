package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 切片 2 真实冒烟（默认跳过）：-Di2v=true + DASHSCOPE_API_KEY。
 * 首帧图 → wan i2v 4s 720P → 断言转存 mp4 存在且实测时长 ≥2s；产物路径打印供目视。
 */
@SpringBootTest
class WanI2vRealSmokeTest {

    @Autowired
    DashScopeWanVideoGenerator generator;

    @Autowired
    Wan27ImageGenerator wan27;

    @Autowired
    StorageService storage;

    @Autowired
    VideoComposeService composeService;

    @Value("${spring.ai.dashscope.api-key:}")
    String apiKey;

    @Test
    void firstFrameToVideoFourSeconds() throws Exception {
        assumeTrue(Boolean.getBoolean("i2v"), "真实冒烟需 -Di2v=true");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "需 DASHSCOPE_API_KEY");
        // 造一张首帧图；公网 URL 直用，本地存储转 base64 data URI
        GeneratedImage frame = wan27.generateWithReferences(
                "anime style, a girl with long black hair standing under rain, close-up, cinematic lighting",
                null, 1280, 720, List.of());
        assertNotNull(frame.getImageUrl());
        String firstFrame = frame.getImageUrl();
        if (!firstFrame.startsWith("http")) {
            try (InputStream in = storage.download(frame.getFileId())) {
                firstFrame = "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
            }
        }
        GeneratedVideo video = generator.generate(new VideoGenerationRequest(
                "subtle motion, slow camera, rain falling, no large deformation",
                firstFrame, null, 4, "720P"));
        assertNotNull(video.getVideoUrl());
        assertNotNull(video.getFileId());
        Path mp4 = Files.createTempFile("i2v-smoke-", ".mp4");
        try (InputStream in = storage.download(video.getFileId())) {
            Files.copy(in, mp4, StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(Files.size(mp4) > 0, "转存 mp4 为空");
        int duration = composeService.probeDurationSeconds(mp4);
        assertTrue(duration >= 2, "i2v 产物时长失控: " + duration);
        System.out.println("[i2v-smoke] video -> " + mp4 + " (" + duration + "s), url=" + video.getVideoUrl());
    }
}