package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 图像生成记录实体
 * 记录每次 AI 图像生成的请求和结果信息
 */
@Entity
@Table(name = "image_records")
public class ImageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 生成提示词 */
    @Column(name = "prompt", nullable = false, length = 2000)
    private String prompt;

    /** 生成的图片访问URL */
    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;

    /** 文件ID/路径 */
    @Column(name = "file_id", length = 512)
    private String fileId;

    /** 原始文件名 */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /** 存储类型：local / oss / minio / fastdfs */
    @Column(name = "storage_type", nullable = false, length = 20)
    private String storageType;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 图像宽度 */
    @Column(name = "width")
    private Integer width;

    /** 图像高度 */
    @Column(name = "height")
    private Integer height;

    /** 关联的聊天会话ID（可选） */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    public ImageRecord() {
        this.createTime = LocalDateTime.now();
    }

    public ImageRecord(Long userId, String prompt, String imageUrl, String fileId, String fileName,
                       String storageType, Long fileSize, Integer width, Integer height) {
        this.userId = userId;
        this.prompt = prompt;
        this.imageUrl = imageUrl;
        this.fileId = fileId;
        this.fileName = fileName;
        this.storageType = storageType;
        this.fileSize = fileSize;
        this.width = width;
        this.height = height;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
