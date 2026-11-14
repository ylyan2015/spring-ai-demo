package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 聊天端点越权/异常状态码一致性测试（release 复审 m-1）：
 * context-usage/send/stream 与 history 一样，归属校验失败统一返回 400（stream 为 error 事件），
 * 而不是未包装异常导致的 500。
 */
class ChatControllerErrorStatusTest {

    private ChatService chatService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        controller = new ChatController(chatService);
    }

    private ChatController.ChatRequest request(String sessionId) {
        ChatController.ChatRequest req = new ChatController.ChatRequest();
        req.setSessionId(sessionId);
        req.setMessage("hi");
        return req;
    }

    @Test
    void contextUsageOwnershipErrorReturns400() {
        when(chatService.getContextUsagePercent("other"))
                .thenThrow(new RuntimeException("无权操作该会话"));

        ResponseEntity<Map<String, Object>> resp = controller.getContextUsage("other");

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(false, resp.getBody().get("success"));
        assertEquals("无权操作该会话", resp.getBody().get("message"));
    }

    @Test
    void sendOwnershipErrorReturns400() {
        when(chatService.sendMessageWithSession(eq("other"), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("无权操作该会话"));

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(request("other"));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(false, resp.getBody().get("success"));
        assertEquals("无权操作该会话", resp.getBody().get("message"));
    }

    @Test
    void streamOwnershipErrorEmitsErrorEvent() {
        when(chatService.streamMessage(eq("other"), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("无权操作该会话"));

        Flux<ServerSentEvent<String>> flux = controller.streamMessage(request("other"));

        ServerSentEvent<String> first = flux.blockFirst(Duration.ofSeconds(5));
        assertNotNull(first, "越权时应返回 error 事件而不是抛异常");
        assertEquals("error", first.event());
        assertTrue(first.data().contains("无权操作该会话"));
    }
}