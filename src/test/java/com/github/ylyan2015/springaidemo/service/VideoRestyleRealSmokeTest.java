package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.RestyleTask;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
class VideoRestyleRealSmokeTest {

    @Autowired VideoRestyleService service;
    @Autowired StorageService storage;
    @Autowired UserRepository userRepository;
    @Value("${spring.ai.dashscope.api-key:}") String apiKey;

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void restyleTenSecondSourceToAnime() throws Exception {
        assumeTrue(Boolean.getBoolean("restyle"), "真实冒烟需 -Drestyle=true");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "需 DASHSCOPE_API_KEY");
        // 服务层登录校验：冒烟专用用户（不存在则建）
        userRepository.findByUsername("restyle-smoke").orElseGet(() -> {
            User n = new User();
            n.setUsername("restyle-smoke");
            n.setPassword("smoke-placeholder");
            return userRepository.save(n);
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("restyle-smoke", null, List.of()));
        Path src = Files.createTempFile("restyle-src-", ".mp4");
        // stdout 必须丢弃/消费，否则管道满致 waitFor 死锁（审查教训）
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=duration=10:size=640x360:rate=24", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", src.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, p.waitFor());
        Map<String, Object> out = service.submit(Files.newInputStream(src), "src.mp4", "video/mp4",
                "anime style, cel shading, clean lines, keep original motion and camera");
        RestyleTask task = service.awaitAndGetForTest(((Number) out.get("id")).longValue());
        // 协议核对：wan2.7-videoedit 视频输入不支持 base64，local 存储无公网 URL→FAILED 带 oss/minio 指引；
        // 成功路径已由 T1 spike 实测验收（oss/minio 环境同路径）
        if ("local".equals(storage.getStorageType())) {
            assertEquals("FAILED", task.getStatus());
            assertTrue(task.getError() != null && task.getError().contains("oss/minio"),
                    "local 存储应带切换指引: " + task.getError());
            System.out.println("[restyle-smoke] local 存储错误路径验证通过: " + task.getError());
        } else {
            assertEquals("SUCCEEDED", task.getStatus(), "转绘失败: " + task.getError());
            assertNotNull(task.getResultUrl());
            System.out.println("[restyle-smoke] 目视验收路径: " + task.getResultUrl());
        }
    }
}