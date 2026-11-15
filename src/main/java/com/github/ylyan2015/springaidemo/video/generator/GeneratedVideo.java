package com.github.ylyan2015.springaidemo.video.generator;

/**
 * 视频生成结果（Phase 4 切片 2；url/fileId 为存储转存后的值）
 */
public record GeneratedVideo(String videoUrl, String fileId, String generatorType,
                             int durationSeconds, int width, int height) {

    /** 便捷构造（宽高未知时补 0） */
    public GeneratedVideo(String videoUrl, String fileId, String generatorType, int durationSeconds) {
        this(videoUrl, fileId, generatorType, durationSeconds, 0, 0);
    }

    public String getVideoUrl() { return videoUrl; }
    public String getFileId() { return fileId; }
    public String getGeneratorType() { return generatorType; }
    public int getDurationSeconds() { return durationSeconds; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}