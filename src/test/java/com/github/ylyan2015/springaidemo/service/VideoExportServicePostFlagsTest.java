package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoExportServicePostFlagsTest {

    private VideoComposeService compose;
    private SfxService sfx;
    private VideoExportService service;

    @BeforeEach
    void setUp() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        VideoExportRepository exportRepo = mock(VideoExportRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        compose = mock(VideoComposeService.class);
        sfx = mock(SfxService.class);
        service = new VideoExportService(scriptRepo, shotRepo, exportRepo, userRepo,
                compose, mock(BgmService.class), mock(SubtitleService.class),
                mock(StoryCharacterRepository.class), sfx, mock(PublishPackService.class));
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageStatus("READY"); shot.setImageUrl("/images/a.png"); shot.setDurationSeconds(5);
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(compose.compose(anyList(), any())).thenReturn(Map.of("videoUrl", "/images/v.mp4",
                "fileSizeBytes", 1L, "totalDuration", 5));
        when(exportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "sfxEnabled", true);
        when(sfx.recommendForScript(anyList())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void withPostPassesFlagsToComposeOptions() {
        service.exportHdWithPost(7L, "720p", "16:9", null, false, false, "zh",
                new VideoExportService.PostFlags(true, false, true));
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        assertTrue(cap.getValue().isUpscale());
        assertFalse(cap.getValue().isInterpolate());
        verify(sfx).recommendForScript(anyList());
    }

    @Test
    void legacyExportHdDefaultsAllFlagsFalse() {
        service.exportHd(7L, "720p", "16:9", null, false, false, "zh");
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        assertFalse(cap.getValue().isUpscale());
        assertFalse(cap.getValue().isInterpolate());
        verify(sfx, never()).recommendForScript(anyList());
    }
}