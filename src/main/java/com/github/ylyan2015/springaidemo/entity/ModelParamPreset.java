package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 模型参数预设实体
 * 存储每个用户对各模型（ollama/openai/deepseek）的自定义参数配置
 */
@Entity
@Table(name = "model_param_presets", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "model_key"})
})
public class ModelParamPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 模型标识：ollama / openai / deepseek */
    @Column(name = "model_key", nullable = false, length = 30)
    private String modelKey;

    /** 温度 (0.0 ~ 2.0) */
    @Column(name = "temperature")
    private Double temperature;

    /** 最大 token 数 */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** Top P (0.0 ~ 1.0) */
    @Column(name = "top_p")
    private Double topP;

    /** Top K (整数) */
    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ModelParamPreset() {
        this.updatedAt = LocalDateTime.now();
    }

    public ModelParamPreset(Long userId, String modelKey) {
        this.userId = userId;
        this.modelKey = modelKey;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getModelKey() { return modelKey; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
