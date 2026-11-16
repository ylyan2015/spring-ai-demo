package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 镜头素材绑定测试（Phase 5 Track B Task 3）
 * 绑定回写时长/归属拒绝/IMAGE 不写 video 字段/时长覆盖
 */
class StoryScriptServiceAssetBindTest {

    private StoryScriptRepository scriptRepo;
    private StoryShotRepository shotRepo;
    private MediaAssetRepository assetRepo;
    private StoryScriptService service;
    private StoryShot shot;

    @BeforeEach void setUp() {
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        assetRepo = mock(MediaAssetRepository.class);
        service = new StoryScriptService(scriptRepo, shotRepo, userRepo,
                mock(VideoScriptService.class), mock(StoryCharacterRepository.class), assetRepo);
        User u = new User();
        u.setId(1L);
        u.setUsername("u1");
        when(userRepo.findByUsername("u1")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u1", null, List.of()));

        StoryScript script = new StoryScript();
        script.setId(10L);
        script.setUserId(1L);
        shot = new StoryShot();
        shot.setId(20L);
        shot.setShotOrder(1);
        shot.setScript(script);
        when(scriptRepo.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(script));
        when(shotRepo.findByIdAndScriptId(20L, 10L)).thenReturn(Optional.of(shot));
        when(scriptRepo.save(any(StoryScript.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shotRepo.save(any(StoryShot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    private void asset(Long userId, String kind, Integer dur) {
        MediaAsset a = new MediaAsset();
        a.setId(30L);
        a.setUserId(userId);
        a.setKind(kind);
        a.setUrl("/images/a.mp4");
        a.setFileId("f1");
        a.setDurationSeconds(dur);
        when(assetRepo.findById(30L)).thenReturn(Optional.of(a));
    }

    @Test void bindVideoWritesDurationAndStatus() {
        asset(1L, "VIDEO", 7);
        StoryShot r = service.bindAsset(10L, 20L, 30L, true, null);
        assertEquals("/images/a.mp4", r.getVideoUrl());
        assertEquals("f1", r.getVideoFileId());
        assertEquals("READY", r.getVideoStatus());
        assertEquals(7, r.getDurationSeconds());
    }

    @Test void bindVideoDurationOverride() {
        asset(1L, "VIDEO", 7);
        StoryShot r = service.bindAsset(10L, 20L, 30L, true, 4);
        assertEquals(4, r.getDurationSeconds());
    }

    @Test void bindImageDoesNotWriteVideoFields() {
        asset(1L, "IMAGE", null);
        StoryShot r = service.bindAsset(10L, 20L, 30L, false, null);
        assertEquals("/images/a.mp4", r.getImageUrl());
        assertEquals("f1", r.getImageFileId());
        assertEquals("READY", r.getImageStatus());
        assertNull(r.getVideoUrl());
        assertNull(r.getVideoFileId());
    }

    @Test void foreignAssetRejected() {
        asset(2L, "VIDEO", 7);
        assertThrows(IllegalArgumentException.class, () -> service.bindAsset(10L, 20L, 30L, true, null));
        verify(shotRepo, never()).save(any(StoryShot.class));
    }

    @Test void bindImageInvalidatesStaleVideo() {
        // 审查 Major#1：镜头已有基于旧图的 READY 视频→绑新图须失效旧视频/尾帧（对齐 updateShot idiom）
        shot.setImageUrl("/images/old.png");
        shot.setImageFileId("old");
        shot.setVideoUrl("/images/old.mp4");
        shot.setVideoFileId("vold");
        shot.setVideoStatus("READY");
        shot.setLastFrameUrl("/images/old-last.jpg");
        shot.setLastFrameFileId("lfold");
        asset(1L, "IMAGE", null);
        StoryShot r = service.bindAsset(10L, 20L, 30L, false, null);
        assertNull(r.getVideoUrl());
        assertNull(r.getVideoFileId());
        assertNull(r.getLastFrameUrl());
        assertNull(r.getLastFrameFileId());
        assertEquals("NONE", r.getVideoStatus());
        assertEquals("/images/a.mp4", r.getImageUrl());
    }

    @Test void bindVideoRejectsImageAsset() {
        // 审查 Major#2：IMAGE 素材不得绑为视频片段（durationSeconds=null 会致 scriptToMap NPE 变砖）
        asset(1L, "IMAGE", null);
        assertThrows(IllegalArgumentException.class, () -> service.bindAsset(10L, 20L, 30L, true, null));
    }

    @Test void bindImageRejectsVideoAsset() {
        asset(1L, "VIDEO", 7);
        assertThrows(IllegalArgumentException.class, () -> service.bindAsset(10L, 20L, 30L, false, null));
    }
}