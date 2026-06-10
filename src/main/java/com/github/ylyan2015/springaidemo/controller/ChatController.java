package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.Conversation;
import com.github.ylyan2015.springaidemo.entity.Message;
import com.github.ylyan2015.springaidemo.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天控制器
 * 提供多轮对话、会话管理等API接口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送消息（支持多轮对话）
     *
     * @param request 包含sessionId和message的请求体
     * @return AI回复
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody ChatRequest request) {
        String response = chatService.sendMessage(request.getSessionId(), request.getMessage());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", request.getSessionId() != null ? request.getSessionId() : "auto-generated");
        result.put("response", response);
        
        return ResponseEntity.ok(result);
    }

    /**
     * GET方式发送消息（兼容旧接口）
     *
     * @param message 用户消息
     * @param sessionId 会话ID（可选）
     * @return AI回复
     */
    @GetMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessageGet(
            @RequestParam String message,
            @RequestParam(required = false) String sessionId) {
        String response = chatService.sendMessage(sessionId, message);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId != null ? sessionId : "auto-generated");
        result.put("response", response);
        
        return ResponseEntity.ok(result);
    }

    /**
     * 获取会话历史消息
     *
     * @param sessionId 会话ID
     * @return 历史消息列表
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Map<String, Object>> getHistory(@PathVariable String sessionId) {
        List<Message> history = chatService.getConversationHistory(sessionId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        result.put("messages", history);
        result.put("count", history.size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 创建新会话
     *
     * @return 新会话ID
     */
    @PostMapping("/conversation")
    public ResponseEntity<Map<String, Object>> createConversation() {
        String sessionId = chatService.createConversation();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        
        return ResponseEntity.ok(result);
    }

    /**
     * 删除会话（仅允许拥有者删除）
     *
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/conversation/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(@PathVariable String sessionId) {
        try {
            chatService.deleteConversation(sessionId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "会话已删除");
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取当前用户的所有会话
     */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> listConversations() {
        List<Conversation> convs = chatService.getUserConversations();
        List<Map<String, Object>> items = convs.stream().map(c -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", c.getSessionId());
            item.put("title", c.getTitle() != null ? c.getTitle() : "新对话");
            item.put("updatedAt", c.getUpdatedAt());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversations", items);
        return ResponseEntity.ok(result);
    }

    /**
     * 聊天请求DTO
     */
    public static class ChatRequest {
        private String sessionId;
        private String message;

        public ChatRequest() {
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
