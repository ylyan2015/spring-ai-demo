package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoComposeEnhanceTest {

    @Test
    void fullChainCommandsOrderAndAudioMap() {
        List<List<String>> cmds = VideoComposeService.buildEnhanceCommands("ffmpeg", "realesrgan",
                "realesrgan-x4plus-anime", "rife", "/models/rife-v4.6", Path.of("src.mp4"),
                Path.of("w"), 30, true);
        assertEquals(4, cmds.size());
        assertTrue(cmds.get(1).contains("-n"));
        assertTrue(cmds.get(2).contains("-m"));
        assertTrue(cmds.get(3).contains("-framerate"));
        assertTrue(cmds.get(3).contains("60")); // rife 帧数翻倍，30fps→60fps 保时长（审查修复）
        assertTrue(cmds.get(3).contains("1:a?"));
    }

    @Test
    void upscaleOnlySkipsRifeAndKeepsFps() {
        List<List<String>> cmds = VideoComposeService.buildEnhanceCommands("ffmpeg", "realesrgan",
                "m", "", "", Path.of("src.mp4"), Path.of("w"), 30, false);
        assertEquals(3, cmds.size());
        assertTrue(cmds.get(2).contains("30"));
        assertFalse(cmds.get(2).contains("1:a?"));
    }
}