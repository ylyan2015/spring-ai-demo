package com.github.ylyan2015.springaidemo.video;

/**
 * TTS 合成行（Phase 4 切片 4：对白/旁白分离多声线）
 * voice 为空时回退默认旁白声线 zh-CN-YunxiNeural
 */
public record TtsLine(String text, String voice) {
}