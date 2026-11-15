package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 切片 3 真实冒烟（默认跳过）：-Dwan27=true + DASHSCOPE_API_KEY。
 * 抽卡 3 图目视评分排序 + 框选重绘一次目视；产物路径打印供人工检查。
 */
@SpringBootTest
class Wan27CandidateSmokeTest {

    @Autowired
    Wan27ImageGenerator wan27;

    @Autowired
    com.github.ylyan2015.springaidemo.storage.StorageService storage;

    @Value("${spring.ai.dashscope.api-key:}")
    String apiKey;

    @Test
    void threeCandidatesAndOneInpaint() throws Exception {
        assumeTrue(Boolean.getBoolean("wan27"), "真实冒烟需 -Dwan27=true");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "需 DASHSCOPE_API_KEY");
        String prompt = "anime style, a girl with long black hair holding an umbrella in rain, medium shot";
        GeneratedImage first = null;
        for (int i = 1; i <= 3; i++) {
            GeneratedImage img = wan27.generateWithReferences(prompt, null, 1280, 720, List.of());
            assertNotNull(img.getImageUrl());
            System.out.println("[candidate-smoke] draw " + i + " -> " + img.getImageUrl());
            if (first == null) {
                first = img;
            }
        }
        // 框选重绘：左上 20% 起 40%x40% 区域，把雨伞换成红色；本地存储转 base64 data URI
        String source = first.getImageUrl();
        if (!source.startsWith("http")) {
            try (InputStream in = storage.download(first.getFileId())) {
                source = "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
            }
        }
        GeneratedImage edited = wan27.editWithMask("replace the umbrella with a red one, keep everything else unchanged",
                source, new double[]{0.2, 0.2, 0.4, 0.4}, first.getWidth(), first.getHeight());
        assertNotNull(edited.getImageUrl());
        System.out.println("[candidate-smoke] inpaint -> " + edited.getImageUrl());
    }
}