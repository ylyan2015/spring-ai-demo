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
 * 多 Agent 编排端点单元测试（Phase 3 切片3）
 */
class VideoAgentControllerOrchestrationTest {

    private AgentOrchestrationService orchestrationService;
    private VideoAgentController controller;

    @BeforeEach
    void setUp() {
        orchestrationService = mock(AgentOrchestrationService.class);
        VideoAgentToolFactory factory = mock(VideoAgentToolFactory.class);
        when(factory.createTools()).thenReturn(List.of());
        controller = new VideoAgentController(factory, mock(DirectorAgentService.class), orchestrationService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void orchestrateDelegatesToService() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", "t1");
        data.put("status", "RUNNING");
        when(orchestrationService.orchestrate("猫咪日常", 3L)).thenReturn(data);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idea", "猫咪日常");
        body.put("templateId", 3);
        ResponseEntity<Map<String, Object>> resp = controller.orchestrate(body);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue((Boolean) resp.getBody().get("success"));
        assertEquals("t1", ((Map<String, Object>) resp.getBody().get("data")).get("taskId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusAndConfirmDelegate() {
        when(orchestrationService.taskStatus("t1")).thenReturn(Map.of("status", "AWAITING_CONFIRMATION"));
        when(orchestrationService.confirm("t1")).thenReturn(Map.of("status", "RUNNING"));
        assertEquals("AWAITING_CONFIRMATION",
                ((Map<String, Object>) controller.orchestrationStatus("t1").getBody().get("data")).get("status"));
        assertEquals("RUNNING",
                ((Map<String, Object>) controller.confirmOrchestration("t1").getBody().get("data")).get("status"));
    }

    @Test
    void errorReturnsBadRequest() {
        when(orchestrationService.orchestrate(anyString(), any())).thenThrow(new RuntimeException("用户未登录"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idea", "x");
        ResponseEntity<Map<String, Object>> resp = controller.orchestrate(body);
        assertEquals(400, resp.getStatusCode().value());
        assertFalse((Boolean) resp.getBody().get("success"));
    }
}