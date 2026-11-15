package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 批量导出 ZIP 打包下载单元测试（模块 I 收尾）
 * 覆盖：条目命名规则、归属/状态校验、ZIP 内容与 manifest
 */
class VideoExportServiceZipTest {

    private VideoExportRepository exportRepository;
    private UserRepository userRepository;
    private VideoComposeService videoComposeService;
    private VideoExportService service;

    @BeforeEach
    void setUp() {
        exportRepository = Mockito.mock(VideoExportRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        videoComposeService = Mockito.mock(VideoComposeService.class);
        service = new VideoExportService(
                Mockito.mock(StoryScriptRepository.class),
                Mockito.mock(StoryShotRepository.class),
                exportRepository,
                userRepository,
                videoComposeService,
                Mockito.mock(BgmService.class),
                Mockito.mock(SubtitleService.class),
                Mockito.mock(com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository.class),
                Mockito.mock(SfxService.class));

        User user = Mockito.mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private VideoExport record(long id, String resolution, String aspect, String status) {
        VideoExport r = new VideoExport();
        r.setId(id);
        r.setScriptId(100L);
        r.setUserId(1L);
        r.setResolution(resolution);
        r.setAspectRatio(aspect);
        r.setStatus(status);
        r.setVideoUrl("/images/2026/11/14/video_" + id + ".mp4");
        r.setDurationSeconds(24);
        return r;
    }

    // ==================== 条目命名 ====================

    @Test
    void zipEntryBaseNameReplacesColonInAspect() {
        VideoExport r = record(12L, "1080p", "9:16", "SUCCESS");
        assertEquals("01_1080p_9x16_12", VideoExportService.zipEntryBaseName(1, r));
        assertEquals("03_720p_1x1_34", VideoExportService.zipEntryBaseName(3, record(34L, "720p", "1:1", "SUCCESS")));
    }

    // ==================== 校验 ====================

    @Test
    void prepareBatchZipRejectsEmptySelection() {
        assertThrows(RuntimeException.class, () -> service.prepareBatchZip(List.of()));
        assertThrows(RuntimeException.class, () -> service.prepareBatchZip(null));
    }

    @Test
    void prepareBatchZipRejectsMoreThanTen() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 11; i++) ids.add(i);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.prepareBatchZip(ids));
        assertTrue(ex.getMessage().contains("10"));
    }

    @Test
    void prepareBatchZipRejectsNotOwnedRecord() {
        when(exportRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.prepareBatchZip(List.of(5L)));
    }

    @Test
    void prepareBatchZipRejectsFailedRecord() {
        when(exportRepository.findByIdAndUserId(5L, 1L))
                .thenReturn(Optional.of(record(5L, "720p", "16:9", "FAILED")));
        assertThrows(RuntimeException.class, () -> service.prepareBatchZip(List.of(5L)));
    }

    @Test
    void prepareBatchZipReturnsOwnedSuccessRecords() {
        VideoExport r = record(5L, "1080p", "9:16", "SUCCESS");
        when(exportRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(r));
        List<VideoExport> result = service.prepareBatchZip(List.of(5L));
        assertEquals(1, result.size());
        assertSame(r, result.get(0));
    }

    // ==================== ZIP 内容 ====================

    @Test
    void writeBatchZipContainsVideosCoversAndManifest() throws Exception {
        VideoExport r1 = record(12L, "1080p", "9:16", "SUCCESS");
        r1.setBgmName("sunny morning");
        r1.setCoverUrl("/images/2026/11/14/cover_12.jpg");
        VideoExport r2 = record(13L, "1080p", "16:9", "SUCCESS");

        // mock 下载：向目标路径写入少量字节
        doAnswer(inv -> {
            Path target = inv.getArgument(1);
            Files.write(target, "fake-media".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(videoComposeService).downloadMediaTo(any(String.class), any(Path.class));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        service.writeBatchZip(List.of(r1, r2), bos);

        List<String> entries = new ArrayList<>();
        String manifest = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                if ("manifest.txt".equals(entry.getName())) {
                    manifest = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(entries.contains("01_1080p_9x16_12.mp4"));
        assertTrue(entries.contains("01_1080p_9x16_12_cover.jpg"));
        assertTrue(entries.contains("02_1080p_16x9_13.mp4"));
        assertTrue(entries.contains("manifest.txt"));
        assertNotNull(manifest);
        assertTrue(manifest.contains("01_1080p_9x16_12.mp4"));
        assertTrue(manifest.contains("sunny morning"));
    }
}