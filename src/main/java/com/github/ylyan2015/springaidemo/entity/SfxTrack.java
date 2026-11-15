package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 音效库条目（Phase 4 切片 4：分层混音）
 * 内置音效文件名格式 category__name.mp3，启动扫描自动注册
 */
@Entity
@Table(name = "sfx_tracks")
public class SfxTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 500)
    private String filePath;

    private Integer durationSeconds;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SfxTrack() {}

    public SfxTrack(String name, String filePath, String category) {
        this.name = name;
        this.filePath = filePath;
        this.category = category;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}