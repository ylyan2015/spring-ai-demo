package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.video.agent.AgentOrchestrationService;
import com.github.ylyan2015.springaidemo.video.agent.DirectorAgentService;
import com.github.ylyan2015.springaidemo.video.agent.VideoAgentToolFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短视频 Agent 控制器（Phase 3 切片1 工具内省 + 切片2 导演对话 + 切片3 多 Agent 编排）
 * /tools 工具清单内省；/chat 导演对话（同步 ReAct）；
 * /orchestrate 一键成片异步编排（提交/轮询/确认续跑）。
 * /api/video/** 沿用现有 permitAll 策略，登录校验由服务层保障。
 */
@RestController
@RequestMapping("/api/video/agent")
public class VideoAgentController {

    private final List<ToolCallback> tools;
    private final DirectorAgentService agentService;
    private final AgentOrchestrationService orchestrationService;

    public VideoAgentController(VideoAgentToolFactory toolFactory,
                                DirectorAgentService agentService,
                                AgentOrchestrationService orchestrationService) {
        this.tools = toolFactory.createTools();
        this.agentService = agentService;
        this.orchestrationService = orchestrationService;
    }

    /**
     * 工具清单：名称 / 描述 / 输入 JSON Schema
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> listTools() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolCallback tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getToolDefinition().name());
            item.put("description", tool.getToolDefinition().description());
            item.put("inputSchema", tool.getToolDefinition().inputSchema());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    /**
     * 导演 Agent 对话（同步 ReAct；exportVideo 需 confirmedTools 显式确认）
     */
    @PostMapping("/chat")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String message = (String) request.get("message");
            String sessionId = (String) request.get("sessionId");
            List<String> confirmedTools = request.get("confirmedTools") instanceof List<?> l
                    ? (List<String>) l : null;
            result.put("success", true);
            result.put("data", agentService.chat(message, sessionId, confirmedTools));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 清空导演对话历史
     */
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearChat(@PathVariable String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            agentService.clearHistory(sessionId);
            result.put("success", true);
            result.put("message", "对话历史已清空");
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 一键成片：提交多 Agent 异步编排任务（Phase 3 切片3）
     */
    @PostMapping("/orchestrate")
    public ResponseEntity<Map<String, Object>> orchestrate(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String idea = (String) request.get("idea");
            Long templateId = request.get("templateId") instanceof Number n ? n.longValue() : null;
            result.put("success", true);
            result.put("data", orchestrationService.orchestrate(idea, templateId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 查询编排任务状态（轮询）
     */
    @GetMapping("/orchestrate/{taskId}")
    public ResponseEntity<Map<String, Object>> orchestrationStatus(@PathVariable String taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", orchestrationService.taskStatus(taskId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 确认续跑：放行 exportVideo 闸门
     */
    @PostMapping("/orchestrate/{taskId}/confirm")
    public ResponseEntity<Map<String, Object>> confirmOrchestration(@PathVariable String taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", orchestrationService.confirm(taskId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}