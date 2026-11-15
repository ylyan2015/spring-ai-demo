package com.github.ylyan2015.springaidemo.video.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成器工厂（Phase 4 切片 2，复刻 ImageGeneratorFactory）
 * 配置 video.generator 默认 none；未实现 type（kling/seedance 预留）回退 none + warn。
 */
@Component
public class VideoGeneratorFactory {

    private static final Logger log = LoggerFactory.getLogger(VideoGeneratorFactory.class);

    private final Map<String, VideoGenerator> generators = new LinkedHashMap<>();
    private volatile String activeType;

    @Value("${video.generator:none}")
    private String configuredType;

    public VideoGeneratorFactory(List<VideoGenerator> generatorList) {
        for (VideoGenerator generator : generatorList) {
            generators.put(generator.getType(), generator);
        }
        log.info("Registered video generators: {}", generators.keySet());
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        String type = configuredType == null || configuredType.isBlank() ? "none" : configuredType;
        if (generators.containsKey(type)) {
            this.activeType = type;
        } else {
            this.activeType = "none";
            log.warn("配置的视频生成器 '{}' 未实现（kling/seedance 为预留扩展），回退 none", type);
        }
        log.info("Active video generator: {}", activeType);
    }

    public VideoGenerator getActiveGenerator() {
        return generators.get(activeType);
    }

    public void switchGenerator(String type) {
        if (!generators.containsKey(type)) {
            throw new IllegalArgumentException("不支持的视频生成器: " + type + "，可用: " + generators.keySet());
        }
        this.activeType = type;
        log.info("Video generator switched to: {}", type);
    }

    public String getActiveType() {
        return activeType;
    }

    public Map<String, VideoGenerator> getAllGenerators() {
        return generators;
    }
}