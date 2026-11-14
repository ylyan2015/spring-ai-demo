package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 人工确认闸门单元测试（Phase 3 切片2）
 */
class ConfirmationGateToolCallbackTest {

    private ToolCallback delegate;
    private ConfirmationGateToolCallback gate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallback.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        when(delegate.getToolDefinition().name()).thenReturn("exportVideo");
        when(delegate.call(anyString(), any())).thenReturn("{\"success\":true}");
        gate = new ConfirmationGateToolCallback(delegate, "导出视频耗时较长，需用户确认", objectMapper);
    }

    private ToolContext ctx(List<String> confirmed, List<Map<String, Object>> pending) {
        Map<String, Object> map = new HashMap<>();
        map.put("confirmedTools", confirmed);
        map.put("pendingConfirmations", pending);
        return new ToolContext(map);
    }

    @Test
    void unconfirmedCallIsBlockedAndRecorded() throws Exception {
        List<Map<String, Object>> pending = new ArrayList<>();
        String out = gate.call("{\"scriptId\":1}", ctx(List.of(), pending));
        JsonNode json = objectMapper.readTree(out);
        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("requiresConfirmation").asBoolean());
        assertEquals("exportVideo", json.get("tool").asText());
        assertEquals(1, pending.size());
        assertEquals("exportVideo", pending.get(0).get("tool"));
        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void confirmedCallDelegates() {
        String out = gate.call("{\"scriptId\":1}", ctx(List.of("exportVideo"), new ArrayList<>()));
        assertEquals("{\"success\":true}", out);
        verify(delegate).call(eq("{\"scriptId\":1}"), any(ToolContext.class));
    }

    @Test
    void nullContextIsBlocked() throws Exception {
        JsonNode json = objectMapper.readTree(gate.call("{}", null));
        assertTrue(json.get("requiresConfirmation").asBoolean());
    }

    @Test
    void singleArgCallIsBlocked() throws Exception {
        JsonNode json = objectMapper.readTree(gate.call("{}"));
        assertTrue(json.get("requiresConfirmation").asBoolean());
    }

    @Test
    void definitionAndMetadataPassThrough() {
        assertSame(delegate.getToolDefinition(), gate.getToolDefinition());
        assertSame(delegate.getToolMetadata(), gate.getToolMetadata());
    }
}