package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.dto.ImageGenerateRequest;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateResponse;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DashScope 云端图像生成器
 * 复用现有 ImageService 的通义万相 API 调用与存储逻辑
 * 注意：DashScope 当前不支持负面提示词，负面提示词会被忽略
 */
@Component
public class DashScopeImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(DashScopeImageGenerator.class);

    private final ImageService imageService;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    public DashScopeImageGenerator(ImageService imageService) {
        this.imageService = imageService;
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        log.info("Generating image via DashScope: size={}x{}, prompt='{}'",
                width, height, prompt != null && prompt.length() > 60 ? prompt.substring(0, 60) + "..." : prompt);

        if (negativePrompt != null && !negativePrompt.isBlank()) {
            log.debug("DashScope does not support negative prompt, ignoring: '{}'", negativePrompt);
        }

        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt(prompt);
        request.setSize(width + "x" + height);
        request.setN(1);

        ImageGenerateResponse response = imageService.generateImage(request);

        log.info("DashScope image generated: url={}", response.getImageUrl());
        return new GeneratedImage(
                response.getImageUrl(),
                response.getFileId(),
                getType(),
                response.getWidth() != null ? response.getWidth() : width,
                response.getHeight() != null ? response.getHeight() : height
        );
    }

    @Override
    public String getType() {
        return "dashscope";
    }

    @Override
    public String getName() {
        return "DashScope 通义万相 (wan2.6)";
    }

    @Override
    public boolean isAvailable() {
        return dashscopeApiKey != null && !dashscopeApiKey.isBlank();
    }
}
