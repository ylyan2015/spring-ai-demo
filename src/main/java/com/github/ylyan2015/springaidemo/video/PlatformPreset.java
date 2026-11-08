package com.github.ylyan2015.springaidemo.video;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 社交媒体平台导出预设（模块 I）
 * 平台 → 推荐比例 + 分辨率档位 + 推荐时长上限（0 表示无限制）
 * 分辨率档位复用 VideoExportService.resolveDimensions（1080p: 9:16→1080x1920 / 16:9→1920x1080 / 1:1→1080x1080）
 */
public enum PlatformPreset {

    DOUYIN("抖音", "9:16", "1080p", 1080, 1920, 60, "竖屏全屏沉浸式，黄金 3 秒开头"),
    BILIBILI("B站", "16:9", "1080p", 1920, 1080, 0, "横屏 16:9，支持长视频"),
    XIAOHONGSHU("小红书", "1:1", "1080p", 1080, 1080, 60, "方形卡片流，封面决定点击率"),
    KUAISHOU("快手", "9:16", "1080p", 1080, 1920, 60, "竖屏全屏沉浸式"),
    WEIXIN_VIDEO("视频号", "16:9", "1080p", 1920, 1080, 0, "横屏为主，时长无硬限制"),
    YOUTUBE_SHORTS("YouTube Shorts", "9:16", "1080p", 1080, 1920, 60, "竖屏，时长须 ≤60s");

    private final String displayName;
    private final String aspectRatio;
    private final String resolution;
    private final int width;
    private final int height;
    private final int maxDurationSeconds;
    private final String note;

    PlatformPreset(String displayName, String aspectRatio, String resolution,
                   int width, int height, int maxDurationSeconds, String note) {
        this.displayName = displayName;
        this.aspectRatio = aspectRatio;
        this.resolution = resolution;
        this.width = width;
        this.height = height;
        this.maxDurationSeconds = maxDurationSeconds;
        this.note = note;
    }

    /** 按枚举名（大小写不敏感）或中文显示名解析平台 */
    public static Optional<PlatformPreset> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        for (PlatformPreset p : values()) {
            if (p.name().equalsIgnoreCase(trimmed) || p.displayName.equals(trimmed)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public String getDisplayName() { return displayName; }
    public String getAspectRatio() { return aspectRatio; }
    public String getResolution() { return resolution; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public String getNote() { return note; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name());
        map.put("displayName", displayName);
        map.put("aspectRatio", aspectRatio);
        map.put("resolution", resolution);
        map.put("width", width);
        map.put("height", height);
        map.put("maxDurationSeconds", maxDurationSeconds);
        map.put("note", note);
        return map;
    }
}
