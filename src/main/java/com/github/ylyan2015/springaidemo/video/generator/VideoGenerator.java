package com.github.ylyan2015.springaidemo.video.generator;

/**
 * 视频生成器接口（Phase 4 切片 2，复刻 ImageGenerator 模式）
 * generate 为阻塞封装（内部异步提交+轮询），产物一律存储转存。
 */
public interface VideoGenerator {

    GeneratedVideo generate(VideoGenerationRequest request);

    String getType();

    String getName();

    boolean isAvailable();
}