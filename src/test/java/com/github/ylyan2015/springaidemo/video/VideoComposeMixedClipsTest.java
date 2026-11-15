package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** idiom 复制 VideoComposeFfmpegTest：FFMPEG/FFPROBE 常量 + assumeTrue + ReflectionTestUtils 注入 */
class VideoComposeMixedClipsTest {

    private static final String FFMPEG = System.getProperty("video.ffmpeg-path",
            "D:/Program Files/ffmpeg/bin/ffmpeg.exe");
    private static final String FFPROBE = System.getProperty("video.ffprobe-path",
            "D:/Program Files/ffmpeg/bin/ffprobe.exe");

    @Test
    void mixedTwoClipsAndOneImageComposes() throws Exception {
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg not installed at " + FFMPEG);
        Path dir = Files.createTempDirectory("mixed-");
        Path clip1 = dir.resolve("c1.mp4");
        Path clip2 = dir.resolve("c2.mp4");
        Path img = dir.resolve("i.png");
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", clip1.toString());
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", clip2.toString());
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "color=c=red:size=320x240:d=1",
                "-frames:v", "1", img.toString());
        StorageService storage = Mockito.mock(StorageService.class);
        when(storage.upload(any(InputStream.class), anyString(), anyString())).thenReturn("video_test.mp4");
        when(storage.getAccessUrl(anyString())).thenReturn("/images/video_test.mp4");
        VideoComposeService service = new VideoComposeService(storage);
        ReflectionTestUtils.setField(service, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(service, "composeTimeoutMinutes", 2);
        double exact = 2 + 3 + 2 - 2 * VideoComposeService.XFADE_DURATION;
        Path out = service.composeMediaForTest(List.of(clip1, clip2, img), List.of(2, 3, 2),
                List.of("", "", "推进"), List.of(true, true, false), dir,
                new VideoComposeService.ComposeOptions(), exact);
        assertTrue(Files.exists(out));
        double dur = probe(out);
        assertTrue(Math.abs(dur - exact) < 0.3, "混合合成时长失控: " + dur);
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private static double probe(Path file) throws Exception {
        Process p = new ProcessBuilder(FFPROBE, "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, p.waitFor(), out);
        return Double.parseDouble(out);
    }
}