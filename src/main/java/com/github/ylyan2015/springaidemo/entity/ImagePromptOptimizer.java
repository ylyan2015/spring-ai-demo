package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 图像提示词优化记录实体类
 * 记录每次提示词优化的输入、输出和使用的风格
 */
@Entity
@Table(name = "image_prompt_optimizations")
public class ImagePromptOptimizer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 关联的脚本ID（可选，独立优化时无关联） */
    @Column(name = "script_id")
    private Long scriptId;

    /** 关联的镜头ID（可选，独立优化时无关联） */
    @Column(name = "shot_id")
    private Long shotId;

    /** 原始场景描述（中文输入） */
    @Column(name = "original_description", columnDefinition = "TEXT", nullable = false)
    private String originalDescription;

    /** 优化后的提示词（英文输出） */
    @Column(name = "optimized_prompt", columnDefinition = "TEXT", nullable = false)
    private String optimizedPrompt;

    /** 负面提示词 */
    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    /** 使用的视觉风格（如 CINEMATIC_REALISM） */
    @Column(name = "visual_style", length = 50)
    private String visualStyle;

    /** 使用的模型名称（如 deepseek / ollama） */
    @Column(name = "model_used", length = 50)
    private String modelUsed;

    /** 优化状态: SUCCESS / FAILED */
    @Column(name = "status", length = 20)
    private String status = "SUCCESS";

    /** 错误信息（失败时记录） */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ImagePromptOptimizer() {
        this.createdAt = LocalDateTime.now();
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public Long getShotId() { return shotId; }
    public void setShotId(Long shotId) { this.shotId = shotId; }

    public String getOriginalDescription() { return originalDescription; }
    public void setOriginalDescription(String originalDescription) { this.originalDescription = originalDescription; }

    public String getOptimizedPrompt() { return optimizedPrompt; }
    public void setOptimizedPrompt(String optimizedPrompt) { this.optimizedPrompt = optimizedPrompt; }

    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }

    public String getVisualStyle() { return visualStyle; }
    public void setVisualStyle(String visualStyle) { this.visualStyle = visualStyle; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
