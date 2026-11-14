package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 视频模板实体类（模块 H）
 * 预设视频风格模板：应用后自动注入脚本结构（镜头数）与风格化提示词引导，
 * 系统内置模板开箱即用，用户可另存自定义模板
 */
@Entity
@Table(name = "video_templates")
public class VideoTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板名称 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 分类: product / story / knowledge / travel / food / emotion / tech / fitness / other */
    @Column(name = "category", length = 50)
    private String category;

    /** 模板描述（前端选择提示） */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 推荐镜头数 */
    @Column(name = "shot_count")
    private Integer shotCount = 5;

    /** 风格化提示词引导（注入脚本生成 System Prompt，英文） */
    @Column(name = "style_prefix", columnDefinition = "TEXT")
    private String stylePrefix;

    /** 推荐 BGM 分类（happy/sad/epic/calm/tense） */
    @Column(name = "default_bgm_category", length = 50)
    private String defaultBgmCategory;

    /** 推荐 TTS 声音（Edge TTS voice） */
    @Column(name = "default_voice", length = 50)
    private String defaultVoice;

    /** 是否系统内置模板（内置模板不可删除） */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    /** 创建者用户ID（自定义模板；系统模板为 null） */
    @Column(name = "user_id")
    private Long userId;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public VideoTemplate() {
        this.createdAt = LocalDateTime.now();
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getShotCount() { return shotCount; }
    public void setShotCount(Integer shotCount) { this.shotCount = shotCount; }

    public String getStylePrefix() { return stylePrefix; }
    public void setStylePrefix(String stylePrefix) { this.stylePrefix = stylePrefix; }

    public String getDefaultBgmCategory() { return defaultBgmCategory; }
    public void setDefaultBgmCategory(String defaultBgmCategory) { this.defaultBgmCategory = defaultBgmCategory; }

    public String getDefaultVoice() { return defaultVoice; }
    public void setDefaultVoice(String defaultVoice) { this.defaultVoice = defaultVoice; }

    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}