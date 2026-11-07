package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 背景音乐（BGM）实体类
 * 支持内置音乐库（扫描本地目录注册）与用户上传的音乐
 */
@Entity
@Table(name = "bgm_tracks")
public class Bgm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名称 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 音乐库内的相对文件路径（如 happy__sunny_day.mp3） */
    @Column(name = "file_path", nullable = false, length = 300)
    private String filePath;

    /** 风格分类: happy / sad / epic / calm / tense / other */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    /** 情绪标签（供 LLM 推荐参考，如 明快、欢快、治愈） */
    @Column(name = "mood", length = 200)
    private String mood;

    /** 音乐时长（秒），未知时为 null */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** 是否内置音乐（扫描音乐库目录自动注册） */
    @Column(name = "is_built_in", nullable = false)
    private Boolean isBuiltIn = true;

    /** 上传者用户ID（内置音乐为 null） */
    @Column(name = "user_id")
    private Long userId;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Bgm() {}

    public Bgm(String name, String filePath, String category, String mood, Boolean isBuiltIn) {
        this.name = name;
        this.filePath = filePath;
        this.category = category;
        this.mood = mood;
        this.isBuiltIn = isBuiltIn;
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Boolean getIsBuiltIn() { return isBuiltIn; }
    public void setIsBuiltIn(Boolean isBuiltIn) { this.isBuiltIn = isBuiltIn; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
