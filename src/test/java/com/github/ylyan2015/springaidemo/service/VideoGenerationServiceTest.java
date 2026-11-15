package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedVideo;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.NoneVideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerationRequest;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGeneratorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoGenerationServiceTest {

    protected VideoGeneratorFactory videoFactory;
    protected VideoGenerator gen;
    protected ImageGeneratorFactory imageFactory;
    protected StoryScriptRepository scriptRepo;
    protected StoryShotRepository shotRepo;
    protected StorageService storage;
    protected VideoComposeService compose;
    protected VideoGenerationService service;
    protected StoryScript script;
    protected StoryShot shot;

    @BeforeEach
    void setUp() throws Exception {
        videoFactory = mock(VideoGeneratorFactory.class);
        gen = mock(VideoGenerator.class);
        when(gen.getType()).thenReturn("dashscope-wan");
        when(videoFactory.getActiveGenerator()).thenReturn(gen);
        imageFactory = mock(ImageGeneratorFactory.class);
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        compose = mock(VideoComposeService.class);
        service = new VideoGenerationService(videoFactory, imageFactory, scriptRepo, shotRepo,
                userRepo, new ObjectMapper(), storage, compose);
        ReflectionTestUtils.setField(service, "workers", 2);
        ReflectionTestUtils.setField(service, "retryAttempts", 1);
        ReflectionTestUtils.setField(service, "resolution", "720P");
        ReflectionTestUtils.setField(service, "storageType", "minio");
        service.init();

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        script.setContinuityMode("NONE");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));

        shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImageUrl("/images/a.png");
        shot.setImageFileId("img1");
        shot.setImagePrompt("base");
        shot.setDurationSeconds(5);
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(storage.getAccessUrl("img1")).thenReturn("http://minio/a.png");
        when(gen.generate(any())).thenReturn(new GeneratedVideo("/images/v.mp4", "fv", "dashscope-wan", 4));
        when(storage.download("fv")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(compose.probeDurationSeconds(any())).thenReturn(4);
        Path lastFrame = Files.createTempFile("lastframe-test", ".jpg");
        Files.write(lastFrame, new byte[]{1});
        when(compose.extractLastFrame(any(), any())).thenReturn(lastFrame);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("lf1");
        when(storage.getAccessUrl("lf1")).thenReturn("http://minio/lf1.jpg");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        service.shutdown();
    }

    @Test
    void generateForShotWritesReadyAndLastFrame() {
        StoryShot done = service.generateForShot(7L, 3L);
        assertEquals("READY", done.getVideoStatus());
        assertEquals("/images/v.mp4", done.getVideoUrl());
        assertEquals(4, done.getDurationSeconds());
        assertEquals("lf1", done.getLastFrameFileId());
        assertEquals("http://minio/lf1.jpg", done.getLastFrameUrl());
    }

    @Test
    void noneGeneratorThrows() {
        when(videoFactory.getActiveGenerator()).thenReturn(new NoneVideoGenerator());
        assertThrows(RuntimeException.class, () -> service.generateForShot(7L, 3L));
    }

    @Test
    void localImageWithoutPublicUrlFailsWithGuidance() {
        ReflectionTestUtils.setField(service, "storageType", "local");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.generateForShot(7L, 3L));
        assertTrue(ex.getMessage().contains("oss/minio"), ex.getMessage());
        assertEquals("FAILED", shot.getVideoStatus());
    }

    @Test
    void retryOnceOnFailure() {
        when(gen.generate(any()))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(new GeneratedVideo("/images/v.mp4", "fv", "dashscope-wan", 4));
        StoryShot done = service.generateForShot(7L, 3L);
        assertEquals("READY", done.getVideoStatus());
        verify(gen, times(2)).generate(any());
    }

    @Test
    void motionTemplateInjected() {
        service.generateForShot(7L, 3L);
        var cap = org.mockito.ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen).generate(cap.capture());
        assertTrue(cap.getValue().prompt().contains("subtle motion"));
        assertEquals("http://minio/a.png", cap.getValue().firstFrameUrl());
        assertNull(cap.getValue().lastFrameUrl());
    }
}