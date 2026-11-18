package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 13（spec §5.1/§5.2）：system prompt 的画面感升级，以及素材清单的注入位置与零注入语义。
 * 三条判据各守一条不可回退的行为：清单夹在基础 prompt 与 templateGuidance 之间（顺序错了会盖掉模板风格）、
 * null/空白清单不留任何痕迹（空库用户不该看到「可用素材库」空标题）、两参重载仍然可用（存量调用点零改动）。
 */
class VideoScriptServicePromptTest {

    private static final String MINIMAL_JSON = "{\"title\":\"t\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";

    /**
     * 分层 mock（非 RETURNS_DEEP_STUBS）：深桩链中间层的 stub 调用会计进 verify，本类的三条判据全都 verify(spec)
     * ——AssetMatchServiceTest 同一坑（Task 4 审查记过一次），计划里的深桩写法跑不起来。
     */
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

    @Test
    void catalogInjectedBetweenBaseAndGuidance() {
        ModelService ms = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec = stubClient(ms, MINIMAL_JSON);
        new VideoScriptService(ms, mock(ImageService.class))
                .generateScript("创意", "模板引导XYZ", "[人物] 林晚：黑长直,红大衣\n");
        String sys = capturedSystemPrompt(spec);
        assertTrue(sys.contains("可用素材库"));
        assertTrue(sys.contains("[人物] 林晚：黑长直,红大衣"));
        assertTrue(sys.indexOf("可用素材库") < sys.indexOf("模板引导XYZ")); // 拼接顺序：基础+清单+guidance
        assertTrue(sys.contains("光影"), "画面感四要素");
        assertTrue(sys.contains("beautiful scene"), "反空泛禁令");
        // spec §5.1 剩余升级项与 §5.2 行为契约：不钉住的话，删文本/换措辞全绿（审查 round 1 Important-1）
        assertTrue(sys.contains("round(2.5 × durationSeconds)"), "旁白字数上限（BLG 教训固化）");
        assertTrue(sys.contains("subject + action + environment details"), "imagePrompt 固化公式");
        assertTrue(sys.contains("lighting + color palette + composition + art style"), "公式后四元（只钉前三元时删后半不会红，复审 round 2 项 1）");
        assertTrue(sys.contains("环境细节至少 2 个具体物件"), "公式里的最低环境细节数");
        assertTrue(sys.contains("范例镜") && sys.contains("anime coloring"), "高质量标杆镜");
        assertTrue(sys.contains("无背景纯色镜头") && sys.contains("服装状态保持一致"), "纯色镜/服装一致禁令");
        assertTrue(sys.contains("用户已备参考图"), "清单标题限定语（非图片不得进库）");
        assertTrue(sys.contains("优先用库内人物当主角"), "§5.2 指令句：清单不只是信息，是行为契约");
    }

    @Test
    void nullCatalogInjectsNothing() {
        ModelService ms = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec = stubClient(ms, MINIMAL_JSON);
        new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null, null);
        assertFalse(capturedSystemPrompt(spec).contains("可用素材库"));
    }

    /** 空串清单与 null 同义：库刚清空时不得留一个没有内容的「可用素材库」标题 */
    @Test
    void blankCatalogInjectsNothing() {
        ModelService ms = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec = stubClient(ms, MINIMAL_JSON);
        new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null, "   \n");
        assertFalse(capturedSystemPrompt(spec).contains("可用素材库"));
    }

    @Test
    void twoArgOverloadStillWorks() {
        ModelService ms = mock(ModelService.class);
        stubClient(ms, MINIMAL_JSON);
        assertNotNull(new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null));
    }

    /** 两参与三参（catalog=null）必须给出逐字相同的 prompt，否则「委托」只是形式上存在 */
    @Test
    void twoArgAndExplicitNullCatalogProduceIdenticalPrompt() {
        ModelService ms1 = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec1 = stubClient(ms1, MINIMAL_JSON);
        new VideoScriptService(ms1, mock(ImageService.class)).generateScript("创意", "GUIDE");

        ModelService ms2 = mock(ModelService.class);
        ChatClient.ChatClientRequestSpec spec2 = stubClient(ms2, MINIMAL_JSON);
        new VideoScriptService(ms2, mock(ImageService.class)).generateScript("创意", "GUIDE", null);

        assertEquals(capturedSystemPrompt(spec1), capturedSystemPrompt(spec2));
    }
}
