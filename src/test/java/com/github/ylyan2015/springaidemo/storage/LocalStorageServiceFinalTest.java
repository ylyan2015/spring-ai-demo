package com.github.ylyan2015.springaidemo.storage;

import com.github.ylyan2015.springaidemo.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成片归置目录 uploadFinal：final/ 友好命名（标题-视频.ext）、非法字符清洗、重名时间戳后缀。
 * 用户决策（2026-11-17）：final/ 目录 + 追加时间戳防覆盖 + 存量成片复制归集不删原文件。
 */
class LocalStorageServiceFinalTest {

    @TempDir
    Path tempDir;

    private LocalStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties.Local config = new StorageProperties.Local();
        config.setBasePath(tempDir.toString());
        config.setAccessPath("/images");
        service = new LocalStorageService(config);
        service.init();
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadFinal_storesUnderFinalDirWithFriendlyName() {
        String fileId = service.uploadFinal(stream("v"), "那座山，翻过去了", "视频", ".mp4", "video/mp4");

        assertEquals("final/那座山，翻过去了-视频.mp4", fileId.replace('\\', '/'));
        assertTrue(Files.exists(tempDir.resolve("final").resolve("那座山，翻过去了-视频.mp4")));
        assertTrue(service.getAccessUrl(fileId).startsWith("/images/final/"));
    }

    @Test
    void uploadFinal_sanitizesIllegalFileNameChars() {
        String fileId = service.uploadFinal(stream("v"), "a/b\\c:d*e?f\"g<h>i|j", "视频", ".mp4", "video/mp4");

        String name = Path.of(fileId).getFileName().toString();
        assertEquals("abcdefghij-视频.mp4", name);
    }

    @Test
    void uploadFinal_stripsDotSegmentsPreventingPathTraversal() {
        String fileId = service.uploadFinal(stream("v"), "../evil", "视频", ".mp4", "video/mp4");

        String name = Path.of(fileId).getFileName().toString();
        assertFalse(name.contains(".."));
        // 清洗后仅剩 "evil"
        assertEquals("evil-视频.mp4", name);
        assertFalse(Files.exists(tempDir.resolve("evil-视频.mp4")));
    }

    @Test
    void uploadFinal_blankTitleFallsBackToOriginalFileName() {
        String fileId = service.uploadFinal(stream("v"), "  ", "视频", ".mp4", "video/mp4");

        assertEquals("final/视频.mp4", fileId.replace('\\', '/'));
    }

    @Test
    void uploadFinal_duplicateAppendsTimestampSuffix() throws Exception {
        String first = service.uploadFinal(stream("v1"), "同名", "视频", ".mp4", "video/mp4");
        String second = service.uploadFinal(stream("v2"), "同名", "视频", ".mp4", "video/mp4");

        assertNotEquals(first, second);
        String secondName = Path.of(second).getFileName().toString();
        assertTrue(secondName.matches("同名-视频-\\d+\\.mp4"), "实际: " + secondName);
        // 历史版本不覆盖
        assertEquals("v1", Files.readString(tempDir.resolve("final").resolve("同名-视频.mp4")));
        assertEquals("v2", Files.readString(tempDir.resolve(second)));
    }

    @Test
    void uploadFinal_truncatesOverlongTitle() {
        String longTitle = "长".repeat(200);
        String fileId = service.uploadFinal(stream("v"), longTitle, "视频", ".mp4", "video/mp4");

        String name = Path.of(fileId).getFileName().toString();
        assertTrue(name.length() <= 80 + "-视频.mp4".length(), "实际长度: " + name.length());
    }

    @Test
    void interfaceDefault_degradesToPlainUpload() {
        List<String> calls = new ArrayList<>();
        StorageService other = new StorageService() {
            @Override
            public String upload(InputStream is, String fileName, String contentType) {
                calls.add(fileName);
                return "oss/" + fileName;
            }
            @Override public InputStream download(String fileId) { return null; }
            @Override public void delete(String fileId) { }
            @Override public boolean exists(String fileId) { return false; }
            @Override public String getAccessUrl(String fileId) { return fileId; }
            @Override public String getStorageType() { return "oss"; }
        };

        String fileId = other.uploadFinal(stream("v"), "标题", "视频", ".mp4", "video/mp4");

        assertEquals("oss/标题-视频.mp4", fileId);
        assertEquals(List.of("标题-视频.mp4"), calls);
    }
}
