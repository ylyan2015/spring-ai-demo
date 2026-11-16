package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 素材库实体（Phase 5 Track B Task 1：评论类动漫影视素材图片/视频切片）
 * 上传入库裁剪 + ffprobe 探测时长；归属 userId（服务层登录校验）
 */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 200)
    private String name;

    /** VIDEO / IMAGE */
    @Column(length = 10)
    private String kind;

    @Column(length = 500)
    private String fileId;

    @Column(length = 1000)
    private String url;

    /** 视频实测时长（秒，ffprobe）；图片为 null */
    private Integer durationSeconds;

    @Column(length = 500)
    private String tags;

    @Column(length = 500)
    private String sourceNote;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MediaAsset() {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSourceNote() { return sourceNote; }
    public void setSourceNote(String sourceNote) { this.sourceNote = sourceNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}