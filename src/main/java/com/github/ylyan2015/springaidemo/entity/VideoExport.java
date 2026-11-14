package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 视频导出记录实体类
 * 每次生成预览或高清导出都会记录一条，供导出历史查询与重新下载
 */
@Entity
@Table(name = "video_exports")
public class VideoExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的故事脚本ID */
    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    /** 导出用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 分辨率档位: 480p（预览）/ 720p / 1080p */
    @Column(name = "resolution", nullable = false, length = 10)
    private String resolution;

    /** 画面比例: 16:9 / 9:16 / 1:1 */
    @Column(name = "aspect_ratio", nullable = false, length = 10)
    private String aspectRatio;

    /** 实际输出宽度 */
    @Column(name = "width")
    private Integer width;

    /** 实际输出高度 */
    @Column(name = "height")
    private Integer height;

    /** 成品视频访问地址 */
    @Column(name = "video_url", length = 1024)
    private String videoUrl;

    /** 文件大小（字节） */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** 视频时长（秒） */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** 是否带预览水印 */
    @Column(name = "has_watermark", nullable = false)
    private Boolean hasWatermark = false;

    /** 本次导出使用的 BGM ID（未使用为 null） */
    @Column(name = "bgm_id")
    private Long bgmId;

    /** 本次导出使用的 BGM 名称（冗余存储，防止 BGM 被删除后无从展示） */
    @Column(name = "bgm_name", length = 100)
    private String bgmName;

    /** 是否烧录了字幕 */
    @Column(name = "has_subtitle", nullable = false)
    private Boolean hasSubtitle = false;

    /** 烧录字幕语言: zh 原文 / en 英文 / bilingual 中英双语（模块 F；未烧录字幕为 null） */
    @Column(name = "subtitle_language", length = 20)
    private String subtitleLanguage;

    /** 是否叠加了品牌 Logo 角标 */
    @Column(name = "has_logo", nullable = false)
    private Boolean hasLogo = false;

    /** 封面图地址（模块 I：从本条导出视频截帧生成） */
    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    /** 导出状态: SUCCESS / FAILED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUCCESS";

    /** 失败原因 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 导出时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public VideoExport() {}

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Boolean getHasWatermark() { return hasWatermark; }
    public void setHasWatermark(Boolean hasWatermark) { this.hasWatermark = hasWatermark; }

    public Long getBgmId() { return bgmId; }
    public void setBgmId(Long bgmId) { this.bgmId = bgmId; }

    public String getBgmName() { return bgmName; }
    public void setBgmName(String bgmName) { this.bgmName = bgmName; }

    public Boolean getHasSubtitle() { return hasSubtitle; }
    public void setHasSubtitle(Boolean hasSubtitle) { this.hasSubtitle = hasSubtitle; }

    public String getSubtitleLanguage() { return subtitleLanguage; }
    public void setSubtitleLanguage(String subtitleLanguage) { this.subtitleLanguage = subtitleLanguage; }

    public Boolean getHasLogo() { return hasLogo; }
    public void setHasLogo(Boolean hasLogo) { this.hasLogo = hasLogo; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
