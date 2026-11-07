package com.github.ylyan2015.springaidemo.video.generator;

/**
 * 图像生成结果 DTO
 * 统一封装云端和本地生成器的输出
 */
public class GeneratedImage {

    /** 图片访问地址 */
    private String imageUrl;

    /** 存储服务中的文件ID */
    private String fileId;

    /** 使用的生成器类型（dashscope / local） */
    private String generatorType;

    /** 图片宽度 */
    private int width;

    /** 图片高度 */
    private int height;

    public GeneratedImage() {}

    public GeneratedImage(String imageUrl, String fileId, String generatorType, int width, int height) {
        this.imageUrl = imageUrl;
        this.fileId = fileId;
        this.generatorType = generatorType;
        this.width = width;
        this.height = height;
    }

    // ==================== Getters and Setters ====================

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getGeneratorType() { return generatorType; }
    public void setGeneratorType(String generatorType) { this.generatorType = generatorType; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
}
