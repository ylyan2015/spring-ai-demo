package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 专家 Agent 工厂单元测试（Phase 3 切片3）
 */
class SpecialistAgentFactoryTest {

    private ModelService modelService;
    private VideoAgentToolFactory toolFactory;
    private ChatClient baseClient;
    private ChatClient specialistClient;
    private SpecialistAgentFactory factory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        toolFactory = mock(VideoAgentToolFactory.class);
        baseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        specialistClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(baseClient);
        ChatClient.Builder builder = baseClient.mutate();
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultToolCallbacks(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(specialistClient);
        ChatClient.ChatClientRequestSpec spec = specialistClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call().content()).thenReturn("专家完成");
        factory = new SpecialistAgentFactory(modelService, toolFactory, objectMapper);
    }

    private ToolCallback rawTool(String name) {
        return FunctionToolCallback.builder(name, (Map<String, Object> in) -> Map.of("success", true))
                .description(name).inputType(Map.class).build();
    }

    private ToolCallback delegate(List<ToolCallback> tools, String name) {
        when(toolFactory.createTools()).thenReturn(tools);
        return factory.createDelegateTools().stream()
                .filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    /** 创建并触发委托调用（专家装配发生在 call 时） */
    private void invokeDelegate(List<ToolCallback> tools, String name) {
        delegate(tools, name).call("{\"taskDescription\":\"测试任务\"}");
    }

    @Test
    void createsFourDelegateTools() {
        when(toolFactory.createTools()).thenReturn(List.of());
        List<ToolCallback> tools = factory.createDelegateTools();
        assertEquals(4, tools.size());
        assertEquals(List.of("delegateToScreenwriter", "delegateToArtist", "delegateToAudio", "delegateToEditor"),
                tools.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void screenwriterGetsScriptAndPromptToolsOnly() {
        invokeDelegate(List.of(rawTool("generateStoryboard"), rawTool("enhancePrompt"),
                rawTool("generateCharacters"), rawTool("generateImage"), rawTool("exportVideo")), "delegateToScreenwriter");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of("generateStoryboard", "enhancePrompt", "generateCharacters"),
                mounted.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void artistGetsImageToolsOnly() {
        invokeDelegate(List.of(rawTool("generateStoryboard"), rawTool("generateImage"),
                rawTool("evaluateImage"), rawTool("generateShotVideo"), rawTool("inpaintImage"),
                rawTool("exportVideo")), "delegateToArtist");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of("generateImage", "evaluateImage", "generateShotVideo", "inpaintImage"),
                mounted.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void audioGetsBgmToolOnly() {
        invokeDelegate(List.of(rawTool("recommendBgm"), rawTool("exportVideo")), "delegateToAudio");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of("recommendBgm"),
                mounted.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editorExportToolIsGated() {
        invokeDelegate(List.of(rawTool("exportVideo"), rawTool("generateImage")), "delegateToEditor");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(1, mounted.size());
        assertTrue(mounted.get(0) instanceof ConfirmationGateToolCallback);
    }

    @Test
    void delegateCallRunsSpecialistAndReturnsReply() throws Exception {
        ToolCallback tool = delegate(List.of(rawTool("recommendBgm")), "delegateToAudio");
        String out = tool.call("{\"taskDescription\":\"为猫咪视频选配乐\"}");
        JsonNode json = objectMapper.readTree(out);
        assertTrue(json.get("success").asBoolean());
        assertEquals("专家完成", json.get("reply").asText());
    }

    @Test
    void delegateFailureReturnsErrorJson() throws Exception {
        // 实现调用链为 messages().call().content()（无 toolContext），分段 stub 对齐
        ChatClient.ChatClientRequestSpec spec = specialistClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.call().content()).thenThrow(new RuntimeException("模型不可用"));
        ToolCallback tool = delegate(List.of(), "delegateToAudio");
        JsonNode json = objectMapper.readTree(tool.call("{\"taskDescription\":\"x\"}"));
        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("message").asText().contains("模型不可用"));
    }
}