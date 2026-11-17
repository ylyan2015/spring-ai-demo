package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 成片标题贯穿：导出链把脚本标题塞进 ComposeOptions.finalTitle，
 * 封面生成把标题传给 renderVideoCover，供 uploadFinal 友好命名。
 */
class VideoExportFinalTitleWiringTest {

    private VideoComposeService compose;
    private VideoExportService service;
    private StoryScriptRepository scriptRepo;

    @BeforeEach
    void setUp() {
        scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        VideoExportRepository exportRepo = mock(VideoExportRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        compose = mock(VideoComposeService.class);
        service = new VideoExportService(scriptRepo, shotRepo, exportRepo, userRepo,
                compose, mock(BgmService.class), mock(SubtitleService.class),
                mock(StoryCharacterRepository.class), mock(SfxService.class));
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L); s.setTitle("那座山，翻过去了");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageStatus("READY"); shot.setImageUrl("/images/a.png"); shot.setDurationSeconds(5);
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(compose.compose(anyList(), any())).thenReturn(Map.of("videoUrl", "/images/v.mp4",
                "fileSizeBytes", 1L, "totalDuration", 5));
        when(exportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void exportHd_passesScriptTitleIntoComposeOptions() {
        service.exportHd(7L, "720p", "16:9", null, false, false, "zh");
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        assertEquals("那座山，翻过去了", cap.getValue().getFinalTitle());
    }

    @Test
    void generateCover_passesScriptTitleIntoRenderVideoCover() throws Exception {
        VideoExport record = new VideoExport();
        record.setId(50L); record.setScriptId(7L); record.setUserId(1L);
        record.setStatus("SUCCESS"); record.setVideoUrl("/images/v.mp4"); record.setDurationSeconds(60);
        VideoExportRepository exportRepo = captureExportRepo(record);
        when(compose.renderVideoCover(anyString(), anyDouble(), any())).thenReturn("/images/final/cover.jpg");

        service.generateCover(7L, null, null);

        verify(compose).renderVideoCover(eq("/images/v.mp4"), anyDouble(), eq("那座山，翻过去了"));
    }

    /** 重新装配注入 exportRepo（setUp 里 exportRepo 是局部变量，此处按需替换 service 内字段） */
    private VideoExportRepository captureExportRepo(VideoExport record) {
        VideoExportRepository exportRepo = mock(VideoExportRepository.class);
        when(exportRepo.findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(7L, 1L, "SUCCESS"))
                .thenReturn(Optional.of(record));
        when(exportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "exportRepository", exportRepo);
        return exportRepo;
    }
}
