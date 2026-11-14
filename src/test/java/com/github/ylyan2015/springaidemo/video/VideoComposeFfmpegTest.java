package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 真实 FFmpeg 合成回归测试。
 * 覆盖三个只有实际跑一遍才会暴露的问题：xfade 输出标签位置、zoompan 的 z 表达式变量、
 * -loop 1 无限输入缺少 -t 截断。未安装 FFmpeg 的机器自动跳过。
 */
class VideoComposeFfmpegTest {

    private static final String FFMPEG = System.getProperty("video.ffmpeg-path",
            "D:/Program Files/ffmpeg/bin/ffmpeg.exe");
    private static final String FFPROBE = System.getProperty("video.ffprobe-path",
            "D:/Program Files/ffmpeg/bin/ffprobe.exe");
    private static final String PYTHON = System.getProperty("video.python-command",
            "C:/Users/Yan Yulin/AppData/Local/Python/bin/python.exe");

    private static HttpServer imageServer;
    private static int imagePort;

    @BeforeAll
    static void startImageServer() throws Exception {
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg not installed at " + FFMPEG);

        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imagePort = imageServer.getAddress().getPort();
        imageServer.createContext("/images/", ex -> {
            BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(new Color(Math.abs(ex.getRequestURI().getPath().hashCode()) & 0xFFFFFF));
            g.fillRect(0, 0, 1280, 720);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            byte[] bytes = png.toByteArray();
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        imageServer.start();
    }

    @AfterAll
    static void stopImageServer() {
        if (imageServer != null) {
            imageServer.stop(0);
        }
    }

    @Test
    void composesMultiShotVideoWithXfadeAndBoundedDuration() throws Exception {
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        VideoComposeService service = newService(stored);

        List<Map<String, Object>> shots = List.of(
                shot(1, "http://127.0.0.1:" + imagePort + "/images/a.png", 3, ""),
                shot(2, "http://127.0.0.1:" + imagePort + "/images/b.png", 3, ""));

        Map<String, Object> result = service.compose(shots);

        assertEquals(5, result.get("totalDuration"),
                "两段各 3s、0.8s 交叉淡入，总时长 5.2 四舍五入为 5");
        assertEquals(1280, result.get("width"));
        assertEquals(Boolean.FALSE, result.get("hasNarration"));
        // 纯色测试图几乎压不出体积，这里只用来确认确实产出了码流
        assertTrue(stored.size() > 1000, "合成产物过小: " + stored.size());

        // 真正的判据：ffprobe 量到的时长必须≈5.2s。缺 -t 时这里会是几十秒以上的失控输出。
        Path outFile = Files.createTempFile("compose-regression", ".mp4");
        try {
            Files.write(outFile, stored.toByteArray());
            double duration = probeDuration(outFile);
            assertTrue(Math.abs(duration - 5.2) < 0.3, "实际时长偏离预期: " + duration + "s");
        } finally {
            Files.deleteIfExists(outFile);
        }
    }

    /**
     * 旁白链路：edge-tts 合成 → 按时长补静音拼接 → 与画面合并。
     * 需要联网访问微软 TTS 接口，默认不跑，-Dtts=true 时执行。
     */
    @Test
    @EnabledIfSystemProperty(named = "tts", matches = "true")
    void composesVideoWithChineseNarration() throws Exception {
        assumeTrue(Files.exists(Path.of(PYTHON)), "Python not installed at " + PYTHON);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        VideoComposeService service = newService(stored);

        List<Map<String, Object>> shots = List.of(
                shot(1, "http://127.0.0.1:" + imagePort + "/images/a.png", 3, "清晨的第一缕阳光洒进窗台。"),
                shot(2, "http://127.0.0.1:" + imagePort + "/images/b.png", 3, "新的一天从这里开始。"));

        Map<String, Object> result = service.compose(shots);

        assertEquals(Boolean.TRUE, result.get("hasNarration"), "旁白轨未合成，edge-tts 可能不可用");
        assertEquals(Boolean.TRUE, result.get("hasAudio"));

        Path outFile = Files.createTempFile("compose-narration", ".mp4");
        try {
            Files.write(outFile, stored.toByteArray());
            String streams = ffprobe(outFile, "stream=codec_type,codec_name");
            assertTrue(streams.contains("aac"), "音轨不是 aac: " + streams);
        } finally {
            Files.deleteIfExists(outFile);
        }
    }

    private static VideoComposeService newService(ByteArrayOutputStream sink) {
        StorageService storage = Mockito.mock(StorageService.class);
        when(storage.upload(any(InputStream.class), anyString(), anyString())).thenAnswer(inv -> {
            try (InputStream in = inv.getArgument(0)) {
                in.transferTo(sink);
            }
            return "video_test.mp4";
        });
        when(storage.getAccessUrl(anyString())).thenReturn("/images/video_test.mp4");

        VideoComposeService service = new VideoComposeService(storage);
        ReflectionTestUtils.setField(service, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(service, "pythonCommand", PYTHON);
        ReflectionTestUtils.setField(service, "serverPort", imagePort);
        ReflectionTestUtils.setField(service, "composeTimeoutMinutes", 2);
        return service;
    }

    /**
     * application.yml 工具路径配置解析验证（release 审查 M10 后默认裸名走 PATH）：
     * 默认值必须是非空裸名；环境变量覆盖为绝对路径时占位符不得截断盘符。
     */
    @Test
    void resolvesFfmpegPathsFromApplicationYaml() throws Exception {
        // MockEnvironment 不含 systemProperties，断言的是 yml 默认值而非 -D 覆盖值
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }

        // 默认裸名（可移植，release 审查 M10）
        assertEquals("ffmpeg", env.getProperty("video.ffmpeg-path"));
        assertEquals("ffprobe", env.getProperty("video.ffprobe-path"));
        assertEquals("python", env.getProperty("video.python-command"));

        // 环境变量覆盖为带盘符绝对路径时，占位符解析不得截断冒号
        MockEnvironment overridden = new MockEnvironment()
                .withProperty("FFMPEG_PATH", "D:/tools/ffmpeg/bin/ffmpeg.exe")
                .withProperty("FFPROBE_PATH", "D:/tools/ffmpeg/bin/ffprobe.exe")
                .withProperty("PYTHON_CMD", "C:/Python/python.exe");
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            overridden.getPropertySources().addLast(source);
        }
        for (String key : List.of("video.ffmpeg-path", "video.ffprobe-path", "video.python-command")) {
            String path = overridden.getProperty(key);
            assertNotNull(path, key + " 未解析出值");
            assertTrue(path.length() > 2 && path.charAt(1) == ':'
                            && Character.isLetter(path.charAt(0)),
                    key + " 占位符解析异常（盘符疑似被截断）: " + path);
        }
    }

    /** 裸 python 在 Windows 上常被商店别名桩挡住，确认配置的绝对路径能真正探测到 edge-tts */
    @Test
    void detectsEdgeTtsThroughConfiguredPythonCommand() {
        assumeTrue(Files.exists(Path.of(PYTHON)), "Python not installed at " + PYTHON);

        VideoComposeService service = new VideoComposeService(Mockito.mock(StorageService.class));
        ReflectionTestUtils.setField(service, "pythonCommand", PYTHON);

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(service, "isEdgeTtsAvailable"),
                "配置的 python-command 探测不到 edge-tts，旁白会被静默跳过");
    }

    private static Map<String, Object> shot(int order, String url, int seconds, String narration) {
        return Map.of("order", order, "imageUrl", url,
                "durationSeconds", seconds, "narration", narration);
    }

    private static double probeDuration(Path file) throws Exception {
        return Double.parseDouble(ffprobe(file, "format=duration"));
    }

    private static String ffprobe(Path file, String entries) throws Exception {
        Process p = new ProcessBuilder(FFPROBE, "-v", "error",
                "-show_entries", entries, "-of", "csv=p=0", file.toString())
                .redirectErrorStream(true).start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertEquals(0, p.waitFor(), "ffprobe failed: " + out);
        return out;
    }
}
