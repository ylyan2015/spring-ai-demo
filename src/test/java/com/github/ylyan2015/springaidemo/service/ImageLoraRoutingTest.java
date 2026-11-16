package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5 切片 3：LoRA 路由（supportsLora=true 且角色带 LoRA → generateWithLora；否则存量路径逐字不变）
 */
class ImageLoraRoutingTest {

    private ImageGenerator gen;
    private ReferenceImageResolver resolver;
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
        resolver = mock(ReferenceImageResolver.class);
        when(resolver.resolve(any())).thenReturn(List.of());
        service = new ImageGenerationService(factory, scriptRepo, shotRepo, userRepo,
                new ObjectMapper(), quality, new ShotPromptAssembler(), resolver, new LoraResolver(false));

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript();
        s.setId(7L);
        s.setUserId(1L);
        s.setStyle("ANIME");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setImagePrompt("base");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private StoryCharacter loraCharacter() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        c.setName("saki");
        c.setFeatureTable("brown hair");
        c.setLoraName("saki_chan_lora.safetensors");
        c.setTriggerWord("saki_chan");
        c.setReferenceImageUrl("http://ref/1.png");
        return c;
    }

    @Test
    void loraSupportedRoutesToGenerateWithLora() {
        when(resolver.charactersForShot(any())).thenReturn(List.of(loraCharacter()));
        when(gen.supportsLora()).thenReturn(true);
        when(gen.generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "comfyui", 1280, 704));

        service.generateForShot(7L, 3L, null);

        // 触发词经 assembler 注入 prompt；controlnet 关闭 cnRef=null
        verify(gen).generateWithLora(contains("saki_chan"), any(), anyInt(), anyInt(),
                eq("saki_chan_lora.safetensors"), isNull());
        verify(gen, never()).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList());
    }

    @Test
    void loraUnsupportedFallsBackToLegacyGenerate() {
        when(resolver.charactersForShot(any())).thenReturn(List.of(loraCharacter()));
        when(gen.supportsLora()).thenReturn(false);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "dashscope", 1280, 720));

        service.generateForShot(7L, 3L, null);

        verify(gen).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void noLoraCharacterUsesLegacyEvenIfSupported() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        c.setName("plain");
        c.setFeatureTable("black hair");
        when(resolver.charactersForShot(any())).thenReturn(List.of(c));
        when(gen.supportsLora()).thenReturn(true);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "comfyui", 1280, 704));

        service.generateForShot(7L, 3L, null);

        verify(gen).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any());
    }
}