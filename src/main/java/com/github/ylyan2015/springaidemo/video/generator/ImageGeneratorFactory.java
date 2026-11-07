package com.github.ylyan2015.springaidemo.video.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图像生成器工厂
 * 根据配置 image.generator 动态选择图像生成实现（dashscope / local）
 * 支持运行时切换，参考 ModelService 的动态切换模式
 */
@Component
public class ImageGeneratorFactory {

    private static final Logger log = LoggerFactory.getLogger(ImageGeneratorFactory.class);

    private final Map<String, ImageGenerator> generators = new LinkedHashMap<>();
    private volatile String activeType;

    @Value("${image.generator:dashscope}")
    private String configuredType;

    public ImageGeneratorFactory(List<ImageGenerator> generatorList) {
        for (ImageGenerator generator : generatorList) {
            generators.put(generator.getType(), generator);
        }
        log.info("Registered image generators: {}", generators.keySet());
    }

    /**
     * 初始化激活的生成器（@PostConstruct 方式由 Spring 调用）
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        if (generators.containsKey(configuredType)) {
            this.activeType = configuredType;
        } else {
            // 配置无效时回退到首个可用的生成器
            this.activeType = generators.keySet().iterator().next();
            log.warn("配置的图像生成器 '{}' 不存在，回退到 '{}'", configuredType, activeType);
        }
        log.info("Active image generator: {} ({})", activeType, getActiveGenerator().getName());
    }

    /**
     * 获取当前激活的图像生成器
     */
    public ImageGenerator getActiveGenerator() {
        return generators.get(activeType);
    }

    /**
     * 运行时切换图像生成器
     */
    public void switchGenerator(String type) {
        if (!generators.containsKey(type)) {
            throw new IllegalArgumentException("不支持的图像生成器: " + type
                    + "，可用: " + generators.keySet());
        }
        this.activeType = type;
        log.info("Image generator switched to: {} ({})", type, generators.get(type).getName());
    }

    /**
     * 获取当前激活的生成器类型
     */
    public String getActiveType() {
        return activeType;
    }

    /**
     * 获取所有已注册的生成器（用于列表展示）
     */
    public Map<String, ImageGenerator> getAllGenerators() {
        return generators;
    }
}
