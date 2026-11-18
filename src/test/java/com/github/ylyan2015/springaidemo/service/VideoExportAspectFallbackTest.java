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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * P0-3 竖屏优先：导出请求未指定画幅时 fallback 脚本 aspectRatio（显式请求优先），
 * 9:16 脚本默认导出 720x1280；双 null 保持存量 16:9 行为（向后兼容契约）。
 */
class VideoExportAspectFallbackTest {

    private VideoComposeService compose;
    private VideoExportRepository exportRepo;
    private VideoExportService service;
    private StoryScript script;

    @BeforeEach
    void setUp() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        exportRepo = mock(VideoExportRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        compose = mock(VideoComposeService.class);
        service = new VideoExportService(scriptRepo, shotRepo, exportRepo, userRepo,
                compose, mock(BgmService.class), mock(SubtitleService.class),
                mock(StoryCharacterRepository.class), mock(SfxService.class));
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        script.setTitle("竖屏短剧");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
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

    private VideoComposeService.ComposeOptions capturedOptions() {
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        return cap.getValue();
    }

    // ==================== effectiveAspect 静态助手 ====================

    @Test
    void explicitRequestWinsOverScriptAspect() {
        assertEquals("16:9", VideoExportService.effectiveAspect("16:9", "9:16"));
        assertEquals("1:1", VideoExportService.effectiveAspect("1:1", "9:16"));
    }

    @Test
    void blankRequestFallsBackToScriptAspect() {
        assertEquals("9:16", VideoExportService.effectiveAspect(null, "9:16"));
        assertEquals("9:16", VideoExportService.effectiveAspect("  ", "9:16"));
    }

    @Test
    void bothNullKeepsLegacyLandscape() {
        assertEquals("16:9", VideoExportService.effectiveAspect(null, null));
    }

    @Test
    void invalidValuesNormalizeToLandscape() {
        assertEquals("16:9", VideoExportService.effectiveAspect("4:3", "9:16"));
        assertEquals("16:9", VideoExportService.effectiveAspect(null, "4:3"));
    }

    // ==================== 导出接线 ====================

    @Test
    void hdExportWithoutAspectUsesScriptPortrait() {
        script.setAspectRatio("9:16");
        service.exportHd(7L, "720p", null, null, false, false, "zh");
        VideoComposeService.ComposeOptions options = capturedOptions();
        assertEquals(720, options.getWidth());
        assertEquals(1280, options.getHeight());
        // 审查 Minor#2：导出记录必须落库 fallback 后的画幅与实际分辨率（守 ExportMeta 接线）
        ArgumentCaptor<VideoExport> recCap = ArgumentCaptor.forClass(VideoExport.class);
        verify(exportRepo).save(recCap.capture());
        assertEquals("9:16", recCap.getValue().getAspectRatio());
        assertEquals(720, recCap.getValue().getWidth().intValue());
        assertEquals(1280, recCap.getValue().getHeight().intValue());
    }

    @Test
    void hdExportExplicitLandscapeOverridesScriptPortrait() {
        script.setAspectRatio("9:16");
        service.exportHd(7L, "720p", "16:9", null, false, false, "zh");
        VideoComposeService.ComposeOptions options = capturedOptions();
        assertEquals(1280, options.getWidth());
        assertEquals(720, options.getHeight());
    }

    @Test
    void previewWithoutAspectUsesScriptPortrait() {
        script.setAspectRatio("9:16");
        service.exportPreview(7L, null, null, false, "zh");
        VideoComposeService.ComposeOptions options = capturedOptions();
        assertEquals(480, options.getWidth());
        assertEquals(854, options.getHeight());
    }

    @Test
    void nullScriptAspectKeepsLegacyLandscapeExport() {
        service.exportHd(7L, "720p", null, null, false, false, "zh");
        VideoComposeService.ComposeOptions options = capturedOptions();
        assertEquals(1280, options.getWidth());
        assertEquals(720, options.getHeight());
    }
}