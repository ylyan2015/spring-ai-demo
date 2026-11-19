package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 导出联动发布包（计划 Task 8）：高清导出成功后落派生稿并写 final/&lt;标题&gt;-发布包.txt，
 * 批量 ZIP 每条记录附一个 _发布包.txt。
 *
 * <p>本类最重要的判据是「附属能力不得反过来阻断出片」：发布包写失败、ZIP 里取文本失败，
 * 都只该少一个附件，不该让已经合成完的视频报导出失败。
 */
class VideoExportPublishPackTest {

    private VideoExportService service;
    private PublishPackService packs;
    private VideoComposeService compose;

    @BeforeEach
    void setUp() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        VideoExportRepository exportRepo = mock(VideoExportRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        compose = mock(VideoComposeService.class);
        packs = mock(PublishPackService.class);
        service = new VideoExportService(scriptRepo, shotRepo, exportRepo, userRepo, compose,
                mock(BgmService.class), mock(SubtitleService.class), mock(StoryCharacterRepository.class),
                mock(SfxService.class), packs);

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        StoryScript s = new StoryScript();
        s.setId(7L);
        s.setUserId(1L);
        s.setTitle("钱坑漫评01");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImageStatus("READY");
        shot.setImageUrl("/images/a.png");
        shot.setDurationSeconds(5);
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(compose.compose(anyList(), any())).thenReturn(Map.of("videoUrl", "/images/v.mp4",
                "fileSizeBytes", 1L, "totalDuration", 5));
        when(exportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private VideoExport record(long id) {
        VideoExport r = new VideoExport();
        r.setId(id);
        r.setScriptId(7L);
        r.setUserId(1L);
        r.setResolution("1080p");
        r.setAspectRatio("16:9");
        r.setStatus("SUCCESS");
        r.setVideoUrl("/images/2026/11/19/video_" + id + ".mp4");
        r.setDurationSeconds(24);
        return r;
    }

    /** 把下载与发布包取文本都按「写文件/返回串」mock 掉，然后解出 ZIP 条目名 */
    private Map<String, String> runBatchZip(List<VideoExport> records) throws Exception {
        doAnswer(inv -> {
            Files.write(inv.getArgument(1), "fake-media".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(compose).downloadMediaTo(any(String.class), any(Path.class));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        service.writeBatchZip(records, bos);
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    // ==================== 导出成功后联动 ====================

    @Test
    void hdExportSyncsPublishPackAndReportsUrl() {
        when(packs.syncAfterExport(7L, 1L, "钱坑漫评01")).thenReturn("/images/final/钱坑漫评01-发布包.txt");
        Map<String, Object> result = service.exportHd(7L, "720p", "16:9", null, false, false, "zh");

        verify(packs).syncAfterExport(7L, 1L, "钱坑漫评01");
        assertEquals("/images/final/钱坑漫评01-发布包.txt", result.get("publishPackUrl"),
                "响应要给出发布包访问地址，用户不用去目录里翻：" + result.keySet());
    }

    @Test
    void previewExportDoesNotWritePublishPack() {
        service.exportPreview(7L, "16:9", null, false, "zh");
        verifyNoInteractions(packs);
    }

    @Test
    void failedExportDoesNotWritePublishPack() {
        when(compose.compose(anyList(), any())).thenThrow(new RuntimeException("ffmpeg exit 1"));
        assertThrows(RuntimeException.class,
                () -> service.exportHd(7L, "720p", "16:9", null, false, false, "zh"));
        verifyNoInteractions(packs);
    }

    @Test
    void publishPackFailureNeverBreaksExport() {
        when(packs.syncAfterExport(anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("disk full"));
        Map<String, Object> result = assertDoesNotThrow(
                () -> service.exportHd(7L, "720p", "16:9", null, false, false, "zh"));
        assertNotNull(result.get("video"), "成片已经出来了，附属文本写失败不该把导出改判成失败");
        assertNull(result.get("publishPackUrl"));
    }

    // ==================== ZIP 附带 ====================

    @Test
    void zipEntryIncludesPublishPackAndManifestColumns() throws Exception {
        when(packs.packTextFor(7L, 1L)).thenReturn("【标题】钱坑漫评01\n【简介】x");
        when(packs.summaryFor(7L, 1L)).thenReturn(new PublishPackService.Summary(
                "钱坑漫评01", 2, 512, "动漫杂谈", "MANUAL"));

        Map<String, String> entries = runBatchZip(List.of(record(12L)));

        String packName = "01_1080p_16x9_12_发布包.txt";
        assertTrue(entries.containsKey(packName), "ZIP 条目：" + entries.keySet());
        assertTrue(entries.get(packName).contains("钱坑漫评01"));
        String manifest = entries.get("manifest.txt");
        assertTrue(manifest.contains("钱坑漫评01"), manifest);
        assertTrue(manifest.contains("标签 2"), manifest);
        assertTrue(manifest.contains("简介 512"), manifest);
        assertTrue(manifest.contains("动漫杂谈"), manifest);
        assertTrue(manifest.contains("人工"), manifest);
    }

    @Test
    void zipCompletesEvenWhenPackTextFails() throws Exception {
        when(packs.packTextFor(anyLong(), anyLong())).thenThrow(new RuntimeException("脚本不存在或无权访问"));
        Map<String, String> entries = runBatchZip(List.of(record(12L)));

        assertTrue(entries.containsKey("01_1080p_16x9_12.mp4"), "打包主产物不得因发布包失败而缺失：" + entries.keySet());
        assertFalse(entries.containsKey("01_1080p_16x9_12_发布包.txt"));
        assertTrue(entries.containsKey("manifest.txt"));
    }
}
