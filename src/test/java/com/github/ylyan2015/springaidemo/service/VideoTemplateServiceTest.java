package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoTemplate;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 视频模板服务单元测试（模块 H）
 * 覆盖：系统模板预置幂等、归属校验、系统模板禁删、模板引导注入、自定义模板保存
 */
class VideoTemplateServiceTest {

    private VideoTemplateRepository templateRepository;
    private StoryScriptService storyScriptService;
    private VideoTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = Mockito.mock(VideoTemplateRepository.class);
        storyScriptService = Mockito.mock(StoryScriptService.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        service = new VideoTemplateService(templateRepository, userRepository, storyScriptService);

        User user = Mockito.mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private VideoTemplate template(long id, boolean system, Long userId) {
        VideoTemplate t = new VideoTemplate();
        t.setId(id);
        t.setName("模板" + id);
        t.setCategory("product");
        t.setShotCount(5);
        t.setStylePrefix("cinematic product showcase");
        t.setIsSystem(system);
        t.setUserId(userId);
        return t;
    }

    // ==================== 系统模板预置 ====================

    @Test
    @SuppressWarnings("unchecked")
    void seedInsertsEightSystemTemplatesWhenEmpty() {
        when(templateRepository.findByIsSystemTrue()).thenReturn(List.of());
        service.seedSystemTemplates();
        ArgumentCaptor<List<VideoTemplate>> captor = ArgumentCaptor.forClass(List.class);
        verify(templateRepository).saveAll(captor.capture());
        List<VideoTemplate> seeded = captor.getValue();
        assertEquals(8, seeded.size());
        assertTrue(seeded.stream().allMatch(t -> Boolean.TRUE.equals(t.getIsSystem())));
        assertTrue(seeded.stream().allMatch(t -> t.getStylePrefix() != null && !t.getStylePrefix().isBlank()));
        assertTrue(seeded.stream().allMatch(t -> t.getShotCount() != null && t.getShotCount() > 0));
    }

    @Test
    void seedIsIdempotentWhenSystemTemplatesExist() {
        when(templateRepository.findByIsSystemTrue()).thenReturn(List.of(template(1L, true, null)));
        service.seedSystemTemplates();
        verify(templateRepository, never()).saveAll(anyList());
    }

    // ==================== 归属校验 ====================

    @Test
    void getTemplateAllowsSystemTemplate() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(1L, true, null)));
        assertEquals(1L, service.getTemplate(1L).getId());
    }

    @Test
    void getTemplateAllowsOwnCustomTemplate() {
        when(templateRepository.findById(2L)).thenReturn(Optional.of(template(2L, false, 1L)));
        assertEquals(2L, service.getTemplate(2L).getId());
    }

    @Test
    void getTemplateRejectsOthersCustomTemplate() {
        when(templateRepository.findById(3L)).thenReturn(Optional.of(template(3L, false, 99L)));
        assertThrows(RuntimeException.class, () -> service.getTemplate(3L));
    }

    // ==================== 删除 ====================

    @Test
    void deleteRejectsSystemTemplate() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(1L, true, null)));
        assertThrows(RuntimeException.class, () -> service.deleteTemplate(1L));
        verify(templateRepository, never()).delete(Mockito.any(VideoTemplate.class));
    }

    @Test
    void deleteAllowsOwnCustomTemplate() {
        VideoTemplate t = template(2L, false, 1L);
        when(templateRepository.findById(2L)).thenReturn(Optional.of(t));
        service.deleteTemplate(2L);
        verify(templateRepository).delete(t);
    }

    // ==================== 模板应用与引导 ====================

    @Test
    void buildGuidanceContainsStylePrefixAndShotCount() {
        String guidance = VideoTemplateService.buildGuidance(template(1L, true, null));
        assertTrue(guidance.contains("cinematic product showcase"));
        assertTrue(guidance.contains("5"));
    }

    @Test
    void applyTemplateDelegatesToScriptGenerationWithGuidance() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(1L, true, null)));
        StoryScript script = new StoryScript();
        when(storyScriptService.generateAndSaveScript(eq("一个猫咪短片"), anyString())).thenReturn(script);

        StoryScript result = service.applyTemplate(1L, "一个猫咪短片");
        assertSame(script, result);
        verify(storyScriptService).generateAndSaveScript(eq("一个猫咪短片"),
                argThat(g -> g.contains("cinematic product showcase")));
    }

    @Test
    void applyTemplateRejectsBlankIdea() {
        assertThrows(RuntimeException.class, () -> service.applyTemplate(1L, "  "));
    }

    // ==================== 自定义模板 ====================

    @Test
    void saveCustomTemplateSetsOwnerAndNonSystem() {
        when(templateRepository.save(Mockito.any(VideoTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var result = service.saveCustomTemplate("我的模板", "story", "描述", 6,
                "warm cinematic style", "calm");
        assertEquals("我的模板", result.get("name"));
        assertEquals(false, result.get("isSystem"));
        ArgumentCaptor<VideoTemplate> captor = ArgumentCaptor.forClass(VideoTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals(false, captor.getValue().getIsSystem());
        assertEquals(6, captor.getValue().getShotCount());
    }

    @Test
    void saveCustomTemplateRejectsBlankName() {
        assertThrows(RuntimeException.class,
                () -> service.saveCustomTemplate("", "story", null, 5, "style", null));
    }
}