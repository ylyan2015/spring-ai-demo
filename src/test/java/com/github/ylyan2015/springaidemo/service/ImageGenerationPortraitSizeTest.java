package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-3 竖屏优先：9:16 脚本生图尺寸切 720x1280（单镜/尾卡装配点贯穿），
 * 提示词经四参 assemble 注入竖构图词；横屏/null 沿用 default-size（向后兼容契约）。
 */
class ImageGenerationPortraitSizeTest {

    private ImageGenerator gen;
    private EndCardImageRenderer endCardRenderer;
    private StoryScript script;
    private StoryShot shot;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolveAll(any(), any())).thenReturn(List.of());
        endCardRenderer = mock(EndCardImageRenderer.class);
        service = new ImageGenerationService(factory, scriptRepo, shotRepo, userRepo,
                new ObjectMapper(), quality, new ShotPromptAssembler(), resolver, new LoraResolver(false),
                endCardRenderer);
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
        script.setStyle("ANIME");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
        shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImagePrompt("base");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "mock", 64, 64));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== effectiveSize 静态助手 ====================

    @Test
    void portraitOverridesConfiguredDefault() {
        assertArrayEquals(new int[]{720, 1280}, ImageGenerationService.effectiveSize("9:16", "1280x720"));
    }

    @Test
    void landscapeKeepsConfiguredDefault() {
        assertArrayEquals(new int[]{1280, 720}, ImageGenerationService.effectiveSize("16:9", "1280x720"));
    }

    @Test
    void nullAspectParsesCustomDefault() {
        assertArrayEquals(new int[]{1024, 1024}, ImageGenerationService.effectiveSize(null, "1024x1024"));
    }

    @Test
    void portraitWinsEvenWhenDefaultIsBogus() {
        assertArrayEquals(new int[]{720, 1280}, ImageGenerationService.effectiveSize("9:16", "bogus"));
    }

    @Test
    void bogusDefaultFallsBackTo1280x720() {
        assertArrayEquals(new int[]{1280, 720}, ImageGenerationService.effectiveSize(null, "bogus"));
        assertArrayEquals(new int[]{1280, 720}, ImageGenerationService.effectiveSize(null, null));
    }

    // ==================== 单镜生成接线 ====================

    @Test
    void portraitShotGeneratesAt720x1280WithVerticalPrompt() {
        script.setAspectRatio("9:16");
        service.generateForShot(7L, 3L, null);
        verify(gen).generate(contains("vertical framing"), any(), eq(720), eq(1280));
    }

    @Test
    void landscapeShotKeepsConfiguredSizeAndLegacyPrompt() {
        service.generateForShot(7L, 3L, null);
        verify(gen).generate(argThat(p -> !p.contains("vertical framing")), any(), eq(1280), eq(720));
    }

    // ==================== 尾卡接线 ====================

    @Test
    void portraitEndCardRendersAt720x1280() {
        script.setAspectRatio("9:16");
        shot.setEndCard(true);
        shot.setNarration("关注看下一集");
        when(endCardRenderer.render(anyString(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/card.png", "fx", "local", 64, 64));

        service.generateForShot(7L, 3L, null);

        verify(endCardRenderer).render(eq("关注看下一集"), eq(720), eq(1280));
        verify(gen, never()).generate(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void landscapeEndCardKeepsConfiguredSize() {
        shot.setEndCard(true);
        shot.setNarration("关注看下一集");
        when(endCardRenderer.render(anyString(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/card.png", "fx", "local", 64, 64));

        service.generateForShot(7L, 3L, null);

        verify(endCardRenderer).render(anyString(), eq(1280), eq(720));
    }
}