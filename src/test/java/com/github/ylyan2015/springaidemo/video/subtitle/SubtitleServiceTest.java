package com.github.ylyan2015.springaidemo.video.subtitle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 字幕服务单元测试（模块 F：时间轴对齐 + 多语言字幕）
 */
class SubtitleServiceTest {

    private ModelService modelService;
    private ChatClient chatClient;
    private SubtitleService service;

    @BeforeEach
    void setUp() {
        modelService = Mockito.mock(ModelService.class);
        chatClient = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(chatClient);
        service = new SubtitleService(modelService, new ObjectMapper());
    }

    private StoryShot shot(String narration, int duration) {
        StoryShot s = new StoryShot();
        s.setNarration(narration);
        s.setDurationSeconds(duration);
        return s;
    }

    private void mockTranslation(String jsonArray) {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(jsonArray);
    }

    // ==================== 时间轴（xfade 对齐） ====================

    @Test
    void buildCuesAlignsToXfadeMidpoints() {
        // 3 镜头 × 5s，xfade 0.8s：实际时长 13.4s；转场起点 4.2s / 8.4s，字幕在中点切换
        List<SubtitleCue> cues = service.buildCues(
                List.of("一", "二", "三"), List.of(5, 5, 5), 0.8);
        assertEquals(3, cues.size());
        assertEquals(0, cues.get(0).startMs());
        assertEquals(4600, cues.get(0).endMs());
        assertEquals(4600, cues.get(1).startMs());
        assertEquals(8800, cues.get(1).endMs());
        assertEquals(8800, cues.get(2).startMs());
        assertEquals(13400, cues.get(2).endMs());
    }

    @Test
    void buildCuesSkipsBlankNarrationButKeepsTimeline() {
        List<SubtitleCue> cues = service.buildCues(
                List.of("一", "", "三"), List.of(5, 5, 5), 0.8);
        assertEquals(2, cues.size());
        assertEquals(8800, cues.get(1).startMs());
    }

    // ==================== 多语言 ====================

    @Test
    void generateSrtDefaultsToOriginalChinese() {
        String srt = service.generateSrt(List.of(shot("你好世界", 5)));
        assertTrue(srt.contains("你好世界"));
        verifyNoInteractions(modelService);
    }

    @Test
    void generateSrtEnglishUsesLlmTranslation() {
        mockTranslation("[\"Hello world\",\"Second line\"]");
        String srt = service.generateSrt(List.of(shot("你好世界", 5), shot("第二句", 5)), "en");
        assertTrue(srt.contains("Hello world"));
        assertTrue(srt.contains("Second line"));
        assertFalse(srt.contains("你好世界"));
    }

    @Test
    void generateSrtBilingualContainsBothLines() {
        mockTranslation("[\"Hello world\"]");
        String srt = service.generateSrt(List.of(shot("你好世界", 5)), "bilingual");
        assertTrue(srt.contains("你好世界"));
        assertTrue(srt.contains("Hello world"));
        assertTrue(srt.contains("你好世界\nHello world"));
    }

    @Test
    void generateAssBilingualUsesAssLineBreak() {
        mockTranslation("[\"Hello world\"]");
        String ass = service.generateAss(List.of(shot("你好世界", 5)), new SubtitleStyle(), "bilingual");
        assertTrue(ass.contains("你好世界\\NHello world"));
    }

    @Test
    void invalidLanguageFallsBackToChinese() {
        String srt = service.generateSrt(List.of(shot("你好世界", 5)), "klingon");
        assertTrue(srt.contains("你好世界"));
        verifyNoInteractions(modelService);
    }

    @Test
    void translationFailureFallsBackToOriginal() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("LLM unavailable"));
        String srt = service.generateSrt(List.of(shot("你好世界", 5)), "en");
        assertTrue(srt.contains("你好世界"));
    }

    @Test
    void translationResultIsCachedAcrossCalls() {
        mockTranslation("[\"Hello world\"]");
        service.generateSrt(List.of(shot("你好世界", 5)), "en");
        service.generateSrt(List.of(shot("你好世界", 5)), "en");
        verify(modelService, times(1)).getChatClient();
    }
}