package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切片 4 T2：多声线 TTS 命令（对白角色声线 / 旁白默认声线不变）
 */
class VideoComposeMultiVoiceTest {

    @Test
    void ttsCommandUsesLineVoice() {
        List<String> cmd = VideoComposeService.buildTtsCommand("python",
                new TtsLine("你终于来了。", "zh-CN-XiaoxiaoNeural"), Path.of("a.mp3"));
        int i = cmd.indexOf("--voice");
        assertEquals("zh-CN-XiaoxiaoNeural", cmd.get(i + 1));
        assertTrue(cmd.contains("--text"));
    }

    @Test
    void defaultNarrationVoiceUnchanged() {
        List<String> cmd = VideoComposeService.buildTtsCommand("python",
                new TtsLine("旁白", null), Path.of("a.mp3"));
        assertEquals("zh-CN-YunxiNeural", cmd.get(cmd.indexOf("--voice") + 1));
    }
}