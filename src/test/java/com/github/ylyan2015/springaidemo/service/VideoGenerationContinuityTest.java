package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoGenerationContinuityTest extends VideoGenerationServiceTest {

    private StoryShot secondShot() {
        StoryShot s2 = new StoryShot();
        s2.setId(4L);
        s2.setShotOrder(2);
        s2.setImageUrl("/images/b.png");
        s2.setImageFileId("img2");
        s2.setImagePrompt("base2");
        s2.setSceneDescription("next scene");
        s2.setDurationSeconds(5);
        when(shotRepo.findByIdAndScriptId(4L, 7L)).thenReturn(java.util.Optional.of(s2));
        when(storage.getAccessUrl("img2")).thenReturn("http://minio/b.png");
        return s2;
    }

    @Test
    void prevLastFrameModeChainsFirstFrame() {
        StoryShot s2 = secondShot();
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot, s2));
        script.setContinuityMode("PREV_LAST_FRAME");

        service.generateForScriptBlocking(7L);

        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        assertEquals("http://minio/a.png", cap.getAllValues().get(0).firstFrameUrl());
        assertEquals("http://minio/lf1.jpg", cap.getAllValues().get(1).firstFrameUrl());
        // PREV 模式仅覆盖首帧，不产生尾帧（规格 §5.3）
        assertNull(cap.getAllValues().get(0).lastFrameUrl());
        assertNull(cap.getAllValues().get(1).lastFrameUrl());
    }

    @Test
    void prevModeSingleShotUsesPrevLastFrameAsFirstFrame() {
        StoryShot s2 = secondShot();
        shot.setLastFrameFileId("lf0");
        shot.setLastFrameUrl("http://minio/lf0.jpg");
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot, s2));
        script.setContinuityMode("PREV_LAST_FRAME");

        service.generateForShot(7L, 4L);

        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen).generate(cap.capture());
        assertEquals("http://minio/lf0.jpg", cap.getValue().firstFrameUrl());
        assertNull(cap.getValue().lastFrameUrl());
    }

    @Test
    void prevModeDegradedFinalizeFallsBackToOwnImage() {
        StoryShot s2 = secondShot();
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot, s2));
        script.setContinuityMode("PREV_LAST_FRAME");
        when(compose.extractLastFrame(any(), any())).thenThrow(new RuntimeException("ffmpeg missing"));

        service.generateForScriptBlocking(7L);

        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        // 第一镜 finalize 降级无尾帧 → 第二镜回退本镜定妆图，不沿用旧链
        assertEquals("http://minio/b.png", cap.getAllValues().get(1).firstFrameUrl());
    }

    @Test
    void generatedLastFrameModeUsesWan27() {
        StoryShot s2 = secondShot();
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot, s2));
        script.setContinuityMode("GENERATED_LAST_FRAME");
        ImageGenerator wan27 = mock(ImageGenerator.class);
        when(wan27.isAvailable()).thenReturn(true);
        when(wan27.supportsReferences()).thenReturn(true);
        when(wan27.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("http://last/2.png", "lx", "wan27", 1, 1));
        when(imageFactory.getAllGenerators()).thenReturn(Map.of("wan27", wan27));

        service.generateForScriptBlocking(7L);

        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        // 并行批量下捕获顺序不确定，按首帧 URL 识别镜头
        VideoGenerationRequest r1 = cap.getAllValues().stream()
                .filter(r -> "http://minio/a.png".equals(r.firstFrameUrl())).findFirst().orElseThrow();
        VideoGenerationRequest r2 = cap.getAllValues().stream()
                .filter(r -> "http://minio/b.png".equals(r.firstFrameUrl())).findFirst().orElseThrow();
        assertEquals("http://last/2.png", r1.lastFrameUrl());
        assertNull(r2.lastFrameUrl());
    }

    @Test
    void generatedModeFallsBackWhenWan27Unavailable() {
        script.setContinuityMode("GENERATED_LAST_FRAME");
        when(imageFactory.getAllGenerators()).thenReturn(Map.of());
        service.generateForShot(7L, 3L);
        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen).generate(cap.capture());
        assertNull(cap.getValue().lastFrameUrl());
    }
}