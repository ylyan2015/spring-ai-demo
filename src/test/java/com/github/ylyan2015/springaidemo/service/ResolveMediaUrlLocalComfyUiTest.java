package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGeneratorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5 切片 1：local 存储 × comfyui 生成器首帧直通分支（相对 URL，生成器同源取字节）
 * 存量行为回归：local × dashscope-wan / null 仍抛 oss/minio 指引。
 */
class ResolveMediaUrlLocalComfyUiTest {

    private VideoGeneratorFactory videoFactory;
    private VideoGenerator gen;
    private StorageService storage;
    private VideoGenerationService service;

    @BeforeEach
    void setUp() {
        videoFactory = mock(VideoGeneratorFactory.class);
        gen = mock(VideoGenerator.class);
        when(videoFactory.getActiveGenerator()).thenReturn(gen);
        storage = mock(StorageService.class);
        service = new VideoGenerationService(videoFactory, mock(ImageGeneratorFactory.class),
                mock(StoryScriptRepository.class), mock(StoryShotRepository.class),
                mock(UserRepository.class), new ObjectMapper(), storage, mock(VideoComposeService.class));
        ReflectionTestUtils.setField(service, "storageType", "local");
        when(storage.getAccessUrl("img1")).thenReturn("/images/a.png");
    }

    @Test
    void localComfyUiPassesRelativeUrl() {
        when(gen.getType()).thenReturn("comfyui");
        assertEquals("/images/a.png", service.resolveMediaUrl(null, "img1"));
    }

    @Test
    void localDashscopeStillThrows() {
        when(gen.getType()).thenReturn("dashscope-wan");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveMediaUrl(null, "img1"));
        assertTrue(ex.getMessage().contains("oss/minio"), ex.getMessage());
    }

    @Test
    void localNullActiveStillThrows() {
        when(videoFactory.getActiveGenerator()).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.resolveMediaUrl(null, "img1"));
    }

    @Test
    void httpUrlUnchangedUnderLocal() {
        assertEquals("http://x/a.png", service.resolveMediaUrl("http://x/a.png", null));
    }
}