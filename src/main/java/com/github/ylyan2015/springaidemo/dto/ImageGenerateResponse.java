package com.github.ylyan2015.springaidemo.dto;

import java.time.LocalDateTime;

/**
 * 图像生成响应 DTO
 */
public class ImageGenerateResponse {

    /** 图片访问 URL */
    private String imageUrl;

    /** 图片标识（可用于后续管理） */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 存储类型 */
    private String storageType;

    /** 生成提示词 */
    private String prompt;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 图像宽度 */
    private Integer width;

    /** 图像高度 */
    private Integer height;

    /** 生成时间 */
    private LocalDateTime createTime;

    /** 关联的会话ID */
    private String sessionId;

    public ImageGenerateResponse() {
    }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
