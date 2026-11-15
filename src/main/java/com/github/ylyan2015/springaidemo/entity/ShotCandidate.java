package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 镜头候选资产实体（Phase 4 切片 3：抽卡多候选与局部重绘）
 * type=IMAGE|VIDEO；selected 为人工择优闸门；重绘产物同样入候选不直接覆盖主资产
 */
@Entity
@Table(name = "shot_candidates")
public class ShotCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shotId;

    /** IMAGE | VIDEO */
    @Column(nullable = false, length = 10)
    private String type;

    @Column(length = 1024)
    private String url;

    @Column(length = 200)
    private String fileId;

    private Double qualityScore;

    /** 视频候选时长（秒）；图像候选为 null */
    private Integer durationSeconds;

    @Column(nullable = false)
    private boolean selected = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ShotCandidate() {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShotId() { return shotId; }
    public void setShotId(Long shotId) { this.shotId = shotId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}