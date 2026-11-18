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

    /** Phase 4 切片 1：逗号分隔角色 id */
    @Column(name = "character_ids", length = 200)
    private String characterIds;

    /** Phase 4 切片 1：景别 CLOSE/MEDIUM/LONG */
    @Column(name = "shot_size", length = 20)
    private String shotSize = "MEDIUM";

    /** Phase 4 切片 2：i2v 产物视频地址（存储转存后） */
    @Column(name = "video_url", length = 1024)
    private String videoUrl;

    @Column(name = "video_file_id", length = 200)
    private String videoFileId;

    /** Phase 4 切片 2：NONE/PENDING/GENERATING/READY/FAILED */
    @Column(name = "video_status", length = 20)
    private String videoStatus = "NONE";

    /** Phase 4 切片 2：末帧图（连续性模式消费） */
    @Column(name = "last_frame_url", length = 1024)
    private String lastFrameUrl;

    @Column(name = "last_frame_file_id", length = 200)
    private String lastFrameFileId;

    /** 图片质量综合评分（0-10，模块 J；未启用评估时为 null） */
    @Column(name = "quality_score")
    private Double qualityScore;

    /** Phase 4 切片 4：本镜角色对白（中文，无对白为空） */
    @Column(name = "dialogue", columnDefinition = "TEXT")
    private String dialogue;

    /** Phase 4 切片 4：对白角色 id（声线取角色卡 voice） */
    @Column(name = "dialogue_character_id")
    private Long dialogueCharacterId;

    /** Phase 5 Track B：引用镜出处水印（非空=引用镜，受占比护栏与 drawtext 约束） */
    @Column(name = "insert_attribution", length = 200)
    private String insertAttribution;

    /** Phase 5 Track A：尾卡镜（true=跳过生图，纯色底+FFmpeg 压字本地渲染） */
    @Column(name = "end_card")
    private Boolean endCard;

    /** 素材库切片 S1：绑定的参考素材 id（逗号分隔，MediaAsset CHARACTER/SCENE，镜像 characterIds idiom）；null=未绑定；仅人工确认后写入 */
    @Column(name = "reference_asset_ids", length = 500)
    private String referenceAssetIds;

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
    public String getCharacterIds() { return characterIds; }
    public void setCharacterIds(String characterIds) { this.characterIds = characterIds; }
    public String getShotSize() { return shotSize; }
    public void setShotSize(String shotSize) { this.shotSize = shotSize; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public String getVideoFileId() { return videoFileId; }
    public void setVideoFileId(String videoFileId) { this.videoFileId = videoFileId; }
    public String getVideoStatus() { return videoStatus; }
    public void setVideoStatus(String videoStatus) { this.videoStatus = videoStatus; }
    public String getLastFrameUrl() { return lastFrameUrl; }
    public void setLastFrameUrl(String lastFrameUrl) { this.lastFrameUrl = lastFrameUrl; }
    public String getLastFrameFileId() { return lastFrameFileId; }
    public void setLastFrameFileId(String lastFrameFileId) { this.lastFrameFileId = lastFrameFileId; }

    /** Phase 4 切片 2：图片变更后失效旧视频/尾帧，避免导出静默混入与新图不符的旧片段 */
    public void invalidateVideo() {
        this.videoUrl = null;
        this.videoFileId = null;
        this.lastFrameUrl = null;
        this.lastFrameFileId = null;
        this.videoStatus = "NONE";
    }

    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }

    public String getDialogue() { return dialogue; }
    public void setDialogue(String dialogue) { this.dialogue = dialogue; }
    public Long getDialogueCharacterId() { return dialogueCharacterId; }
    public void setDialogueCharacterId(Long dialogueCharacterId) { this.dialogueCharacterId = dialogueCharacterId; }
    public String getInsertAttribution() { return insertAttribution; }
    public void setInsertAttribution(String insertAttribution) { this.insertAttribution = insertAttribution; }

    public Boolean getEndCard() { return endCard; }
    public void setEndCard(Boolean endCard) { this.endCard = endCard; }

    public String getReferenceAssetIds() { return referenceAssetIds; }
    public void setReferenceAssetIds(String referenceAssetIds) { this.referenceAssetIds = referenceAssetIds; }
}
