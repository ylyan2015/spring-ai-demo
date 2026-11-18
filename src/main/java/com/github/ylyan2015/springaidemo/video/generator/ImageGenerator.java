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
     * Phase 4 切片 1：参考图模式（角色一致性）。
     * 默认委托 generate 忽略 refs → 现有实现零改动向后兼容。
     */
    default GeneratedImage generateWithReferences(String prompt, String negativePrompt,
                                                  int width, int height, java.util.List<String> referenceImageUrls) {
        return generate(prompt, negativePrompt, width, height);
    }

    /** 是否支持参考图模式（默认 false） */
    default boolean supportsReferences() {
        return false;
    }

    /**
     * Phase 4 切片 3：框选局部重绘。
     * rect 为 0-1 相对坐标 [x,y,w,h]，imgW/imgH 为原图像素宽高（换算绝对像素 bbox）。
     * 默认不支持；不支持的生成器调用抛 UnsupportedOperationException。
     */
    default GeneratedImage editWithMask(String instruction, String imageUrl, double[] rect, int imgW, int imgH) {
        throw new UnsupportedOperationException("当前图像生成器不支持局部重绘: " + getType());
    }

    /** 是否支持框选局部重绘（默认 false） */
    default boolean supportsEdit() {
        return false;
    }

    /**
     * Phase 5 切片 3：角色 LoRA 模式（本地 SD1.5+LoRA 身份一致性）。
     * controlNetRefUrl 可为 null（不锁机位）；默认不支持，调用抛 UnsupportedOperationException。
     */
    default GeneratedImage generateWithLora(String prompt, String negativePrompt, int width, int height,
                                            String loraName, String controlNetRefUrl) {
        throw new UnsupportedOperationException("当前图像生成器不支持 LoRA: " + getType());
    }

    /** 是否支持角色 LoRA 模式（默认 false） */
    default boolean supportsLora() {
        return false;
    }

    /**
     * 素材库切片 S3：LoRA + 参考图组合模式。
     * 默认降级 generateWithReferences（uploadFinal 同款 default idiom）→ 其余 generator 零改动。
     */
    default GeneratedImage generateWithLoraAndReferences(String prompt, String negativePrompt,
                                                         int width, int height, String loraName,
                                                         String controlNetRefUrl,
                                                         java.util.List<String> referenceImageUrls) {
        return generateWithReferences(prompt, negativePrompt, width, height, referenceImageUrls);
    }

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
