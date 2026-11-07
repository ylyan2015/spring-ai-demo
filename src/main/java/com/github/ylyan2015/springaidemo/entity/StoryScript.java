package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 故事脚本实体类
 * 代表一个完整的短视频分镜脚本项目，包含多个镜头
 */
@Entity
@Table(name = "story_scripts")
public class StoryScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 脚本标题 */
    @Column(name = "title", length = 200)
    private String title;

    /** 主题描述 */
    @Column(name = "theme", length = 500)
    private String theme;

    /** 原始创意想法 */
    @Column(name = "idea", columnDefinition = "TEXT")
    private String idea;

    /** 项目状态: DRAFT / GENERATING / READY / EXPORTED */
    @Column(name = "status", length = 20)
    private String status = "DRAFT";

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 关联的镜头列表 */
    @OneToMany(mappedBy = "script", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("shotOrder ASC")
    private List<StoryShot> shots = new ArrayList<>();

    public StoryScript() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<StoryShot> getShots() { return shots; }
    public void setShots(List<StoryShot> shots) { this.shots = shots; }

    // ==================== Helper Methods ====================

    /**
     * 添加镜头到脚本
     */
    public void addShot(StoryShot shot) {
        shots.add(shot);
        shot.setScript(this);
    }

    /**
     * 从脚本中移除镜头
     */
    public void removeShot(StoryShot shot) {
        shots.remove(shot);
        shot.setScript(null);
    }

    /**
     * 更新修改时间
     */
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
