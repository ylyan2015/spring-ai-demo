package com.github.ylyan2015.springaidemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 角色卡实体（Phase 4 切片 1：角色一致性）
 * 固定特征表（英文）出图时拼 prompt 头部；定妆参考图供 wan2.7 参考图模式；
 * voice 为 edge-tts 声线名（切片 4 多声线配音消费）
 */
@Entity
@Table(name = "story_characters")
public class StoryCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    @JsonIgnore
    private StoryScript script;

    @Column(nullable = false, length = 100)
    private String name;

    /** 固定特征表（英文）：发型/瞳色/服装/体型，LLM 生成，跨镜复用 */
    @Column(columnDefinition = "TEXT")
    private String featureTable;

    @Column(length = 500)
    private String personality;

    @Column(length = 200)
    private String referenceImageFileId;

    @Column(length = 1024)
    private String referenceImageUrl;

    /** edge-tts 声线名，默认晓晓（女声） */
    @Column(length = 100)
    private String voice = "zh-CN-XiaoxiaoNeural";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public StoryCharacter() {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public StoryScript getScript() { return script; }
    public void setScript(StoryScript script) { this.script = script; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFeatureTable() { return featureTable; }
    public void setFeatureTable(String featureTable) { this.featureTable = featureTable; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getReferenceImageFileId() { return referenceImageFileId; }
    public void setReferenceImageFileId(String referenceImageFileId) { this.referenceImageFileId = referenceImageFileId; }
    public String getReferenceImageUrl() { return referenceImageUrl; }
    public void setReferenceImageUrl(String referenceImageUrl) { this.referenceImageUrl = referenceImageUrl; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}