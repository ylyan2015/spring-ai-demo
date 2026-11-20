package com.github.ylyan2015.springaidemo.video.subtitle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
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

    @Test
    void generateAssOnEffectiveDurationsShiftsLaterCues() {
        // 声明 5/5/5，第 2 镜被旁白撑到 8s：转场起点 4.2s / 11.4s，后段字幕须整体后移
        List<StoryShot> shots = List.of(shot("一", 5), shot("二", 5), shot("三", 5));
        String declared = service.generateAss(shots, new SubtitleStyle(), "zh");
        String effective = service.generateAss(shots, new SubtitleStyle(), "zh", List.of(5, 8, 5));

        assertTrue(declared.contains("Dialogue: 0,0:00:08.80,0:00:13.40"));
        assertTrue(effective.contains("Dialogue: 0,0:00:04.60,0:00:11.80"));
        assertTrue(effective.contains("Dialogue: 0,0:00:11.80,0:00:16.40"));
        verifyNoInteractions(modelService);
    }

    @Test
    void generateAssFallsBackToDeclaredWhenSizeMismatch() {
        List<StoryShot> shots = List.of(shot("一", 5), shot("二", 5));
        assertEquals(service.generateAss(shots, new SubtitleStyle(), "zh"),
                service.generateAss(shots, new SubtitleStyle(), "zh", List.of(5)));
        assertEquals(service.generateAss(shots, new SubtitleStyle(), "zh"),
                service.generateAss(shots, new SubtitleStyle(), "zh", null));
    }

    @Test
    void generateAssOnEqualDurationsMatchesDeclaredTimeline() {
        List<StoryShot> shots = List.of(shot("一", 5), shot("二", 5), shot("三", 5));
        assertEquals(service.generateAss(shots, new SubtitleStyle(), "zh"),
                service.generateAss(shots, new SubtitleStyle(), "zh", List.of(5, 5, 5)));
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

    // ==================== 长行预折行（R10：libass 不折无空格 CJK） ====================

    /** 全角字宽=fontSize 的独立估宽（不复用生产 charWidth，避免恒真断言） */
    private static int fullWidth(String line, int fontSize) {
        int w = 0;
        for (char c : line.toCharArray()) {
            w += (c >= 0x2E80 || c == 0x2014 || c == 0x2015 || c == 0x2026) ? fontSize : (int) Math.ceil(fontSize * 0.6);
        }
        return w;
    }

    @Test
    void longCjkCueWrapsIntoLinesWithinPlayResWidth() {
        String narration = "为什么它比劝你别碰杠杆的宣传片管用？因为它不讲道理，它让你听见心跳。"
                + "收盘后段子比行情多：去年就亏完了，所以今年毫无损失。笑完你发现，你在重播自己。";
        String ass = service.generateAss(List.of(shot(narration, 16)), new SubtitleStyle(), "zh");
        String text = ass.substring(ass.lastIndexOf(",,") + 2).trim();
        String[] lines = text.split("\\\\N");
        assertTrue(lines.length >= 2, "超宽单行须预折行: " + text);
        for (String line : lines) {
            assertTrue(fullWidth(line, 24) <= 1200, "折行后仍超宽: " + line);
        }
        assertEquals(narration, String.join("", lines), "折行不得丢字");
    }

    @Test
    void longestShotNarrationStaysWithinTwoLines() {
        // S03 全片最长旁白（≈103全角字）：超两行宽须时域拆段，每段≤2行（三行会侵入卡片出处行区 y≥951）
        String narration = "第8话的标题叫，金融宽松是狂乱派对。可画面里没有派对，只有一根柱子。"
                + "2014年10月31日13点44分，日本银行宣布追加金融宽松，美元兑日元拔起一根直上直下的白柱。"
                + "漫画里这个日期、这个分钟，是白纸黑字画出来的。";
        String ass = service.generateAss(List.of(shot(narration, 21)), new SubtitleStyle(), "zh");
        List<String> texts = new ArrayList<>();
        for (String line : ass.split("\n")) {
            if (line.startsWith("Dialogue:")) {
                texts.add(line.substring(line.lastIndexOf(",,") + 2).trim());
            }
        }
        assertTrue(texts.size() >= 2, "超两行宽旁白须时域拆段: " + ass);
        StringBuilder joined = new StringBuilder();
        for (String t : texts) {
            String[] ls = t.split("\\\\N");
            assertTrue(ls.length <= 2, "拆段后仍超2行: " + t);
            for (String l : ls) {
                assertTrue(fullWidth(l, 24) <= 1200, "折行后仍超宽: " + l);
            }
            joined.append(String.join("", ls));
        }
        assertEquals(narration, joined.toString(), "拆段+折行不得丢字");
    }

    @Test
    void shortCueNotWrapped() {
        String narration = "11月19日深夜，它还是红的，涨2.64%。20日收盘，跌9.71%。同一只股票，一夜之间。";
        String ass = service.generateAss(List.of(shot(narration, 10)), new SubtitleStyle(), "zh");
        assertFalse(ass.contains("\\N"), "未超宽不应插硬换行: " + ass);
    }

    @Test
    void wrapKeepsExistingBilingualLineBreak() {
        mockTranslation("[\"Hello world\"]");
        String ass = service.generateAss(List.of(shot("你好世界", 5)), new SubtitleStyle(), "bilingual");
        assertTrue(ass.contains("你好世界\\NHello world"));
    }
}