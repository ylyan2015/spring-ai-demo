# Phase 3 切片2：导演 Agent（工具挂载 + ReAct 对话 + 人工确认点）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在切片1 的 6 个工具之上构建导演 Agent：`POST /api/video/agent/chat` 对话端点（ChatClient 挂载工具，Spring AI 内建 tool-calling 循环即 ReAct），`exportVideo` 设人工确认闸门，前端工作室加「AI 导演」对话面板。

**Architecture:** `ConfirmationGateToolCallback`（ToolCallback 装饰器：未确认时拦截 exportVideo 并返回 requiresConfirmation JSON + 记录待确认项到 ToolContext）+ `DirectorAgentService`（ModelService.getChatClient().mutate() 挂导演 System Prompt 与工具；按 userId:sessionId 维护内存对话历史；同步执行于请求线程，SecurityContext 天然可用）+ 控制器对话/清空端点 + 前端确认交互。

**Tech Stack:** Spring Boot 3.4.1 + Java 17 + Spring AI 1.0.0 ChatClient/ToolCallback/ToolContext + JUnit 5 + Mockito

**Spec:** `VIDEO_STUDIO_PLAN.md` 第四章 Phase 3（4.1 导演 Agent / 4.3 实现思路）；切片1 成果 `video/agent/VideoAgentToolFactory.createTools()`

## Global Constraints

- 已核实 API（javap）：`ChatClient.mutate()` → `Builder.defaultSystem(String).defaultToolCallbacks(List<ToolCallback>).build()`；`ChatClientRequestSpec.messages(List<Message>).toolContext(Map).call().content()`；`ToolCallback.call(String, ToolContext)` 为 default 方法；`ToolContext.getContext()` 返回构造传入的 Map
- 确认机制为**请求级显式确认**：请求体 `confirmedTools:["exportVideo"]` 经 ToolContext 传入闸门；不做服务端 pending 令牌存储（YAGNI）
- 仅 `exportVideo` 设闸门（耗时数分钟）；generateImage 等创作循环工具不设闸（文档说明）
- 对话历史内存态（重启清空），按 userId:sessionId 隔离，上限 20 条消息
- 分层约定：构造器注入 + final、SLF4J、统一响应 `{success,message,data}`；登录校验在服务层（/api/video/** 为 permitAll）
- TDD 先红后绿；SecurityContext 测试用三参 UsernamePasswordAuthenticationToken
- 中英文文档严格同步；改动不提交

## File Structure

- Create `video/agent/ConfirmationGateToolCallback.java` — 确认闸门装饰器
- Create `video/agent/DirectorAgentService.java` — 导演 Agent 服务
- Modify `controller/VideoAgentController.java` — 追加 POST /chat、DELETE /chat/{sessionId}
- Modify `templates/video-studio.html` — AI 导演面板（对话 + 确认按钮）
- Create `src/test/java/.../video/agent/ConfirmationGateToolCallbackTest.java`（5 测试）
- Create `src/test/java/.../video/agent/DirectorAgentServiceTest.java`（7 测试）
- Create `src/test/java/.../controller/VideoAgentControllerChatTest.java`（3 测试）
- Modify README.md/en、API_TEST.md/en、VIDEO_STUDIO_PLAN.md

---

### Task 1: 确认闸门装饰器

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/ConfirmationGateToolCallback.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/ConfirmationGateToolCallbackTest.java`

**Interfaces:**
- Consumes: `ToolCallback`（被装饰的原始工具）、`ToolContext.getContext()` 中的 `"confirmedTools"`（Collection<String>）与 `"pendingConfirmations"`（List<Map<String,Object>>，由 DirectorAgentService 预置）
- Produces: 未确认 → JSON `{"success":false,"requiresConfirmation":true,"tool":"exportVideo","message":"..."}` 且向 pendingConfirmations 追加 `{tool, input, reason}`；已确认 → 透传 delegate.call(input, ctx)

- [x] **Step 1: 写失败测试**

```java
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
```

- [x] **Step 2: 运行确认红灯** — `mvn -q test -Dtest=ConfirmationGateToolCallbackTest` → 编译失败「找不到符号 ConfirmationGateToolCallback」

- [x] **Step 3: 实现**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
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
```

- [x] **Step 4: 运行确认绿灯** — Tests run: 5, Failures: 0

---

### Task 2: 导演 Agent 服务

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/DirectorAgentService.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/DirectorAgentServiceTest.java`

**Interfaces:**
- Consumes: `ModelService.getChatClient()`、`VideoAgentToolFactory.createTools()`、Task 1 闸门、`UserRepository.findByUsername`
- Produces: `Map<String,Object> chat(String message, String sessionId, List<String> confirmedTools)` → `{reply, sessionId, pendingConfirmations}`；`void clearHistory(String sessionId)`；未登录/空消息抛 RuntimeException

- [x] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 导演 Agent 服务单元测试（Phase 3 切片2）
 */
