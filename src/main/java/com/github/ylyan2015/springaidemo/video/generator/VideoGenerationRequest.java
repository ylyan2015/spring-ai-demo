package com.github.ylyan2015.springaidemo.video.generator;

/**
 * 视频生成请求（Phase 4 切片 2：图生视频）
 *
 * @param prompt          运动/画面描述（含中低运动幅度模板）
 * @param firstFrameUrl   首帧图 URL（公网或 base64 data URI）
 * @param lastFrameUrl    尾帧图 URL（可 null；仅首尾帧模型消费）
 * @param durationSeconds 时长（服务层 clamp 2-15）
 * @param resolution      分辨率档位（如 720P）
 */
public record VideoGenerationRequest(String prompt, String firstFrameUrl, String lastFrameUrl,
                                     int durationSeconds, String resolution) {
}