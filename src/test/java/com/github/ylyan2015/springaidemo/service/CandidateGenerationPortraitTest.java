package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ShotCandidateRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.LoraResolver;
import com.github.ylyan2015.springaidemo.video.generator.ReferenceImageResolver;
import com.github.ylyan2015.springaidemo.video.generator.VideoGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-3 审查 Minor#2：抽卡装配点竖屏接线守卫——
 * generateImageCandidates 必须经四参 assemble 注入竖构图词、经 effectiveSize 切 720x1280；
 * 回退为三参/硬编码 1280x720 时本用例必须独红（此前该分支无测试覆盖）。
 */
class CandidateGenerationPortraitTest {

    private ImageGenerator gen;
    private CandidateGenerationService service;
    private StoryScript script;

    @BeforeEach
    void setUp() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ShotCandidateRepository candRepo = mock(ShotCandidateRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolveAll(any(), any())).thenReturn(List.of());
        service = new CandidateGenerationService(factory, mock(VideoGeneratorFactory.class),
                scriptRepo, shotRepo, userRepo, candRepo, new ShotPromptAssembler(), resolver,
                quality, mock(VideoGenerationService.class), new LoraResolver(false));
        ReflectionTestUtils.setField(service, "imageCount", 2);
        ReflectionTestUtils.setField(service, "defaultSize", "1280x720");

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImagePrompt("base");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "mock", 64, 64));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void portraitCandidatesGenerateAt720x1280WithVerticalPrompt() {
        script.setAspectRatio("9:16");
        service.generateImageCandidates(7L, 3L);
        verify(gen, times(2)).generate(contains("vertical framing"), any(), eq(720), eq(1280));
    }

    @Test
    void landscapeCandidatesKeepConfiguredSizeAndLegacyPrompt() {
        service.generateImageCandidates(7L, 3L);
        verify(gen, times(2)).generate(argThat(p -> !p.contains("vertical framing")), any(), eq(1280), eq(720));
    }
}