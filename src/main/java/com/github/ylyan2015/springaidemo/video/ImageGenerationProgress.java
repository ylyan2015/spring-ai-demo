package com.github.ylyan2015.springaidemo.video;

import java.time.LocalDateTime;

/**
 * 图像生成进度事件
 * 用于 SSE 进度推送，记录批量生成的实时状态
 */
public class ImageGenerationProgress {

    /** 事件类型: START / SHOT_START / SHOT_COMPLETE / SHOT_FAILED / COMPLETE / ERROR */
    private String type;

    /** 关联的脚本ID */
    private Long scriptId;

    /** 总镜头数 */
    private int totalShots;

    /** 已完成镜头数 */
    private int completedShots;

    /** 失败镜头数 */
    private int failedShots;

    /** 当前正在处理的镜头ID */
    private Long shotId;

    /** 当前镜头顺序号 */
    private int currentShotIndex;

    /** 当前镜头生成完成后的图片地址 */
    private String imageUrl;

    /** 提示信息 */
    private String message;

    /** 事件时间 */
    private LocalDateTime timestamp;

    public ImageGenerationProgress() {
        this.timestamp = LocalDateTime.now();
    }

    /** 创建简单事件 */
    public static ImageGenerationProgress of(String type, String message) {
        ImageGenerationProgress p = new ImageGenerationProgress();
        p.type = type;
        p.message = message;
        return p;
    }

    // ==================== Getters and Setters ====================

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public int getTotalShots() { return totalShots; }
    public void setTotalShots(int totalShots) { this.totalShots = totalShots; }

    public int getCompletedShots() { return completedShots; }
    public void setCompletedShots(int completedShots) { this.completedShots = completedShots; }

    public int getFailedShots() { return failedShots; }
    public void setFailedShots(int failedShots) { this.failedShots = failedShots; }

    public Long getShotId() { return shotId; }
    public void setShotId(Long shotId) { this.shotId = shotId; }

    public int getCurrentShotIndex() { return currentShotIndex; }
    public void setCurrentShotIndex(int currentShotIndex) { this.currentShotIndex = currentShotIndex; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
