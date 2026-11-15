package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切片 3 T1：候选择优回写（人工闸门 + 归属校验 + VIDEO 委托 applyVideoCandidate）
 */
class ShotCandidateServiceTest {

    private ShotCandidateRepository candRepo;
    private StoryShotRepository shotRepo;
    private StoryScriptRepository scriptRepo;
    private VideoGenerationService videoGen;
    private ImageGeneratorFactory imageFactory;
    private StorageService storage;
    private ShotCandidateService service;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        candRepo = mock(ShotCandidateRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        scriptRepo = mock(StoryScriptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        videoGen = mock(VideoGenerationService.class);
        imageFactory = mock(ImageGeneratorFactory.class);
        storage = mock(StorageService.class);
        service = new ShotCandidateService(candRepo, shotRepo, scriptRepo, userRepo, videoGen,
                imageFactory, storage);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        when(scriptRepo.findById(7L)).thenReturn(Optional.of(s));
        shot = new StoryShot(); shot.setId(3L); shot.setScript(s);
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.findById(3L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void selectImageWritesBackMainAssetAndFlags() {
        ShotCandidate old = new ShotCandidate(); old.setId(1L); old.setShotId(3L);
        old.setType("IMAGE"); old.setSelected(true);
        ShotCandidate neu = new ShotCandidate(); neu.setId(2L); neu.setShotId(3L);
        neu.setType("IMAGE"); neu.setUrl("/images/new.png"); neu.setFileId("fnew");
        when(candRepo.findById(2L)).thenReturn(Optional.of(neu));
        when(candRepo.findByShotIdOrderByIdAsc(3L)).thenReturn(List.of(old, neu));
        // 切片 2 M3 不变量：换图同时失效旧视频
        shot.setVideoUrl("/images/old.mp4");
        shot.setVideoStatus("READY");
        service.select(7L, 2L);
        assertEquals("/images/new.png", shot.getImageUrl());
        assertEquals("fnew", shot.getImageFileId());
        assertEquals("READY", shot.getImageStatus());
        assertNull(shot.getVideoUrl());
        assertEquals("NONE", shot.getVideoStatus());
        assertFalse(old.isSelected());
        assertTrue(neu.isSelected());
    }

    @Test
    void selectVideoDelegatesApply() {
        ShotCandidate v = new ShotCandidate(); v.setId(5L); v.setShotId(3L);
        v.setType("VIDEO"); v.setUrl("/images/v.mp4"); v.setFileId("fv"); v.setDurationSeconds(4);
        when(candRepo.findById(5L)).thenReturn(Optional.of(v));
        when(candRepo.findByShotIdOrderByIdAsc(3L)).thenReturn(List.of(v));
        service.select(7L, 5L);
        verify(videoGen).applyVideoCandidate(shot, "/images/v.mp4", "fv", 4);
        assertTrue(v.isSelected());
    }

    @Test
    void selectOtherUsersCandidateRejected() {
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.select(7L, 2L));
    }

    @Test
    void selectCandidateOfForeignShotRejected() {
        ShotCandidate foreign = new ShotCandidate(); foreign.setId(9L); foreign.setShotId(99L);
        foreign.setType("IMAGE");
        when(candRepo.findById(9L)).thenReturn(Optional.of(foreign));
        assertThrows(RuntimeException.class, () -> service.select(7L, 9L));
    }

    // ==================== inpaint（T3） ====================

    @Test
    void inpaintRejectsOutOfBoundsRect() {
        assertThrows(IllegalArgumentException.class,
                () -> service.inpaint(3L, new double[]{0.8, 0.2, 0.5, 0.4}, "fix"));
        assertThrows(IllegalArgumentException.class,
                () -> service.inpaint(3L, new double[]{0.1, 0.2, 0, 0.4}, "fix"));
    }

    @Test
    void inpaintUnsupportedGeneratorRejected() {
        ImageGenerator noEdit = mock(ImageGenerator.class);
        when(noEdit.supportsEdit()).thenReturn(false);
        when(imageFactory.getActiveGenerator()).thenReturn(noEdit);
        assertThrows(IllegalArgumentException.class,
                () -> service.inpaint(3L, new double[]{0.1, 0.2, 0.5, 0.4}, "fix"));
    }

    @Test
    void inpaintRejectsExternalUrlWithoutStorage() {
        ImageGenerator editor = mock(ImageGenerator.class);
        when(editor.supportsEdit()).thenReturn(true);
        when(imageFactory.getActiveGenerator()).thenReturn(editor);
        shot.setImageUrl("http://169.254.169.254/latest/meta-data");
        shot.setImageFileId(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.inpaint(3L, new double[]{0.1, 0.2, 0.5, 0.4}, "fix"));
        verify(editor, never()).editWithMask(anyString(), anyString(), any(double[].class), anyInt(), anyInt());
    }

    @Test
    void inpaintCreatesUnselectedCandidate() throws Exception {
        ImageGenerator editor = mock(ImageGenerator.class);
        when(editor.supportsEdit()).thenReturn(true);
        when(editor.editWithMask(anyString(), anyString(), any(double[].class), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/inpainted.png", "finp", "wan27", 20, 16));
        when(imageFactory.getActiveGenerator()).thenReturn(editor);
        shot.setImageUrl("/images/a.png");
        shot.setImageFileId("f1");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 16, BufferedImage.TYPE_INT_RGB), "png", bos);
        when(storage.download("f1")).thenReturn(new ByteArrayInputStream(bos.toByteArray()));

        ShotCandidate c = service.inpaint(3L, new double[]{0.1, 0.2, 0.5, 0.4}, "fix hands");

        assertEquals("IMAGE", c.getType());
        assertEquals("/images/inpainted.png", c.getUrl());
        assertFalse(c.isSelected());
        assertNull(c.getQualityScore());
        verify(editor).editWithMask(eq("fix hands"), anyString(), any(double[].class), eq(20), eq(16));
        // 主资产不被重绘直接覆盖
        assertEquals("/images/a.png", shot.getImageUrl());
    }
}