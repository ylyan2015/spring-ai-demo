package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.VisualStyle;
import com.github.ylyan2015.springaidemo.video.generator.EndCardImageRenderer;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.LoraResolver;
import com.github.ylyan2015.springaidemo.video.generator.ReferenceImageResolver;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-3 审查 Minor#2：批量生图装配点竖屏接线守卫——
 * generateShotWithProgress 必须经四参 assemble 注入竖构图词、经 effectiveSize 切 720x1280；
 * 回退为三参/硬编码尺寸时本用例必须独红（此前该分支无测试覆盖）。
 */
class ImageGenerationBatchPortraitTest {

    private ImageGenerator gen;
    private ImageGenerationService service;
    private StoryScript script;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolveAll(any(), any())).thenReturn(List.of());
        service = new ImageGenerationService(factory, scriptRepo, shotRepo, mock(UserRepository.class),
                new ObjectMapper(), quality, new ShotPromptAssembler(), resolver, new LoraResolver(false),
                mock(EndCardImageRenderer.class));
        ReflectionTestUtils.setField(service, "parallelism", 1);
        ReflectionTestUtils.setField(service, "retryAttempts", 0);
        ReflectionTestUtils.setField(service, "retryDelayMs", 0L);
        ReflectionTestUtils.setField(service, "defaultSize", "1280x720");
        ReflectionTestUtils.setField(service, "totalTimeoutSeconds", 10);
        service.init();

        script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImagePrompt("base");
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "mock", 64, 64));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        SecurityContextHolder.clearContext();
    }

    /** 反射直调 runBatchGeneration（同 TimeoutCancelTest idiom）：mock 生成即时返回，当前线程同步跑完 */
    private void runBatch() throws Exception {
        Method m = ImageGenerationService.class.getDeclaredMethod("runBatchGeneration",
                Long.class, StoryScript.class, List.class, VisualStyle.class,
                SecurityContext.class, Consumer.class);
        m.setAccessible(true);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        m.invoke(service, 7L, script, List.of(shot), VisualStyle.fromName(null), ctx,
                (Consumer<com.github.ylyan2015.springaidemo.video.ImageGenerationProgress>) p -> {});
    }

    @Test
    void portraitBatchGeneratesAt720x1280WithVerticalPrompt() throws Exception {
        script.setAspectRatio("9:16");
        runBatch();
        verify(gen).generate(contains("vertical framing"), any(), eq(720), eq(1280));
    }

    @Test
    void landscapeBatchKeepsConfiguredSizeAndLegacyPrompt() throws Exception {
        runBatch();
        verify(gen).generate(argThat(p -> !p.contains("vertical framing")), any(), eq(1280), eq(720));
    }
}