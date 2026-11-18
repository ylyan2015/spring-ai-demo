package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-2 钩子化脚本：system prompt 三条叙事硬约束（首镜 3 秒钩子 / 15-20 秒情绪转折 / 末镜扣子接尾卡），
 * 以及台词密度自动校验——镜头时长 ≥ (旁白+对白字数)/5.5 + 0.6s（YunyangNeural +15% 实测 ~5.5 字/秒）。
 * 校验只 warn 不阻断：LLM 输出不可强控，compose 的 TTS 预探测抬升是兜底。
 */
class VideoScriptServiceHookTest {

    private static final String MINIMAL_JSON = "{\"title\":\"t\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";

    /** 分层 mock（非深桩）：深桩中间层 stub 会计进 verify——VideoScriptServicePromptTest 同一坑 */
    private ChatClient.ChatClientRequestSpec stubClient(ModelService ms, String response) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(ms.getScriptChatClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);
        return spec;
    }

    private String capturedSystemPrompt(ChatClient.ChatClientRequestSpec spec) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(spec).system(cap.capture());
        return cap.getValue();
    }

    // ==================== prompt 钩子硬约束 ====================

    @Test
    void promptCarriesHookNarrativeConstraints() {
        ModelService ms = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec = stubClient(ms, MINIMAL_JSON);
        new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null, null);
        String sys = capturedSystemPrompt(spec);
        assertTrue(sys.contains("首镜") && sys.contains("3 秒") && sys.contains("冲突"),
                "首镜 3 秒内抛冲突/悬念的硬约束");
        assertTrue(sys.contains("15-20 秒") && sys.contains("情绪转折"), "中段节奏约束");
        assertTrue(sys.contains("扣子") && sys.contains("尾卡"), "末镜留扣子接尾卡 CTA");
    }

    @Test
    void hookConstraintsSurviveGuidanceAppend() {
        // 钩子约束在基础 prompt 内：templateGuidance 追加在后不得挤掉（顺序契约同素材清单）
        ModelService ms = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec = stubClient(ms, MINIMAL_JSON);
        new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", "模板引导XYZ", null);
        String sys = capturedSystemPrompt(spec);
        assertTrue(sys.contains("情绪转折"));
        assertTrue(sys.indexOf("情绪转折") < sys.indexOf("模板引导XYZ"));
    }

    // ==================== 台词密度算术 ====================

    private static Shot shot(int order, int duration, String narration, String dialogue) {
        Shot s = new Shot();
        s.setOrder(order);
        s.setDurationSeconds(duration);
        s.setNarration(narration);
        s.setDialogue(dialogue);
        return s;
    }

    @Test
    void densityFitsWhenDurationCoversChars() {
        // 20 字 → 20/5.5+0.6 ≈ 4.24s ≤ 5s，无违规
        assertTrue(VideoScriptService.densityViolations(
                List.of(shot(1, 5, "一二三四五六七八九十一二三四五六七八九十", ""))).isEmpty());
    }

    @Test
    void densityCountsNarrationPlusDialogue() {
        // 旁白 20 + 对白 10 = 30 字 → 需 6.05s > 5s；只算旁白会漏判（对白也占 TTS 时长）
        List<String> v = VideoScriptService.densityViolations(
                List.of(shot(1, 5, "一二三四五六七八九十一二三四五六七八九十", "一二三四五六七八九十")));
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("1"), "违规描述须带镜头序号: " + v.get(0));
        assertTrue(v.get(0).contains("30"), "违规描述须带总字数: " + v.get(0));
    }

    @Test
    void densityBoundaryExactFitPasses() {
        // 22 字 → 22/5.5+0.6 = 4.6s ≤ 5s 通过；28 字 → 5.69s > 5s 违规（钉住 5.5 与 0.6 两个常数）
        assertTrue(VideoScriptService.densityViolations(
                List.of(shot(1, 5, "一二三四五六七八九十一二三四五六七八九十一二", ""))).isEmpty());
        assertEquals(1, VideoScriptService.densityViolations(
                List.of(shot(1, 5, "一二三四五六七八九十一二三四五六七八九十一二三四五六七八", ""))).size());
    }

    @Test
    void densitySkipsEndCardShot() {
        // 尾卡本地压字不走 TTS，字数再多也不占旁白时长
        Shot end = shot(2, 3, "关注我看下集预告关注我看下集预告关注我看下集预告", "");
        end.setEndCard(true);
        assertTrue(VideoScriptService.densityViolations(List.of(end)).isEmpty());
    }

    @Test
    void densityHandlesNullAndBlankText() {
        assertTrue(VideoScriptService.densityViolations(List.of(shot(1, 5, null, null))).isEmpty());
        assertTrue(VideoScriptService.densityViolations(List.of(shot(1, 5, "  ", ""))).isEmpty());
        assertTrue(VideoScriptService.densityViolations(null).isEmpty());
        assertTrue(VideoScriptService.densityViolations(List.of()).isEmpty());
    }
}