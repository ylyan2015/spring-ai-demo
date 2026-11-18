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

class ImageGenerationServiceCharacterRefsTest {

    private ImageGeneratorFactory factory;
    private ImageGenerator gen;
    private ReferenceImageResolver resolver;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        resolver = mock(ReferenceImageResolver.class);
        service = new ImageGenerationService(factory, scriptRepo, shotRepo, userRepo,
                new ObjectMapper(), quality, new ShotPromptAssembler(), resolver, new LoraResolver(false),
                mock(com.github.ylyan2015.springaidemo.video.generator.EndCardImageRenderer.class));

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
        shot.setCharacterIds("11");
        shot.setImagePrompt("base");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesReferencesWhenSupported() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        c.setName("林晚");
        c.setFeatureTable("black hair");
        when(resolver.charactersForShot(any())).thenReturn(List.of(c));
        when(resolver.resolveAll(any(), any())).thenReturn(List.of("data:image/png;base64,AAAA"));
        when(gen.supportsReferences()).thenReturn(true);
        when(gen.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "wan27", 64, 64));

        service.generateForShot(7L, 3L, null);

        verify(resolver).resolveAll(any(), any());   // 接线锁定：S3 后生产走 resolveAll（stub 错方法即空转）
        verify(gen).generateWithReferences(contains("Character 林晚: black hair."), any(),
                anyInt(), anyInt(), eq(List.of("data:image/png;base64,AAAA")));
    }

    @Test
    void legacyGeneratorUsesPlainGenerate() {
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolveAll(any(), any())).thenReturn(List.of());
        when(gen.supportsReferences()).thenReturn(false);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("/images/x.png", "fx", "mock", 64, 64));

        service.generateForShot(7L, 3L, null);

        verify(gen).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList());
    }
}