class DirectorAgentServiceTest {

    private ModelService modelService;
    private VideoAgentToolFactory toolFactory;
    private UserRepository userRepository;
    private ChatClient baseClient;
    private ChatClient agentClient;
    private DirectorAgentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        toolFactory = mock(VideoAgentToolFactory.class);
        userRepository = mock(UserRepository.class);
        baseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agentClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(baseClient);
        when(baseClient.mutate().defaultSystem(anyString()).defaultToolCallbacks(anyList()).build())
                .thenReturn(agentClient);
        when(toolFactory.createTools()).thenReturn(List.of());
        when(agentClient.prompt().messages(anyList()).toolContext(anyMap()).call().content())
                .thenReturn("导演回复");

        service = new DirectorAgentService(modelService, toolFactory, userRepository, new ObjectMapper());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatReturnsReplyAndSessionId() {
        Map<String, Object> out = service.chat("你好", null, null);
        assertEquals("导演回复", out.get("reply"));
        assertNotNull(out.get("sessionId"));
        assertTrue(((List<?>) out.get("pendingConfirmations")).isEmpty());
    }

    @Test
    void chatRequiresLogin() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.chat("你好", null, null));
        assertTrue(e.getMessage().contains("未登录"));
    }

    @Test
    void blankMessageRejected() {
        assertThrows(RuntimeException.class, () -> service.chat("  ", null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedToolsPassedIntoToolContext() {
        service.chat("确认导出", "s1", List.of("exportVideo"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(agentClient.prompt()).toolContext(captor.capture());
        Object confirmed = captor.getValue().get(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY);
        assertTrue(((java.util.Collection<String>) confirmed).contains("exportVideo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingConfirmationsReturnedFromToolContext() {
        when(agentClient.prompt().messages(anyList()).toolContext(anyMap()).call().content())
                .thenAnswer(inv -> {
                    // 模拟闸门在工具执行期写入待确认项：从深桩调用链拿不到 ctx，
                    // 改为在 thenAnswer 中通过 captor 预先捕获的 map 写入
                    Map<String, Object> ctx = lastCtx;
                    ((List<Map<String, Object>>) ctx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY))
                            .add(Map.of("tool", "exportVideo"));
                    return "请确认导出";
                });
        // 先捕获 toolContext 参数
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        service.chat("导出视频", "s2", null);
        verify(agentClient.prompt(), atLeastOnce()).toolContext(captor.capture());
        lastCtx = captor.getValue();
        // 重新调用使 thenAnswer 生效（lastCtx 已就绪）
        Map<String, Object> out = service.chat("导出视频", "s3", null);
        assertEquals(1, ((List<?>) out.get("pendingConfirmations")).size());
    }

    private Map<String, Object> lastCtx = new java.util.HashMap<>();

    @Test
    @SuppressWarnings("unchecked")
    void historyAccumulatesAcrossCalls() {
        service.chat("第一句", "h1", null);
        service.chat("第二句", "h1", null);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentClient.prompt(), times(2)).messages(captor.capture());
        List<Message> secondCall = captor.getAllValues().get(1);
        // 第一句 user + 导演回复 assistant + 第二句 user
        assertEquals(3, secondCall.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearHistoryResetsSession() {
        service.chat("第一句", "c1", null);
        service.clearHistory("c1");
        service.chat("新开始", "c1", null);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentClient.prompt(), times(2)).messages(captor.capture());
        assertEquals(1, captor.getAllValues().get(1).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportVideoToolIsGated() {
        ToolCallback raw = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(raw.getToolDefinition().name()).thenReturn("exportVideo");
        when(toolFactory.createTools()).thenReturn(List.of(raw));
        service.chat("导出", "g1", null);
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate()).defaultToolCallbacks(captor.capture());
        assertTrue(captor.getValue().get(0) instanceof ConfirmationGateToolCallback);
    }
}
```

注：`pendingConfirmationsReturnedFromToolContext` 用例若深桩时序导致不稳定，可简化为直接构造含 pending 的 ctx 验证 chat 返回值读取逻辑（实现时以稳定通过为准，断言目标不变：pendingConfirmations 来自 ToolContext 收集器）。

- [x] **Step 2: 运行确认红灯** — 编译失败「找不到符号 DirectorAgentService」

- [x] **Step 3: 实现**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导演 Agent 服务（Phase 3 切片2）
 * ChatClient 挂载切片1 工具（Spring AI 内建 tool-calling 循环即 ReAct：思考→调用工具→观察→继续），
 * exportVideo 经人工确认闸门；对话历史按 userId:sessionId 内存隔离（上限 20 条，重启清空）。
 * 同步执行于请求线程，SecurityContext 天然可用；异步编排需另行传播（见项目陷阱记忆）。
 */
@Service
public class DirectorAgentService {

    private static final Logger log = LoggerFactory.getLogger(DirectorAgentService.class);

    /** 需要人工确认的工具（耗时/高成本） */
    private static final Set<String> GATED_TOOLS = Set.of("exportVideo");
    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String DIRECTOR_SYSTEM_PROMPT = """
            你是「AI 短视频导演」，负责协调编剧、美术、音频、剪辑能力，把用户的创意变成短视频。
            可用工具：generateStoryboard（生成分镜脚本）、enhancePrompt（优化提示词）、generateImage（单镜头生图）、
            evaluateImage（图片质量评分）、exportVideo（合成导出，需用户确认）、recommendBgm（配乐推荐）。
            工作准则：
            1. 典型流程：生成分镜 → 逐镜头生成图片 → （可选）质量评估 → 推荐配乐 → 导出；按用户意图灵活裁剪。
            2. 工具返回 success:false 时，向用户解释原因并调整策略，不要原样重复调用。
            3. 工具返回 requiresConfirmation:true 时，停止调用该工具，向用户说明即将执行的操作与参数，请求确认。
            4. 不要编造脚本ID/镜头ID，一律以工具返回值为准；缺少信息时先向用户询问。
            5. 用简体中文回复，简洁说明每一步做了什么、下一步建议。
            """;

    private final ModelService modelService;
    private final VideoAgentToolFactory toolFactory;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** 对话历史：userId:sessionId → 消息列表（内存态） */
    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();

    public DirectorAgentService(ModelService modelService,
                                VideoAgentToolFactory toolFactory,
                                UserRepository userRepository,
                                ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.toolFactory = toolFactory;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 导演对话（同步 ReAct：Spring AI 自动执行工具调用循环直至产出最终回复）
     *
     * @param message        用户消息（必填）
     * @param sessionId      会话ID（null 时自动生成）
     * @param confirmedTools 本次请求显式确认的工具名（如 ["exportVideo"]）
     * @return {reply, sessionId, pendingConfirmations}
     */
    public Map<String, Object> chat(String message, String sessionId, List<String> confirmedTools) {
        Long userId = requireCurrentUserId();
        if (message == null || message.isBlank()) {
            throw new RuntimeException("消息不能为空");
        }
        String sid = sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
        String key = userId + ":" + sid;

        // 工具装配： gated 工具包确认闸门
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback tool : toolFactory.createTools()) {
            String name = tool.getToolDefinition().name();
            if (GATED_TOOLS.contains(name)) {
                tools.add(new ConfirmationGateToolCallback(tool,
                        "导出视频将执行 FFmpeg 合成（耗时数分钟）", objectMapper));
            } else {
                tools.add(tool);
            }
        }

        // ToolContext：确认集合 + 待确认收集器（闸门与响应共用同一 Map 实例）
        List<Map<String, Object>> pending = new ArrayList<>();
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY,
                confirmedTools != null ? Set.copyOf(confirmedTools) : Set.of());
        toolContext.put(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY, pending);

        ChatClient client = modelService.getChatClient().mutate()
                .defaultSystem(DIRECTOR_SYSTEM_PROMPT)
                .defaultToolCallbacks(tools)
                .build();

        List<Message> history = conversations.computeIfAbsent(key, k -> new ArrayList<>());
        history.add(new UserMessage(message));
        trim(history);

        log.info("Director agent chat: user={}, session={}, confirmed={}", userId, sid, confirmedTools);
        String reply = client.prompt()
                .messages(history)
                .toolContext(toolContext)
                .call()
                .content();

        history.add(new AssistantMessage(reply != null ? reply : ""));
        trim(history);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("sessionId", sid);
        out.put("pendingConfirmations", pending);
        return out;
    }

    /** 清空指定会话的对话历史 */
    public void clearHistory(String sessionId) {
        Long userId = requireCurrentUserId();
        if (sessionId != null && !sessionId.isBlank()) {
            conversations.remove(userId + ":" + sessionId);
        }
    }

    private void trim(List<Message> history) {
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
    }

    private Long requireCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }
}
```

- [x] **Step 4: 运行确认绿灯** — Tests run: 7, Failures: 0（若 pending 用例深桩时序不稳，按 Step 1 注释简化实现方式，断言目标不变）

---

### Task 3: 控制器对话端点

**Files:**
- Modify: `src/main/java/com/github/ylyan2015/springaidemo/controller/VideoAgentController.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/controller/VideoAgentControllerChatTest.java`

**Interfaces:**
- Consumes: `DirectorAgentService.chat / clearHistory`（Task 2）
- Produces: `POST /api/video/agent/chat` body `{message, sessionId?, confirmedTools?}` → `{success:true, data:{reply, sessionId, pendingConfirmations}}`；`DELETE /api/video/agent/chat/{sessionId}` → `{success:true}`；RuntimeException → 400 `{success:false,message}`

- [x] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.controller;

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
        controller = new VideoAgentController(factory, agentService);
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
    @SuppressWarnings("unchecked")
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
```

- [x] **Step 2: 运行确认红灯** — 编译失败（构造器双参/方法不存在）

- [x] **Step 3: 修改控制器** — 构造器追加 `DirectorAgentService`（保留工具内省），新增：

```java
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
```

同步更新既有 VideoAgentControllerTest 的构造调用为双参（追加 mock DirectorAgentService）。

- [x] **Step 4: 运行确认绿灯** — 新增 3 + 既有 2 全过

---

### Task 4: 前端 AI 导演面板

**Files:**
- Modify: `src/main/resources/templates/video-studio.html`

- [x] **Step 1: 在导出面板之后新增「AI 导演」区块**（HTML）：

```html
<div class="panel" id="agentPanel">
    <h3>🤖 AI 导演 <span style="font-size:12px;color:#888">（对话式生成 · 导出需确认）</span></h3>
    <div id="agentLog" style="max-height:260px;overflow-y:auto;background:#12121f;border-radius:8px;padding:10px;margin-bottom:8px;font-size:13px"></div>
    <div id="agentConfirmBar" style="display:none;margin-bottom:8px"></div>
    <div style="display:flex;gap:8px">
        <input id="agentInput" placeholder="例如：帮我做一个猫咪日常的治愈系短片" style="flex:1" onkeydown="if(event.key==='Enter')sendAgentMessage()">
        <button class="btn" id="agentSendBtn" onclick="sendAgentMessage()">发送</button>
        <button class="btn btn-ghost" onclick="clearAgentChat()">清空</button>
    </div>
</div>
```

- [x] **Step 2: JS 逻辑**（全局 `agentSessionId=null, agentLastMessage='', agentBusy=false`）：

```javascript
// ==================== AI 导演（Phase 3 切片2） ====================
function appendAgentLog(role, text) {
    const log = document.getElementById('agentLog');
    const color = role === 'user' ? '#4a6cf7' : (role === 'error' ? '#f74a4a' : '#2ecc71');
    log.innerHTML += '<div style="margin-bottom:6px"><b style="color:' + color + '">' +
        (role === 'user' ? '我' : (role === 'error' ? '错误' : '导演')) + '：</b>' + esc(text) + '</div>';
    log.scrollTop = log.scrollHeight;
}

async function sendAgentMessage(confirmedTools) {
    const input = document.getElementById('agentInput');
    const msg = confirmedTools ? agentLastMessage : input.value.trim();
    if (!msg || agentBusy) return;
    agentLastMessage = msg;
    if (!confirmedTools) { input.value = ''; appendAgentLog('user', msg); }
    agentBusy = true;
    document.getElementById('agentSendBtn').disabled = true;
    document.getElementById('agentConfirmBar').style.display = 'none';
    appendAgentLog('director', '思考中...');
    try {
        const body = { message: msg, sessionId: agentSessionId };
        if (confirmedTools) body.confirmedTools = confirmedTools;
        const data = await post('/api/video/agent/chat', body);
        const log = document.getElementById('agentLog');
        log.removeChild(log.lastChild); // 移除"思考中"
        if (!data.success) { appendAgentLog('error', data.message || '对话失败'); return; }
        agentSessionId = data.data.sessionId;
        appendAgentLog('director', data.data.reply || '(无回复)');
        const pending = data.data.pendingConfirmations || [];
        if (pending.length > 0) {
            const bar = document.getElementById('agentConfirmBar');
            bar.innerHTML = '';
            pending.forEach(p => {
                const btn = document.createElement('button');
                btn.className = 'btn btn-orange btn-sm';
                btn.textContent = '确认执行 ' + p.tool;
                btn.onclick = () => sendAgentMessage([p.tool]);
                bar.appendChild(btn);
            });
            bar.style.display = 'block';
        }
        loadScriptList(true); // 工具可能新建了脚本，刷新列表
    } finally {
        agentBusy = false;
        document.getElementById('agentSendBtn').disabled = false;
    }
}

async function clearAgentChat() {
    if (agentSessionId) await api('/api/video/agent/chat/' + agentSessionId, { method: 'DELETE' });
    agentSessionId = null;
    document.getElementById('agentLog').innerHTML = '';
    document.getElementById('agentConfirmBar').style.display = 'none';
}
```

- [x] **Step 3: JS 语法检查** — 提取 script 块 `node --check` 通过

---

### Task 5: 全量验证 + 真实 LLM 冒烟

- [x] **Step 1: 全量测试** — `mvn -q test`：76 既有 + 15 新增 = 91（1 个 TTS 默认跳过）
- [x] **Step 2: 启动冒烟（注入 DEEPSEEK_API_KEY，从 launch.json 正则提取）** — Node 脚本：注册登录 → POST /api/video/agent/chat `{message:"你能做什么？请不要调用任何工具，直接回答"}` → 断言 reply 非空；再发 `{message:"推荐一首适合温馨猫咪日常视频的BGM"}` → 断言 reply 非空（验证真实 tool-calling：recommendBgm）；停止进程
- [x] **Step 3: 确认闸门冒烟（可选）** — `{message:"把脚本1导出为视频"}`（无脚本时工具返回失败 JSON，验证 Agent 能转述错误）

---

### Task 6: 中英文文档同步 + 计划状态

- [x] **Step 1: README 双语** — 特性加「🤖 AI 导演对话」；结构加 ConfirmationGateToolCallback/DirectorAgentService；API 表加 POST /api/video/agent/chat、DELETE /api/video/agent/chat/{sessionId}
- [x] **Step 2: API_TEST 双语** — 7.12 导演 Agent 章节：请求参数表（message/sessionId/confirmedTools）、响应示例（含 pendingConfirmations）、确认流程两轮示例、注意事项（内存历史/仅 exportVideo 设闸/同步执行）；Postman 43/44；注意事项 22
- [x] **Step 3: VIDEO_STUDIO_PLAN.md** — 切片2 标 ✅ + 实现落点；最后更新行
- [x] **Step 4: 中英一致性复核**

---

## Self-Review 结论

1. **Spec 覆盖**：4.1 导演 Agent（编排 6 工具）✅、4.3 ChatClient 挂工具 ✅、人工确认点 ✅（exportVideo 闸门 + 前端确认按钮）；多 Agent 拆分属切片3
2. **占位符扫描**：无 TBD；所有代码步骤含完整代码（pending 测试用例给出稳定性降级说明，断言目标明确）
3. **类型一致性**：CONFIRMED_TOOLS_KEY/PENDING_CONFIRMATIONS_KEY 常量、chat 三参签名、控制器双参构造均跨任务一致；API 经 javap 核实

---

## 执行记录（2026-11-14，全部完成）

- **测试**：新增 16（闸门 5 + 服务 8 + 控制器 3），全量 92 绿（既有 76 + 16）。服务层比计划多 1 测试：pending 用例采用稳定变体（toolContext 桩捕获 map + content 桩模拟闸门写入）并独立断言 reply
- **实现偏差**：① DirectorAgentService 向 messages() 传历史快照副本（避免捕获引用受后续修改影响）；② Mockito 深桩整链 stub 注册时的中间调用会被计为实际调用，测试改为分段 stub + atLeast/非空参数过滤（已验证稳定）
- **真实 LLM 冒烟（DeepSeek）**：注册/纯对话/recommendBgm 真实工具调用/闸门拦截（pendingConfirmations 含 exportVideo 及完整入参）/确认后放行（工具真实执行，脚本不存在错误被 Agent 正确转述）/清空历史/匿名 400 均 PASS。注：模糊指令下 LLM 可能先询问而不调工具（闸门不触发属正常行为），专项复测用明确指令验证
- **前端**：工作室底部「AI 导演」面板（对话日志 + 确认按钮 + 清空），JS 语法 node --check 通过
- **文档**：README 双语（特性/结构/API 表）、API_TEST 双语 7.12 + Postman 43/44 + 注意事项 22、VIDEO_STUDIO_PLAN 切片2 ✅
- **git 排除**：.gitignore 补 data/chatdb.* 与 uploads/；已暂存的 chatdb 文件 git rm --cached 移出索引（本地保留）
- 改动未提交（按用户惯例由用户自行提交）