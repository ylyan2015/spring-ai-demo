package com.github.ylyan2015.springaidemo.dto;

/**
 * 图像生成请求 DTO
 */
public class ImageGenerateRequest {

    /** 生成提示词（必填） */
    private String prompt;

    /** 图像尺寸（默认 1024x1024） */
    private String size = "1024x1024";

    /** 生成图片数量（默认 1，最大 4） */
    private Integer n = 1;

    /** 图像质量（标准/高清，仅部分模型支持） */
    private String quality;

    /** 图像风格（如：写实/插画/水彩等，仅部分模型支持） */
    private String style;

    /** 关联的聊天会话ID（可选） */
    private String sessionId;

    public ImageGenerateRequest() {
    }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public Integer getN() { return n; }
    public void setN(Integer n) { this.n = n; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
