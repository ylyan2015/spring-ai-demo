package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 合成自动对齐测试（Phase 5 Track B Task 4：TTS 预探测 + 短片段 loop）
 */
class VideoComposeAutoFitTest {

    private static final String FFMPEG = System.getProperty("video.ffmpeg-path",
            "D:/Program Files/ffmpeg/bin/ffmpeg.exe");
    private static final String FFPROBE = System.getProperty("video.ffprobe-path",
            "D:/Program Files/ffmpeg/bin/ffprobe.exe");

    @Test void shortClipGetsLoopAndTrim() {
        String f = VideoComposeService.videoInputFilter(1, 10, true, 1280, 720, 30);
        assertTrue(f.contains("trim=duration=10"), f);
    }

    @Test void longClipNoLoop() {
        String f = VideoComposeService.videoInputFilter(1, 3, false, 1280, 720, 30);
        assertFalse(f.contains("trim=duration="), f);
    }

    @Test void shortClipLoopsToFillEffectiveDuration() throws Exception {
        assumeTrue(Boolean.getBoolean("commentary"), "真实 FFmpeg 冒烟需 -Dcommentary=true");
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg not installed at " + FFMPEG);
        Path workDir = Files.createTempDirectory("autofit-");
        Path clip = workDir.resolve("clip.mp4");
        Process p = new ProcessBuilder(FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=duration=2:size=1280x720:rate=30", "-c:v", "libx264",
                clip.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, p.waitFor());

        VideoComposeService service = new VideoComposeService(mock(StorageService.class));
        ReflectionTestUtils.setField(service, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(service, "ffprobePath", FFPROBE);
        ReflectionTestUtils.setField(service, "composeTimeoutMinutes", 2);
        // 2s 片段 + 有效时长 8s + needsLoop → 循环填充至 8s 不饿死
        Path out = service.composeMediaForTest(List.of(clip), List.of(8), List.of("固定"),
                List.of(true), workDir, new VideoComposeService.ComposeOptions(), 8.0, List.of(true));
        assertEquals(8, service.probeDurationSeconds(out));
    }

    @Test void shortClipLoopsInMultiShotCompose() throws Exception {
        // 多镜头生产路径：短片段 -stream_loop -1 + trim + xfade 不饿死（审查指出的单镜头冒烟覆盖缺口）
        assumeTrue(Boolean.getBoolean("commentary"), "真实 FFmpeg 冒烟需 -Dcommentary=true");
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg not installed at " + FFMPEG);
        Path workDir = Files.createTempDirectory("autofit-multi-");
        Path clip1 = workDir.resolve("c1.mp4");
        Path clip2 = workDir.resolve("c2.mp4");
        Path img = workDir.resolve("i.png");
        lavfi(clip1, "testsrc=duration=2:size=1280x720:rate=30", "-c:v", "libx264");
        lavfi(clip2, "testsrc=duration=3:size=1280x720:rate=30", "-c:v", "libx264");
        lavfi(img, "color=c=red:size=1280x720:d=1", "-frames:v", "1");

        VideoComposeService service = new VideoComposeService(mock(StorageService.class));
        ReflectionTestUtils.setField(service, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(service, "ffprobePath", FFPROBE);
        ReflectionTestUtils.setField(service, "composeTimeoutMinutes", 2);
        // 镜1 2s片段抬升到8s（needsLoop）+ 镜2 3s片段 + 镜3 2s静图；exact=8+3+2-2*0.8=11.4
        double exact = 8 + 3 + 2 - 2 * VideoComposeService.XFADE_DURATION;
        Path out = service.composeMediaForTest(List.of(clip1, clip2, img), List.of(8, 3, 2),
                List.of("固定", "固定", "固定"), List.of(true, true, false), workDir,
                new VideoComposeService.ComposeOptions(), exact, List.of(true, false, false));
        assertTrue(Files.exists(out));
        double dur = service.probeDurationSeconds(out);
        assertTrue(Math.abs(dur - exact) < 1.0, "多镜头 loop 合成时长失控: " + dur + " vs " + exact);
    }

    private static void lavfi(Path out, String src, String... extra) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i", src));
        cmd.addAll(java.util.List.of(extra));
        cmd.add(out.toAbsolutePath().toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, p.waitFor());
    }
}