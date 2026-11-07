package com.github.ylyan2015.springaidemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * 故事镜头实体类
 * 代表分镜脚本中的单个镜头，包含画面描述、旁白、图片等信息
 */
@Entity
@Table(name = "story_shots")
public class StoryShot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属脚本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    @JsonIgnore
    private StoryScript script;

    /** 镜头顺序（从1开始） */
    @Column(name = "shot_order", nullable = false)
    private Integer shotOrder;

    /** 画面内容描述（中文） */
    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    /** 镜头运动方式（如：固定、推进、拉远、平移等） */
    @Column(name = "camera_movement", length = 100)
    private String cameraMovement;

    /** 镜头时长（秒） */
    @Column(name = "duration_seconds")
    private Integer durationSeconds = 5;

    /** 旁白文字（中文） */
    @Column(name = "narration", columnDefinition = "TEXT")
    private String narration;

    /** AI图像生成提示词（英文） */
    @Column(name = "image_prompt", columnDefinition = "TEXT")
    private String imagePrompt;

    /** 已生成图片的访问地址 */
    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    /** 存储服务中的文件ID */
    @Column(name = "image_file_id", length = 200)
    private String imageFileId;

    /** 图片生成状态: PENDING / GENERATING / READY / FAILED */
    @Column(name = "image_status", length = 20)
    private String imageStatus = "PENDING";

    public StoryShot() {}

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public StoryScript getScript() { return script; }
    public void setScript(StoryScript script) { this.script = script; }

    public Long getScriptId() {
        return script != null ? script.getId() : null;
    }

    public Integer getShotOrder() { return shotOrder; }
    public void setShotOrder(Integer shotOrder) { this.shotOrder = shotOrder; }

    public String getSceneDescription() { return sceneDescription; }
    public void setSceneDescription(String sceneDescription) { this.sceneDescription = sceneDescription; }

    public String getCameraMovement() { return cameraMovement; }
    public void setCameraMovement(String cameraMovement) { this.cameraMovement = cameraMovement; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }

    public String getImagePrompt() { return imagePrompt; }
    public void setImagePrompt(String imagePrompt) { this.imagePrompt = imagePrompt; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getImageFileId() { return imageFileId; }
    public void setImageFileId(String imageFileId) { this.imageFileId = imageFileId; }

    public String getImageStatus() { return imageStatus; }
    public void setImageStatus(String imageStatus) { this.imageStatus = imageStatus; }
}
