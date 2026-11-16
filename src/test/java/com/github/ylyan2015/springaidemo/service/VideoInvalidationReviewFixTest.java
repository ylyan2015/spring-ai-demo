package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切片 2 审查 M3 回归：图片变更（改提示词/换图/重新生图）必须同步失效旧视频与尾帧
 */
class VideoInvalidationReviewFixTest {

    private StoryScriptRepository scriptRepo;
    private StoryShotRepository shotRepo;
    private StoryScriptService scriptService;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        scriptService = new StoryScriptService(scriptRepo, shotRepo, userRepo,
                mock(VideoScriptService.class), mock(StoryCharacterRepository.class), null);
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript();
        s.setId(7L);
        s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        shot = new StoryShot();
        shot.setId(3L);
        shot.setImagePrompt("a cat on a roof");
        shot.setImageUrl("/images/old.png");
        shot.setImageFileId("old");
        shot.setVideoUrl("/images/old.mp4");
        shot.setVideoFileId("vold");
        shot.setVideoStatus("READY");
        shot.setLastFrameUrl("/images/old-last.jpg");
        shot.setLastFrameFileId("lfold");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void assertVideoInvalidated(StoryShot s) {
        assertNull(s.getVideoUrl());
        assertNull(s.getVideoFileId());
        assertNull(s.getLastFrameUrl());
        assertNull(s.getLastFrameFileId());
        assertEquals("NONE", s.getVideoStatus());
    }

    @Test
    void imagePromptChangeInvalidatesVideo() {
        StoryShot updated = scriptService.updateShot(7L, 3L, Map.of("imagePrompt", "new prompt"));
        assertVideoInvalidated(updated);
        assertEquals("PENDING", updated.getImageStatus());
    }

    @Test
    void imageUrlChangeInvalidatesVideo() {
        StoryShot updated = scriptService.updateShot(7L, 3L, Map.of("imageUrl", "/images/new.png"));
        assertVideoInvalidated(updated);
    }

    @Test
    void identicalImageUrlKeepsVideo() {
        StoryShot updated = scriptService.updateShot(7L, 3L, Map.of("imageUrl", "/images/old.png"));
        assertEquals("/images/old.mp4", updated.getVideoUrl());
        assertEquals("READY", updated.getVideoStatus());
    }

    @Test
    void regenerateImageInvalidatesVideo() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        ImageGenerator gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("http://img/new.png", "fnew", "dashscope", 1280, 720));
        UserRepository userRepo = mock(UserRepository.class);
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        ImageGenerationService imageService = new ImageGenerationService(factory, scriptRepo, shotRepo,
                userRepo, new ObjectMapper(), mock(ImageQualityService.class),
                new com.github.ylyan2015.springaidemo.video.ShotPromptAssembler(),
                new com.github.ylyan2015.springaidemo.video.generator.ReferenceImageResolver(
                        mock(StoryCharacterRepository.class),
                        mock(com.github.ylyan2015.springaidemo.storage.StorageService.class)),
                new com.github.ylyan2015.springaidemo.video.generator.LoraResolver(false));

        StoryShot updated = imageService.generateForShot(7L, 3L, null);

        assertEquals("READY", updated.getImageStatus());
        assertVideoInvalidated(updated);
    }
}