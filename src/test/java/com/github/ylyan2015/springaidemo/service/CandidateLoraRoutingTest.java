package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.generator.*;
import com.github.ylyan2015.springaidemo.video.quality.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5 切片 3：候选抽卡图像路径 LoRA 路由（controlnet 开启携带定妆参考图）
 */
class CandidateLoraRoutingTest {

    private ImageGenerator gen;
    private ReferenceImageResolver resolver;
    private CandidateGenerationService service;

    @BeforeEach
    void setUp() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        VideoGeneratorFactory videoFactory = mock(VideoGeneratorFactory.class);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ShotCandidateRepository candRepo = mock(ShotCandidateRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        resolver = mock(ReferenceImageResolver.class);
        when(resolver.resolve(any())).thenReturn(List.of());
        service = new CandidateGenerationService(factory, videoFactory, scriptRepo, shotRepo, userRepo,
                candRepo, new ShotPromptAssembler(), resolver, quality, mock(VideoGenerationService.class),
                new LoraResolver(true));
        ReflectionTestUtils.setField(service, "imageCount", 1);

        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1); shot.setImagePrompt("p");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void loraCandidateCarriesControlNetRefWhenEnabled() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        c.setName("saki");
        c.setLoraName("saki_chan_lora.safetensors");
        c.setTriggerWord("saki_chan");
        c.setReferenceImageUrl("http://ref/1.png");
        when(resolver.charactersForShot(any())).thenReturn(List.of(c));
        when(gen.supportsLora()).thenReturn(true);
        when(gen.generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(new GeneratedImage("u1", "f1", "comfyui", 1280, 704));

        service.generateImageCandidates(7L, 3L);

        verify(gen).generateWithLora(contains("saki_chan"), any(), eq(1280), eq(720),
                eq("saki_chan_lora.safetensors"), eq("http://ref/1.png"));
        verify(gen, never()).generate(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void noLoraCandidateUsesLegacyGenerate() {
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(gen.supportsLora()).thenReturn(true);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("u1", "f1", "comfyui", 1280, 704));

        service.generateImageCandidates(7L, 3L);

        verify(gen).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any());
    }
}