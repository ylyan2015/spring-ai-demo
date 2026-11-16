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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切片 3 T2：N 候选顺序生成 + 评分 + provisional 最高分回写
 */
class CandidateGenerationServiceTest {

    private ImageGeneratorFactory factory;
    private ImageGenerator gen;
    private VideoGeneratorFactory videoFactory;
    private VideoGenerator videoGen;
    private ImageQualityService quality;
    private StoryScriptRepository scriptRepo;
    private StoryShotRepository shotRepo;
    private ShotCandidateRepository candRepo;
    private VideoGenerationService videoGenService;
    private ReferenceImageResolver resolver;
    private CandidateGenerationService service;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        factory = mock(ImageGeneratorFactory.class);
        gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        videoFactory = mock(VideoGeneratorFactory.class);
        videoGen = mock(VideoGenerator.class);
        quality = mock(ImageQualityService.class);
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        candRepo = mock(ShotCandidateRepository.class);
        resolver = mock(ReferenceImageResolver.class);
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolve(any())).thenReturn(List.of());
        videoGenService = mock(VideoGenerationService.class);
        service = new CandidateGenerationService(factory, videoFactory, scriptRepo, shotRepo, userRepo,
                candRepo, new ShotPromptAssembler(), resolver, quality, videoGenService, new LoraResolver(false));
        ReflectionTestUtils.setField(service, "imageCount", 3);
        ReflectionTestUtils.setField(service, "videoCount", 2);
        ReflectionTestUtils.setField(service, "resolution", "720P");

        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1); shot.setImagePrompt("p");
        shot.setDurationSeconds(5);
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private ImageQualityScore score(double v) {
        ImageQualityScore s = new ImageQualityScore();
        s.setOverallScore(v);
        return s;
    }

    @Test
    void threeImageCandidatesBestScoreBecomesProvisionalMain() {
        when(gen.supportsReferences()).thenReturn(true);
        when(resolver.resolve(any())).thenReturn(List.of("http://ref/1.png"));
        when(gen.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("u1", "f1", "wan27", 1280, 720),
                        new GeneratedImage("u2", "f2", "wan27", 1280, 720),
                        new GeneratedImage("u3", "f3", "wan27", 1280, 720));
        when(quality.isEnabled()).thenReturn(true);
        when(quality.evaluate(eq("u1"), anyString())).thenReturn(score(5.0));
        when(quality.evaluate(eq("u2"), anyString())).thenReturn(score(9.0));
        when(quality.evaluate(eq("u3"), anyString())).thenReturn(score(7.0));
        // 切片 2 M3 不变量：provisional 换图同步失效旧视频
        shot.setVideoUrl("/images/old.mp4");
        shot.setVideoStatus("READY");

        List<ShotCandidate> cands = service.generateImageCandidates(7L, 3L);

        assertEquals(3, cands.size());
        assertEquals("u2", shot.getImageUrl());
        assertEquals(9.0, shot.getQualityScore());
        assertEquals("READY", shot.getImageStatus());
        assertNull(shot.getVideoUrl());
        assertEquals("NONE", shot.getVideoStatus());
    }

    @Test
    void qualityDisabledProvisionalIsFirst() {
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("u1", "f1", "dashscope", 1280, 720),
                        new GeneratedImage("u2", "f2", "dashscope", 1280, 720),
                        new GeneratedImage("u3", "f3", "dashscope", 1280, 720));
        when(quality.isEnabled()).thenReturn(false);

        List<ShotCandidate> cands = service.generateImageCandidates(7L, 3L);

        assertEquals(3, cands.size());
        assertEquals("u1", shot.getImageUrl());
        assertNull(shot.getQualityScore());
    }

    @Test
    void partialFailureSkipsAndAllFailureThrows() {
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(new GeneratedImage("u2", "f2", "dashscope", 1280, 720))
                .thenThrow(new RuntimeException("boom"));
        List<ShotCandidate> cands = service.generateImageCandidates(7L, 3L);
        assertEquals(1, cands.size());
        assertEquals("u2", shot.getImageUrl());

        doThrow(new RuntimeException("boom")).when(gen).generate(anyString(), any(), anyInt(), anyInt());
        assertThrows(RuntimeException.class, () -> service.generateImageCandidates(7L, 3L));
    }

    @Test
    void videoCandidatesProvisionalDelegatesApply() {
        when(videoFactory.getActiveGenerator()).thenReturn(videoGen);
        when(videoGen.getType()).thenReturn("dashscope-wan");
        when(videoGenService.resolveMediaUrl(any(), any())).thenReturn("http://minio/a.png");
        when(videoGen.generate(any()))
                .thenReturn(new GeneratedVideo("/images/v1.mp4", "fv1", "dashscope-wan", 4),
                        new GeneratedVideo("/images/v2.mp4", "fv2", "dashscope-wan", 5));

        List<ShotCandidate> cands = service.generateVideoCandidates(7L, 3L);

        assertEquals(2, cands.size());
        assertEquals("VIDEO", cands.get(0).getType());
        verify(videoGenService).applyVideoCandidate(shot, "/images/v1.mp4", "fv1", 4);
    }

    @Test
    void videoCandidatesNoneGeneratorThrows() {
        when(videoFactory.getActiveGenerator()).thenReturn(new NoneVideoGenerator());
        assertThrows(RuntimeException.class, () -> service.generateVideoCandidates(7L, 3L));
    }
}