package com.github.ylyan2015.springaidemo.video;

/**
 * TTS 合成行（Phase 4 切片 4：对白/旁白分离多声线）
 * voice 为空时回退默认旁白声线；rate 为 edge-tts 语速偏移（如 "+15%"），空则用 CLI 默认
 */
public record TtsLine(String text, String voice, String rate) {

    public TtsLine(String text, String voice) {
        this(text, voice, null);
    }
}