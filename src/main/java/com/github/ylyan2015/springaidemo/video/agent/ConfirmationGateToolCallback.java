package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工确认闸门（Phase 3 切片2）
 * 装饰耗时/高成本工具（exportVideo）：请求未通过 ToolContext.confirmedTools 显式确认时，
 * 不执行真实逻辑，返回 requiresConfirmation JSON 供导演 Agent 转述给用户，
 * 并把待确认项记入 ToolContext.pendingConfirmations 随响应返回前端。
 */
public class ConfirmationGateToolCallback implements ToolCallback {

    /** ToolContext 键：本次请求已确认的工具名集合 */
    public static final String CONFIRMED_TOOLS_KEY = "confirmedTools";
    /** ToolContext 键：待确认项收集器（List<Map<String,Object>>，调用方预置） */
    public static final String PENDING_CONFIRMATIONS_KEY = "pendingConfirmations";

    private final ToolCallback delegate;
    private final String reason;
    private final ObjectMapper objectMapper;

    public ConfirmationGateToolCallback(ToolCallback delegate, String reason, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.reason = reason;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();
        Map<String, Object> ctx = toolContext != null ? toolContext.getContext() : Map.of();
        Object confirmed = ctx.get(CONFIRMED_TOOLS_KEY);
        boolean isConfirmed = confirmed instanceof Collection<?> c && c.contains(toolName);
        if (isConfirmed) {
            return delegate.call(toolInput, toolContext);
        }

        Map<String, Object> pendingItem = new LinkedHashMap<>();
        pendingItem.put("tool", toolName);
        pendingItem.put("input", toolInput);
        pendingItem.put("reason", reason);
        Object collector = ctx.get(PENDING_CONFIRMATIONS_KEY);
        if (collector instanceof List) {
            ((List<Map<String, Object>>) collector).add(pendingItem);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("requiresConfirmation", true);
        result.put("tool", toolName);
        result.put("message", reason + "。请向用户说明即将执行的操作并请求确认，不要重复调用本工具。");
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":false,\"requiresConfirmation\":true,\"tool\":\"" + toolName + "\"}";
        }
    }
}