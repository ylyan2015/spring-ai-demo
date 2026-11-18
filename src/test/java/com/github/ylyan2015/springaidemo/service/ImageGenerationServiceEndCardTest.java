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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 尾卡镜生图旁路单测：endCard=true 的镜头不走生图 provider，
 * 由 EndCardImageRenderer 本地渲染纯色底+压字 PNG（零 GPU/零云）
 */
class ImageGenerationServiceEndCardTest {

    private ImageGeneratorFactory generatorFactory;
    private StoryScriptRepository scriptRepository;
    private StoryShotRepository shotRepository;
    private UserRepository userRepository;
    private ImageGenerator generator;
    private EndCardImageRenderer endCardRenderer;
    private ImageGenerationService service;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        generatorFactory = mock(ImageGeneratorFactory.class);
        scriptRepository = mock(StoryScriptRepository.class);
        shotRepository = mock(StoryShotRepository.class);
        userRepository = mock(UserRepository.class);
        generator = mock(ImageGenerator.class);
        endCardRenderer = mock(EndCardImageRenderer.class);

        service = new ImageGenerationService(generatorFactory, scriptRepository, shotRepository,
                userRepository, new ObjectMapper(), mock(ImageQualityService.class),
                new ShotPromptAssembler(),
                new ReferenceImageResolver(mock(com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository.class),
                        mock(com.github.ylyan2015.springaidemo.storage.StorageService.class),
                        mock(com.github.ylyan2015.springaidemo.repository.MediaAssetRepository.class),
                        mock(com.github.ylyan2015.springaidemo.repository.StoryScriptRepository.class)),
                new LoraResolver(false), endCardRenderer);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));

        StoryScript script = new StoryScript();
        script.setId(1L);
        when(scriptRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(script));
        when(scriptRepository.save(any(StoryScript.class))).thenAnswer(inv -> inv.getArgument(0));

        shot = new StoryShot();
        shot.setId(2L);
        shot.setShotOrder(9);
        shot.setNarration("那座山，我们翻过去了");
        shot.setImagePrompt("black end card, no text");
        shot.setScript(script);
        when(shotRepository.findByIdAndScriptId(2L, 1L)).thenReturn(Optional.of(shot));
        when(shotRepository.save(any(StoryShot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void endCardShotSkipsGeneratorAndUsesRenderer() {
        shot.setEndCard(true);
        when(endCardRenderer.render("那座山，我们翻过去了", 1280, 720))
                .thenReturn(new GeneratedImage("/images/end.png", "fe", "endcard", 1280, 720));

        StoryShot result = service.generateForShot(1L, 2L, null);

        verifyNoInteractions(generatorFactory);
        assertEquals("READY", result.getImageStatus());
        assertEquals("/images/end.png", result.getImageUrl());
        assertEquals("fe", result.getImageFileId());
        assertNull(result.getQualityScore(), "纯色卡不进质量评估");
    }

    @Test
    void endCardWithoutNarrationFallsBackToSceneDescription() {
        shot.setEndCard(true);
        shot.setNarration(null);
        shot.setSceneDescription("尾卡主句压字");
        when(endCardRenderer.render("尾卡主句压字", 1280, 720))
                .thenReturn(new GeneratedImage("/images/end.png", "fe", "endcard", 1280, 720));

        StoryShot result = service.generateForShot(1L, 2L, null);

        assertEquals("READY", result.getImageStatus());
        verify(endCardRenderer).render("尾卡主句压字", 1280, 720);
    }

    @Test
    void endCardRenderFailureMarksShotFailed() {
        shot.setEndCard(true);
        when(endCardRenderer.render(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("FFmpeg 渲染失败"));

        try {
            service.generateForShot(1L, 2L, null);
        } catch (RuntimeException expected) {
            // 预期抛出
        }
        assertEquals("FAILED", shot.getImageStatus());
    }

    @Test
    void normalShotStillUsesGenerator() {
        shot.setEndCard(null);
        when(generatorFactory.getActiveGenerator()).thenReturn(generator);
        when(generator.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("http://img/1.png", "f1", "comfyui", 1280, 720));

        StoryShot result = service.generateForShot(1L, 2L, null);

        assertEquals("READY", result.getImageStatus());
        verify(generator).generate(anyString(), any(), anyInt(), anyInt());
        verify(endCardRenderer, never()).render(anyString(), anyInt(), anyInt());
    }
}
