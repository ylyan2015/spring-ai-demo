package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成-评价-生成自我精修循环：首轮分镜后经「四人评审团」（导演/编剧/画师/乐师单 prompt 串联）
 * 质疑 → 原硬约束下重修，默认 2 轮（refine-rounds=0 可关）。
 * 优雅降级红线：评审或重修任一失败（含返回不可解析 JSON / 空白意见）→ 保留当前最优稿，绝不因精修拖垮生成。
 * 注：refineRounds 字段无 Spring 上下文时默认 0，故存量 Hook/Prompt 测试不受循环影响。
 */
class VideoScriptServiceRefineTest {

    private static final String DRAFT = "{\"title\":\"draft\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";
    private static final String R1 = "{\"title\":\"r1\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";
    private static final String R2 = "{\"title\":\"r2final\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";
    private static final String CRITIQUE = "[导演] 镜1：开场无钩子 → 首镜改为危机现场";

    /** 分层 mock（非深桩）：spec 用于捕获 system prompt，callSpec 用于 content 计数/抛错 */
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;

    private ModelService stubClient(String... responses) {
        ModelService ms = mock(ModelService.class);
        ChatClient client = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(ms.getScriptChatClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(responses[0],
                List.of(responses).subList(1, responses.length).toArray(new String[0]));
        return ms;
    }

    /** 前 okCalls 次 content 返回 DRAFT，第 okCalls+1 次抛错（降级路径专用） */
    private ModelService stubClientThrowingAfter(int okCalls) {
        ModelService ms = mock(ModelService.class);
        ChatClient client = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(ms.getScriptChatClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        org.mockito.stubbing.OngoingStubbing<String> stub = when(callSpec.content());
        for (int i = 0; i < okCalls; i++) {
            stub = stub.thenReturn(DRAFT);
        }
        stub.thenThrow(new RuntimeException("网络抖动"));
        return ms;
    }

    private VideoScriptService withRounds(ModelService ms, int rounds) {
        VideoScriptService s = new VideoScriptService(ms, mock(ImageService.class));
        ReflectionTestUtils.setField(s, "refineRounds", rounds);
        return s;
    }

    private List<String> capturedSystems(int expectedCalls) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(spec, times(expectedCalls)).system(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void twoRoundsMakeFiveCallsAndKeepFinalRefinedDraft() {
        Storyboard out = withRounds(stubClient(DRAFT, CRITIQUE, R1, CRITIQUE, R2), 2)
                .generateScript("创意", null, null);
        assertEquals("r2final", out.getTitle());
        verify(callSpec, times(5)).content();
    }

    @Test
    void criticTurnCarriesFourProfessionsAndQuestionOnlyContract() {
        withRounds(stubClient(DRAFT, CRITIQUE, R1, CRITIQUE, R2), 2).generateScript("创意", null, null);
        List<String> systems = capturedSystems(5);
        for (int criticIdx : new int[]{1, 3}) {
            String sys = systems.get(criticIdx);
            assertTrue(sys.contains("导演") && sys.contains("编剧") && sys.contains("画师") && sys.contains("乐师"),
                    "评审团四视角缺一即失去质疑维度: " + sys);
            assertTrue(sys.contains("只质疑") || sys.contains("不改写"), "评审不得顺手重写脚本");
            assertTrue(sys.contains("钩子") && sys.contains("台词密度"), "质疑维度必须对照既有硬约束");
        }
    }

    @Test
    void refineTurnReusesOriginalHardConstraintSystemPrompt() {
        withRounds(stubClient(DRAFT, CRITIQUE, R1, CRITIQUE, R2), 2).generateScript("创意", null, null);
        List<String> systems = capturedSystems(5);
        String gen = systems.get(0);
        assertTrue(gen.contains("钩子叙事硬约束"));
        assertEquals(gen, systems.get(2), "重修必须原样携带首轮硬约束 system（guidance/清单语义不丢）");
        assertEquals(gen, systems.get(4));
    }

    @Test
    void critiqueCallFailureDegradesToFirstDraftWithoutException() {
        Storyboard out = withRounds(stubClientThrowingAfter(1), 2).generateScript("创意", null, null);
        assertEquals("draft", out.getTitle(), "评审失败必须降级保留首轮稿，不向上抛");
        capturedSystems(2);
    }

    @Test
    void unparseableRefineOutputKeepsPreviousRoundAndStops() {
        Storyboard out = withRounds(stubClient(DRAFT, CRITIQUE, "这不是JSON"), 2)
                .generateScript("创意", null, null);
        assertEquals("draft", out.getTitle(), "重修产物解析失败 → 保留上一稿");
        capturedSystems(3); // 失败即止损：第 4 次调用（第 2 轮评审）不得发生
    }

    @Test
    void blankCritiqueStopsRefine() {
        Storyboard out = withRounds(stubClient(DRAFT, "   "), 2).generateScript("创意", null, null);
        assertEquals("draft", out.getTitle());
        capturedSystems(2);
    }

    @Test
    void roundsZeroDoesSingleCallOnly() {
        Storyboard out = withRounds(stubClient(DRAFT), 0).generateScript("创意", null, null);
        assertEquals("draft", out.getTitle());
        capturedSystems(1);
    }

    @Test
    void roundsOneDoesExactlyThreeCalls() {
        Storyboard out = withRounds(stubClient(DRAFT, CRITIQUE, R1), 1).generateScript("创意", null, null);
        assertEquals("r1", out.getTitle());
        capturedSystems(3);
    }
}
