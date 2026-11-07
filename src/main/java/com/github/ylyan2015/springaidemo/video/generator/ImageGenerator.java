package com.github.ylyan2015.springaidemo.video.generator;

/**
 * 图像生成器统一接口
 * 抽象云端 API 和本地模型两种图像生成实现
 */
public interface ImageGenerator {

    /**
     * 生成单张图片
     *
     * @param prompt         正面提示词（英文）
     * @param negativePrompt 负面提示词（英文，可为 null）
     * @param width          图片宽度
     * @param height         图片高度
     * @return 生成结果
     */
    GeneratedImage generate(String prompt, String negativePrompt, int width, int height);

    /**
     * 生成器类型标识（如 dashscope / local）
     */
    String getType();

    /**
     * 生成器显示名称（如 "DashScope 通义万相"）
     */
    String getName();

    /**
     * 生成器当前是否可用
     * （如云端需 API Key 已配置，本地需服务可达）
     */
    boolean isAvailable();
}
