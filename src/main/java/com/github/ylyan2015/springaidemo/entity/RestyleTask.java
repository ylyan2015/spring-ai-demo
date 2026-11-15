package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 视频转绘任务（Phase 4 切片 5：独立输出模式，不触碰主 pipeline）
 * 规格细化：VideoExport.script_id NOT NULL 存量约束无法放宽，转绘无脚本归属，故独立实体。
 */
@Entity
@Table(name = "restyle_tasks")
public class RestyleTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 500)
    private String sourceFileId;

    @Column(length = 1000)
    private String sourceVideoUrl;

    @Column(length = 2000)
    private String stylePrompt;

    @Column(nullable = false, length = 50)
    private String provider = "dashscope-wan";

    /** PENDING / RUNNING / SUCCEEDED / FAILED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(length = 1000)
    private String resultUrl;

    @Column(length = 500)
    private String resultFileId;

    @Column(length = 1000)
    private String error;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public RestyleTask() {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(String sourceFileId) { this.sourceFileId = sourceFileId; }
    public String getSourceVideoUrl() { return sourceVideoUrl; }
    public void setSourceVideoUrl(String sourceVideoUrl) { this.sourceVideoUrl = sourceVideoUrl; }
    public String getStylePrompt() { return stylePrompt; }
    public void setStylePrompt(String stylePrompt) { this.stylePrompt = stylePrompt; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String resultUrl) { this.resultUrl = resultUrl; }
    public String getResultFileId() { return resultFileId; }
    public void setResultFileId(String resultFileId) { this.resultFileId = resultFileId; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}