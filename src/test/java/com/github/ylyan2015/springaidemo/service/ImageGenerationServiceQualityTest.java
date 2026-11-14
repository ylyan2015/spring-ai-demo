package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片生成质量评估集成单元测试（模块 J）
 * 覆盖：低分自动重试、达标即停、重试上限保留最高分、关闭评估不改变原流程
 */
class ImageGenerationServiceQualityTest {

    private ImageGeneratorFactory generatorFactory;
    private StoryScriptRepository scriptRepository;
    private StoryShotRepository shotRepository;
    private UserRepository userRepository;
    private ImageQualityService qualityService;
    private ImageGenerator generator;
    private ImageGenerationService service;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        generatorFactory = mock(ImageGeneratorFactory.class);
        scriptRepository = mock(StoryScriptRepository.class);
        shotRepository = mock(StoryShotRepository.class);
        userRepository = mock(UserRepository.class);
        qualityService = mock(ImageQualityService.class);
        generator = mock(ImageGenerator.class);

        service = new ImageGenerationService(generatorFactory, scriptRepository, shotRepository,
                userRepository, new ObjectMapper(), qualityService);

        // 登录上下文（三参构造才是已认证状态）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));

        StoryScript script = new StoryScript();
        script.setId(1L);
        when(scriptRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(script));

        shot = new StoryShot();
        shot.setId(2L);
        shot.setShotOrder(1);
        shot.setImagePrompt("a cat on a roof");
        shot.setScript(script);
        when(shotRepository.findByIdAndScriptId(2L, 1L)).thenReturn(Optional.of(shot));
        when(shotRepository.save(any(StoryShot.class))).thenAnswer(inv -> inv.getArgument(0));

        when(generatorFactory.getActiveGenerator()).thenReturn(generator);
        when(generator.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("http://img/1.png", "f1", "dashscope", 1280, 720));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ImageQualityScore score(double overall) {
        ImageQualityScore s = new ImageQualityScore();
        s.setOverallScore(overall);
        s.setClarityScore(overall);
        s.setRelevanceScore(overall);
        s.setAestheticScore(overall);
        s.setFeedback(overall < 6 ? "画面模糊，主体不清晰" : "质量良好");
        s.setLlmEvaluated(true);
        return s;
    }

    @Test
    void lowScoreTriggersRegenerationUntilPass() {
        when(qualityService.isEnabled()).thenReturn(true);
        when(qualityService.getMaxRetries()).thenReturn(2);
        when(qualityService.evaluate(anyString(), anyString()))
                .thenReturn(score(4.0), score(8.0));
        when(qualityService.shouldRegenerate(any())).thenAnswer(inv -> {
            ImageQualityScore s = inv.getArgument(0);
            return s != null && s.getOverallScore() < 6.0;
        });

        StoryShot result = service.generateForShot(1L, 2L, null);

        verify(generator, times(2)).generate(anyString(), any(), anyInt(), anyInt());
        assertEquals("READY", result.getImageStatus());
        assertEquals(8.0, result.getQualityScore(), 0.01);
    }

    @Test
    void exhaustedRetriesKeepBestScoringImage() {
        when(qualityService.isEnabled()).thenReturn(true);
        when(qualityService.getMaxRetries()).thenReturn(2);
        when(qualityService.evaluate(anyString(), anyString()))
                .thenReturn(score(3.0), score(5.0), score(4.0));
        when(qualityService.shouldRegenerate(any())).thenReturn(true);
        when(generator.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("http://img/1.png", "f1", "dashscope", 1280, 720),
                        new GeneratedImage("http://img/2.png", "f2", "dashscope", 1280, 720),
                        new GeneratedImage("http://img/3.png", "f3", "dashscope", 1280, 720));

        StoryShot result = service.generateForShot(1L, 2L, null);

        // 初次 + 2 次重试 = 3 次生成；保留最高分（5.0）对应的第二张
        verify(generator, times(3)).generate(anyString(), any(), anyInt(), anyInt());
        assertEquals("http://img/2.png", result.getImageUrl());
        assertEquals(5.0, result.getQualityScore(), 0.01);
    }

    @Test
    void passingScoreDoesNotRegenerate() {
        when(qualityService.isEnabled()).thenReturn(true);
        when(qualityService.getMaxRetries()).thenReturn(2);
        when(qualityService.evaluate(anyString(), anyString())).thenReturn(score(8.5));
        when(qualityService.shouldRegenerate(any())).thenReturn(false);

        StoryShot result = service.generateForShot(1L, 2L, null);

        verify(generator, times(1)).generate(anyString(), any(), anyInt(), anyInt());
        assertEquals(8.5, result.getQualityScore(), 0.01);
    }

    @Test
    void disabledQualityKeepsOriginalFlow() {
        when(qualityService.isEnabled()).thenReturn(false);

        StoryShot result = service.generateForShot(1L, 2L, null);

        verify(generator, times(1)).generate(anyString(), any(), anyInt(), anyInt());
        assertNull(result.getQualityScore());
        verify(qualityService, times(0)).evaluate(anyString(), anyString());
    }

    @Test
    void evaluationFailureDoesNotBlockGeneration() {
        when(qualityService.isEnabled()).thenReturn(true);
        when(qualityService.getMaxRetries()).thenReturn(2);
        when(qualityService.evaluate(anyString(), anyString()))
                .thenThrow(new RuntimeException("评估服务不可用"));

        StoryShot result = service.generateForShot(1L, 2L, null);

        assertEquals("READY", result.getImageStatus());
        assertNull(result.getQualityScore());
        verify(generator, times(1)).generate(anyString(), any(), anyInt(), anyInt());
    }
}