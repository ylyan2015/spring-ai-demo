package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.video.agent.AgentOrchestrationService;
import com.github.ylyan2015.springaidemo.video.agent.DirectorAgentService;
import com.github.ylyan2015.springaidemo.video.agent.VideoAgentToolFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 导演 Agent 对话端点单元测试（Phase 3 切片2）
 */
class VideoAgentControllerChatTest {

    private DirectorAgentService agentService;
    private VideoAgentController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(DirectorAgentService.class);
        VideoAgentToolFactory factory = mock(VideoAgentToolFactory.class);
        when(factory.createTools()).thenReturn(List.of());
        controller = new VideoAgentController(factory, agentService, mock(AgentOrchestrationService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatDelegatesToService() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", "好的");
        data.put("sessionId", "s1");
        data.put("pendingConfirmations", List.of());
        when(agentService.chat("你好", "s1", null)).thenReturn(data);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "你好");
        body.put("sessionId", "s1");
        ResponseEntity<Map<String, Object>> resp = controller.chat(body);

        assertEquals(200, resp.getStatusCode().value());
        assertTrue((Boolean) resp.getBody().get("success"));
        assertEquals("好的", ((Map<String, Object>) resp.getBody().get("data")).get("reply"));
    }

    @Test
    void chatPassesConfirmedTools() {
        when(agentService.chat(anyString(), any(), anyList())).thenReturn(new LinkedHashMap<>());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "确认导出");
        body.put("confirmedTools", List.of("exportVideo"));
        controller.chat(body);
        verify(agentService).chat(eq("确认导出"), isNull(), eq(List.of("exportVideo")));
    }

    @Test
    void chatErrorReturnsBadRequest() {
        when(agentService.chat(anyString(), any(), any())).thenThrow(new RuntimeException("用户未登录"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "你好");
        ResponseEntity<Map<String, Object>> resp = controller.chat(body);
        assertEquals(400, resp.getStatusCode().value());
        assertFalse((Boolean) resp.getBody().get("success"));
    }
}