package com.github.ylyan2015.springaidemo.video.subtitle;

/**
 * 单条字幕（含视频时间轴上的起止毫秒）
 *
 * @param text    字幕文本（镜头旁白）
 * @param startMs 开始时间（毫秒，视频时间轴）
 * @param endMs   结束时间（毫秒，视频时间轴）
 */
public record SubtitleCue(String text, long startMs, long endMs) {
}
