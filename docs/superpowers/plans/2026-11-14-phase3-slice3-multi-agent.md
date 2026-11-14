# Phase 3 切片3：多 Agent 编排（专家拆分 + 异步 SecurityContext 传播）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 按规格 4.1/4.2 拆分编剧/美术/音频/剪辑四个专家 Agent（各自 System Prompt + 工具子集），以「专家即工具」委托给导演；新增异步编排端点（提交→轮询→确认续跑），工作线程显式传播 SecurityContext。

**Architecture:** `SpecialistAgentFactory`（4 专家 ChatClient + 4 个 delegateToXxx 委托工具，剪辑专家的 exportVideo 包确认闸门）→ `DirectorAgentService` 工具装配追加委托工具（6+4=10）并新增异步编排（内存任务表 + 专用线程池 + 提交时捕获 SecurityContext、工作线程 set/finally clear）→ 控制器 3 端点 → 前端「一键流水线」按钮 + 轮询。

**Tech Stack:** Spring Boot 3.4.1 + Java 17 + Spring AI 1.0.0 ChatClient/FunctionToolCallback + Spring Security 6.4.2（SecurityContextHolder 显式传播）+ JUnit 5 + Mockito

**Spec:** `VIDEO_STUDIO_PLAN.md` 第四章 4.1 角色设计 / 4.2 工具分配 / 4.3 实现思路；切片1/2 成果（VideoAgentToolFactory / ConfirmationGateToolCallback / DirectorAgentService）

## Global Constraints

- 已核实 API：`FunctionToolCallback.builder(name, Function<I,O>).description().inputType().build()`；`ChatClient.mutate().defaultSystem().defaultToolCallbacks(List).build()`；`prompt().messages(List).toolContext(Map).call().content()`；spring-security-core 实际版本 6.4.2
- **SecurityContext 传播**（陷阱记忆 8e17127a/82dcf821）：ThreadLocal 不跨线程；提交时捕获 `SecurityContextHolder.getContext()`，工作线程内 `setContext` + `finally clearContext`；专家/工具都在编排工作线程内同步执行，天然继承；ImageGenerationService 内部并行池已自行传播
- 编排任务表内存态（重启清空），上限 100 条（超限清理最旧终态任务）；任务按 userId 归属校验
- 确认沿用切片2 请求级机制：任务 AWAITING_CONFIRMATION 时经 confirm 端点携 confirmedTools 续跑（无 pending 令牌）
- 委托工具无状态：每次调用新建专家 prompt（不携带专家历史），上下文由导演会话历史承载
- 分层约定：构造器注入 + final、SLF4J、统一响应 {success,message,data}、登录校验在服务层；测试用三参 UsernamePasswordAuthenticationToken；Mockito 深桩分段 stub + atLeast/非空过滤（记忆 0189e79d）
- TDD 先红后绿；中英文文档严格同步；改动不提交

## File Structure

- Create `video/agent/SpecialistAgentFactory.java` — 4 专家 + 4 委托工具
- Create `video/agent/AgentOrchestrationService.java` — 异步编排 + 任务表（内嵌 OrchestrationTask）
- Modify `video/agent/DirectorAgentService.java` — 构造器加 SpecialistAgentFactory；工具装配追加委托；导演 Prompt 更新
- Modify `controller/VideoAgentController.java` — 构造器三参 + 3 端点
- Modify `templates/video-studio.html` — 一键流水线按钮 + 轮询
- Create 测试：SpecialistAgentFactoryTest（7）/ AgentOrchestrationServiceTest（8）/ VideoAgentControllerOrchestrationTest（3）；Modify DirectorAgentServiceTest（+1 且构造器适配）/ VideoAgentControllerTest、VideoAgentControllerChatTest（构造器适配）
- Modify README.md/en、API_TEST.md/en、VIDEO_STUDIO_PLAN.md

---

### Task 1: 专家 Agent 工厂（4 专家 + 委托工具）

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/SpecialistAgentFactory.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/SpecialistAgentFactoryTest.java`

**Interfaces:**
- Consumes: `ModelService.getChatClient()`、`VideoAgentToolFactory.createTools()`、`ConfirmationGateToolCallback`、ObjectMapper
- Produces: `List<ToolCallback> createDelegateTools()` → 4 个工具 delegateToScreenwriter/delegateToArtist/delegateToAudio/delegateToEditor，inputType `AgentToolRequests.DelegateTask(String taskDescription)`；工具执行 = 对应专家 ChatClient 单轮 ReAct，异常转 {success:false,message}

**专家工具子集（规格 4.2 映射到现有 6 工具；editShot/generateTts/mixAudio 等未 Tool 化能力不新增，TTS/混音已内嵌于 exportVideo，文档注明）：**

| 专家 | 工具子集 | 闸门 |
|------|---------|------|
| 编剧 screenwriter | generateStoryboard, enhancePrompt | 无 |
| 美术 artist | generateImage, evaluateImage | 无 |
| 音频 audio | recommendBgm | 无 |
| 剪辑 editor | exportVideo | ConfirmationGate |

- [x] **Step 1: 写失败测试**

```java
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
        delegate(List.of(rawTool("generateStoryboard"), rawTool("enhancePrompt"),
                rawTool("generateImage"), rawTool("exportVideo")), "delegateToScreenwriter");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of("generateStoryboard", "enhancePrompt"),
                mounted.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void artistGetsImageToolsOnly() {
        delegate(List.of(rawTool("generateStoryboard"), rawTool("generateImage"),
                rawTool("evaluateImage"), rawTool("exportVideo")), "delegateToArtist");
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> mounted = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of("generateImage", "evaluateImage"),
                mounted.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void audioGetsBgmToolOnly() {
        delegate(List.of(rawTool("recommendBgm"), rawTool("exportVideo")), "delegateToAudio");
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
        delegate(List.of(rawTool("exportVideo"), rawTool("generateImage")), "delegateToEditor");
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
        when(specialistClient.prompt().call().content()).thenThrow(new RuntimeException("模型不可用"));
        ToolCallback tool = delegate(List.of(), "delegateToAudio");
        JsonNode json = objectMapper.readTree(tool.call("{\"taskDescription\":\"x\"}"));
        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("message").asText().contains("模型不可用"));
    }
}
```

注：`delegateFailureReturnsErrorJson` 的深桩链若与实现调用链（messages/toolContext）不一致导致 stub 未命中，以实现实际调用链为准补 stub（断言目标不变：异常转错误 JSON）。

- [x] **Step 2: 运行确认红灯** — `mvn -q test -Dtest=SpecialistAgentFactoryTest` → 编译失败「找不到符号 SpecialistAgentFactory」

- [x] **Step 3: AgentToolRequests 追加 DelegateTask record**

```java
    /** 委托专家 Agent 执行子任务（Phase 3 切片3） */
    public record DelegateTask(
            @ToolParam(description = "交给专家的子任务描述，需包含必要上下文（如脚本ID、镜头ID、主题），专家不共享导演对话历史") String taskDescription) {}
```

- [x] **Step 4: 实现 SpecialistAgentFactory**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专家 Agent 工厂（Phase 3 切片3，规格 4.1/4.2）
 * 编剧/美术/音频/剪辑四专家：各自 System Prompt + 工具子集，
 * 以「专家即工具」delegateToXxx 供导演委托；专家调用无状态（单轮 ReAct），
 * 在调用线程内同步执行（异步编排时即编排工作线程，SecurityContext 已传播）。
 * 剪辑专家的 exportVideo 包确认闸门；TTS/混音已内嵌于 exportVideo 管线，不另设工具。
 */
@Component
public class SpecialistAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SpecialistAgentFactory.class);

    private static final Map<String, Set<String>> SPECIALIST_TOOLS = Map.of(
            "screenwriter", Set.of("generateStoryboard", "enhancePrompt"),
            "artist", Set.of("generateImage", "evaluateImage"),
            "audio", Set.of("recommendBgm"),
            "editor", Set.of("exportVideo"));

    private static final Map<String, String> SPECIALIST_PROMPTS = Map.of(
            "screenwriter", """
                    你是短视频编剧专家，负责把创意变成可拍摄的分镜脚本。
                    用 generateStoryboard 生成脚本（返回脚本ID与镜头列表），必要时用 enhancePrompt 优化画面提示词。
                    完成后汇报：脚本ID、镜头数、每镜头画面概要。工具失败时说明原因，不要编造ID。用简体中文回复。""",
            "artist", """
                    你是短视频美术专家，负责为分镜镜头生成高质量画面。
                    用 generateImage 逐镜头生成图片（入参需 scriptId 与 shotId），可用 evaluateImage 评估质量。
                    完成后汇报每个镜头的图片状态与URL。工具失败时说明原因并给出建议，不要编造ID。用简体中文回复。""",
            "audio", """
                    你是短视频音频专家，负责为视频挑选配乐。
                    用 recommendBgm 按主题情感推荐BGM，汇报推荐分类、曲目与BGM ID。
                    旁白配音与混音在导出管线内自动完成，无需额外操作。用简体中文回复。""",
            "editor", """
                    你是短视频剪辑专家，负责把脚本合成为视频。
                    用 exportVideo 导出（preview=true 出480p预览，否则高清）。
                    工具返回 requiresConfirmation:true 时立即停止并原样转述待确认信息，不要重复调用。
                    完成后汇报视频URL与时长。用简体中文回复。""");

    private final ModelService modelService;
    private final VideoAgentToolFactory toolFactory;
    private final ObjectMapper objectMapper;

    public SpecialistAgentFactory(ModelService modelService,
                                  VideoAgentToolFactory toolFactory,
                                  ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.toolFactory = toolFactory;
        this.objectMapper = objectMapper;
    }

    /** 构建 4 个委托工具（每次调用新建，无状态） */
    public List<ToolCallback> createDelegateTools() {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(delegate("delegateToScreenwriter", "screenwriter",
                "委托编剧专家：生成分镜脚本/优化提示词。taskDescription 需包含创意想法与可选模板ID"));
        tools.add(delegate("delegateToArtist", "artist",
                "委托美术专家：为镜头生成图片/评估质量。taskDescription 需包含脚本ID与镜头ID列表"));
        tools.add(delegate("delegateToAudio", "audio",
                "委托音频专家：按主题推荐BGM。taskDescription 需包含视频主题与情感基调"));
        tools.add(delegate("delegateToEditor", "editor",
                "委托剪辑专家：合成导出视频（需用户确认）。taskDescription 需包含脚本ID与导出参数"));
        return tools;
    }

    private ToolCallback delegate(String toolName, String specialist, String description) {
        return FunctionToolCallback.builder(toolName, guarded(input -> runSpecialist(specialist, input)))
                .description(description)
                .inputType(AgentToolRequests.DelegateTask.class)
                .build();
    }

    private Map<String, Object> runSpecialist(String specialist, AgentToolRequests.DelegateTask req) {
        if (req.taskDescription() == null || req.taskDescription().isBlank()) {
            throw new IllegalArgumentException("taskDescription 不能为空");
        }
        List<ToolCallback> subset = new ArrayList<>();
        for (ToolCallback tool : toolFactory.createTools()) {
            String name = tool.getToolDefinition().name();
            if (!SPECIALIST_TOOLS.get(specialist).contains(name)) {
                continue;
            }
            if ("editor".equals(specialist) && "exportVideo".equals(name)) {
                subset.add(new ConfirmationGateToolCallback(tool,
                        "导出视频将执行 FFmpeg 合成（耗时数分钟）", objectMapper));
            } else {
                subset.add(tool);
            }
        }
        ChatClient client = modelService.getChatClient().mutate()
                .defaultSystem(SPECIALIST_PROMPTS.get(specialist))
                .defaultToolCallbacks(subset)
                .build();
        // 专家不共享导演历史：单轮任务描述；确认集合/待确认收集器由导演的 ToolContext 承担，
        // 专家侧闸门未确认时仅返回 requiresConfirmation JSON，由专家转述、导演汇总
        String reply = client.prompt()
                .messages(List.of(new UserMessage(req.taskDescription())))
                .call()
                .content();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("specialist", specialist);
        out.put("reply", reply != null ? reply : "");
        return out;
    }

    private <I> java.util.function.Function<I, Map<String, Object>> guarded(
            java.util.function.Function<I, Map<String, Object>> fn) {
        return input -> {
            try {
                return fn.apply(input);
            } catch (Exception e) {
                log.warn("Specialist delegation failed: {}", e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return err;
            }
        };
    }
}
```

**设计说明（闸门在委托链的行为）**：专家侧 ToolContext 无 confirmedTools → 剪辑专家的 exportVideo 必然返回 requiresConfirmation JSON，专家按 Prompt 转述；导演收到委托结果后向用户请求确认，confirm 续跑时导演**直接调用自己的 exportVideo**（已确认放行）完成导出。此行为在 API_TEST 7.13 注明。

- [x] **Step 5: 运行确认绿灯** — Tests run: 7, Failures: 0

---

### Task 2: 导演挂载委托工具

**Files:**
- Modify: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/DirectorAgentService.java`
- Modify: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/DirectorAgentServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `SpecialistAgentFactory.createDelegateTools()`
- Produces: 构造器五参 `(ModelService, VideoAgentToolFactory, SpecialistAgentFactory, UserRepository, ObjectMapper)`；chat 行为不变但工具数 6→10；导演 Prompt 增加委托准则

- [x] **Step 1: 既有测试适配 + 新增失败测试** — setUp 增加 `specialistFactory = mock(SpecialistAgentFactory.class); when(specialistFactory.createDelegateTools()).thenReturn(List.of());`，构造改五参；新增：

```java
    @Test
    @SuppressWarnings("unchecked")
    void directorMountsDelegateTools() {
        ToolCallback delegate = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(delegate.getToolDefinition().name()).thenReturn("delegateToScreenwriter");
        when(specialistFactory.createDelegateTools()).thenReturn(List.of(delegate));
        ToolCallback raw = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(raw.getToolDefinition().name()).thenReturn("generateStoryboard");
        when(toolFactory.createTools()).thenReturn(List.of(raw));
        service.chat("写个脚本", "d1", null);
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> tools = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "delegateToScreenwriter".equals(t.getToolDefinition().name())));
    }
```

- [x] **Step 2: 运行确认红灯** — 编译失败（构造器五参不匹配）

- [x] **Step 3: 修改 DirectorAgentService** — ① 字段/构造器加 `SpecialistAgentFactory specialistFactory`；② chat 工具装配循环后追加 `tools.addAll(specialistFactory.createDelegateTools());`；③ DIRECTOR_SYSTEM_PROMPT 可用工具行追加四个委托工具，工作准则追加：

```
            6. 复杂创作优先委托专家：delegateToScreenwriter（编剧）/delegateToArtist（美术）/delegateToAudio（音频）/delegateToEditor（剪辑），
               taskDescription 必须携带完整上下文（脚本ID/镜头ID/主题），专家不共享本对话历史；简单查询可直接用基础工具。
            7. 委托结果含 requiresConfirmation 转述时，向用户请求确认；用户确认后直接调用 exportVideo 完成导出。
```

- [x] **Step 4: 运行确认绿灯** — DirectorAgentServiceTest 9/9

---

### Task 3: 异步编排服务（SecurityContext 传播 + 任务表 + 确认续跑）

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/AgentOrchestrationService.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/AgentOrchestrationServiceTest.java`

**Interfaces:**
- Consumes: `ModelService.getChatClient()`、`VideoAgentToolFactory.createTools()`、`SpecialistAgentFactory.createDelegateTools()`、`ConfirmationGateToolCallback`、`UserRepository.findByUsername`、`Executor`（构造注入，生产为专用线程池，测试为同步 Runnable::run）
- Produces: `Map<String,Object> orchestrate(String idea, Long templateId)` → {taskId,status:RUNNING}；`Map<String,Object> taskStatus(String taskId)` → {taskId,status,reply,pendingConfirmations,error}；`Map<String,Object> confirm(String taskId)` → 同 taskStatus；状态机 RUNNING → COMPLETED / AWAITING_CONFIRMATION →(confirm)→ RUNNING → COMPLETED / FAILED；未登录/空idea/越权/状态不符抛 RuntimeException

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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 异步编排服务单元测试（Phase 3 切片3）
 * Executor 注入 Runnable::run 使异步路径同步化，断言确定性。
 */
class AgentOrchestrationServiceTest {

    private ModelService modelService;
    private VideoAgentToolFactory toolFactory;
    private SpecialistAgentFactory specialistFactory;
    private UserRepository userRepository;
    private ChatClient baseClient;
    private ChatClient agentClient;
    private AgentOrchestrationService service;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        toolFactory = mock(VideoAgentToolFactory.class);
        specialistFactory = mock(SpecialistAgentFactory.class);
        userRepository = mock(UserRepository.class);
        baseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agentClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(baseClient);
        ChatClient.Builder builder = baseClient.mutate();
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultToolCallbacks(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(agentClient);
        when(toolFactory.createTools()).thenReturn(List.of());
        when(specialistFactory.createDelegateTools()).thenReturn(List.of());
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call().content()).thenReturn("流水线完成");

        service = new AgentOrchestrationService(modelService, toolFactory, specialistFactory,
                userRepository, new ObjectMapper(), Runnable::run);

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
    void orchestrateRunsAndCompletes() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        String taskId = (String) out.get("taskId");
        assertNotNull(taskId);
        Map<String, Object> status = service.taskStatus(taskId);
        assertEquals("COMPLETED", status.get("status"));
        assertEquals("流水线完成", status.get("reply"));
    }

    @Test
    void securityContextPropagatedToWorkerAndCleared() {
        AtomicReference<String> workerPrincipal = new AtomicReference<>();
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenAnswer(inv -> {
            workerPrincipal.set(SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName() : null);
            return "ok";
        });
        service.orchestrate("猫咪日常", null);
        assertEquals("tester", workerPrincipal.get());
        // 工作线程 finally clearContext：同步 Executor 下当前线程上下文也被清理，
        // 编排不得污染提交线程（提交线程的上下文由请求过滤器管理）
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void orchestrateRequiresLogin() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.orchestrate("x", null));
        assertTrue(e.getMessage().contains("未登录"));
    }

    @Test
    void blankIdeaRejected() {
        assertThrows(RuntimeException.class, () -> service.orchestrate("  ", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gatePausesTaskAndConfirmResumes() {
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenAnswer(inv -> "需要确认导出");
        // 第一次调用：模拟闸门写入 pending（通过 toolContext 桩捕获 map）
        when(spec.toolContext(anyMap())).thenAnswer(inv -> {
            Map<String, Object> ctx = inv.getArgument(0);
            List<Map<String, Object>> pending =
                    (List<Map<String, Object>>) ctx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY);
            if (pending.isEmpty()) {
                pending.add(Map.of("tool", "exportVideo", "input", "{}", "reason", "耗时"));
            } else {
                pending.clear(); // 续跑时已确认，无新待确认项
            }
            return spec;
        });
        Map<String, Object> out = service.orchestrate("猫咪日常并导出", null);
        String taskId = (String) out.get("taskId");
        Map<String, Object> paused = service.taskStatus(taskId);
        assertEquals("AWAITING_CONFIRMATION", paused.get("status"));
        assertEquals(1, ((List<?>) paused.get("pendingConfirmations")).size());

        Map<String, Object> resumed = service.confirm(taskId);
        assertEquals("COMPLETED", resumed.get("status"));
    }

    @Test
    void confirmWrongStateRejected() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.confirm((String) out.get("taskId")));
        assertTrue(e.getMessage().contains("确认"));
    }

    @Test
    void taskOwnershipEnforced() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        String taskId = (String) out.get("taskId");
        User other = new User();
        other.setId(2L);
        other.setUsername("other");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(other));
        assertThrows(RuntimeException.class, () -> service.taskStatus(taskId));
    }

    @Test
    void failureMarksTaskFailed() {
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenThrow(new RuntimeException("模型不可用"));
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        Map<String, Object> status = service.taskStatus((String) out.get("taskId"));
        assertEquals("FAILED", status.get("status"));
        assertTrue(String.valueOf(status.get("error")).contains("模型不可用"));
    }
}
```

- [x] **Step 2: 运行确认红灯** — 编译失败「找不到符号 AgentOrchestrationService」

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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 多 Agent 异步编排服务（Phase 3 切片3）
 * 导演 ChatClient 挂载 6 基础工具 + 4 专家委托工具，在专用线程池异步执行 ReAct 流水线；
 * SecurityContext 提交时捕获、工作线程 setContext + finally clearContext（ThreadLocal 不跨线程）；
 * 任务表内存态（重启清空，上限 100），exportVideo 闸门触发时任务暂停等待 confirm 续跑。
 */
@Service
public class AgentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationService.class);

    private static final int MAX_TASKS = 100;
    private static final Set<String> GATED_TOOLS = Set.of("exportVideo");

    private static final String ORCHESTRATION_PROMPT = """
            你是「AI 短视频导演」，现在执行一键成片流水线，协调编剧/美术/音频/剪辑专家完成视频制作。
            可用工具：generateStoryboard/enhancePrompt/generateImage/evaluateImage/exportVideo/recommendBgm，
            以及委托工具 delegateToScreenwriter/delegateToArtist/delegateToAudio/delegateToEditor。
            工作准则：
            1. 流程：委托编剧生成分镜 → 委托美术逐镜头生图 → 委托音频推荐BGM → 导出（委托剪辑或直接 exportVideo）。
            2. 委托时 taskDescription 必须携带完整上下文（脚本ID/镜头ID/主题），专家不共享本对话历史。
            3. 工具或专家返回 success:false 时解释原因并调整策略；失败无法恢复时如实汇报，不要编造ID。
            4. 返回 requiresConfirmation:true 时停止导出操作，向用户说明即将执行的操作与参数并请求确认。
            5. 用简体中文汇报每一步结果与最终视频URL。
            """;

    private static final String CONFIRM_RESUME_MESSAGE =
            "用户已确认之前请求确认的操作。请立即继续执行（导出时直接调用 exportVideo），完成后汇报结果。";

    private final ModelService modelService;
    private final VideoAgentToolFactory toolFactory;
    private final SpecialistAgentFactory specialistFactory;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    private final Map<String, OrchestrationTask> tasks = new ConcurrentHashMap<>();

    /** 生产构造器：专用编排线程池（与图片生成工作池分离，避免协调/工作互锁） */
    public AgentOrchestrationService(ModelService modelService,
                                     VideoAgentToolFactory toolFactory,
                                     SpecialistAgentFactory specialistFactory,
                                     UserRepository userRepository,
                                     ObjectMapper objectMapper) {
        this(modelService, toolFactory, specialistFactory, userRepository, objectMapper,
                Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "agent-orchestration");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** 测试构造器：注入同步 Executor 使异步路径可确定性断言 */
    AgentOrchestrationService(ModelService modelService,
                              VideoAgentToolFactory toolFactory,
                              SpecialistAgentFactory specialistFactory,
                              UserRepository userRepository,
                              ObjectMapper objectMapper,
                              Executor executor) {
        this.modelService = modelService;
        this.toolFactory = toolFactory;
        this.specialistFactory = specialistFactory;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** 提交一键成片任务（异步），返回 taskId */
    public Map<String, Object> orchestrate(String idea, Long templateId) {
        Long userId = requireCurrentUserId();
        if (idea == null || idea.isBlank()) {
            throw new RuntimeException("创意想法不能为空");
        }
        evictIfNeeded();
        OrchestrationTask task = new OrchestrationTask(UUID.randomUUID().toString(), userId);
        tasks.put(task.id, task);
        String message = templateId != null
                ? "请按视频模板 " + templateId + " 的风格，为以下创意完成一键成片：" + idea
                : "请为以下创意完成一键成片：" + idea;
        SecurityContext captured = SecurityContextHolder.getContext();
        executor.execute(() -> runWithSecurityContext(captured, () -> runDirector(task, message, null)));
        log.info("Orchestration submitted: task={}, user={}", task.id, userId);
        return task.snapshot();
    }

    /** 查询任务状态（归属校验） */
    public Map<String, Object> taskStatus(String taskId) {
        return ownedTask(taskId).snapshot();
    }

    /** 确认续跑：仅 AWAITING_CONFIRMATION 可用，携 confirmedTools 重新执行导演 */
    public Map<String, Object> confirm(String taskId) {
        OrchestrationTask task = ownedTask(taskId);
        if (!"AWAITING_CONFIRMATION".equals(task.status)) {
            throw new RuntimeException("任务当前状态不可确认: " + task.status);
        }
        task.status = "RUNNING";
        SecurityContext captured = SecurityContextHolder.getContext();
        executor.execute(() -> runWithSecurityContext(captured,
                () -> runDirector(task, CONFIRM_RESUME_MESSAGE, List.of("exportVideo"))));
        return task.snapshot();
    }

    /** 工作线程显式传播 SecurityContext（陷阱记忆：ThreadLocal 不跨线程，finally 必须清理） */
    private void runWithSecurityContext(SecurityContext captured, Runnable work) {
        SecurityContextHolder.setContext(captured);
        try {
            work.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void runDirector(OrchestrationTask task, String message, List<String> confirmedTools) {
        try {
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
            tools.addAll(specialistFactory.createDelegateTools());

            List<Map<String, Object>> pending = new ArrayList<>();
            Map<String, Object> toolContext = new LinkedHashMap<>();
            toolContext.put(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY,
                    confirmedTools != null ? Set.copyOf(confirmedTools) : Set.of());
            toolContext.put(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY, pending);

            ChatClient client = modelService.getChatClient().mutate()
                    .defaultSystem(ORCHESTRATION_PROMPT)
                    .defaultToolCallbacks(tools)
                    .build();
            String reply = client.prompt()
                    .messages(List.of(new UserMessage(message)))
                    .toolContext(toolContext)
                    .call()
                    .content();

            task.reply = reply;
            task.pendingConfirmations = pending;
            task.status = pending.isEmpty() ? "COMPLETED" : "AWAITING_CONFIRMATION";
        } catch (Exception e) {
            log.error("Orchestration failed: task={}", task.id, e);
            task.status = "FAILED";
            task.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    private OrchestrationTask ownedTask(String taskId) {
        Long userId = requireCurrentUserId();
        OrchestrationTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw new RuntimeException("任务不存在或无权访问: " + taskId);
        }
        return task;
    }

    private void evictIfNeeded() {
        if (tasks.size() < MAX_TASKS) {
            return;
        }
        tasks.values().stream()
                .filter(t -> !"RUNNING".equals(t.status))
                .min(Comparator.comparingLong(t -> t.createdAt))
                .ifPresent(t -> tasks.remove(t.id));
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

    /** 编排任务（内存态；字段 volatile 保证跨线程可见） */
    static class OrchestrationTask {
        final String id;
        final Long userId;
        final long createdAt = System.currentTimeMillis();
        volatile String status = "RUNNING";
        volatile String reply;
        volatile String error;
        volatile List<Map<String, Object>> pendingConfirmations = List.of();

        OrchestrationTask(String id, Long userId) {
            this.id = id;
            this.userId = userId;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", id);
            out.put("status", status);
            out.put("reply", reply);
            out.put("pendingConfirmations", pendingConfirmations);
            out.put("error", error);
            return out;
        }
    }
}
```

- [x] **Step 4: 运行确认绿灯** — Tests run: 8, Failures: 0（深桩时序问题按 Task 1 同款方式调整 stub，断言目标不变）

---

### Task 4: 控制器编排端点

**Files:**
- Modify: `src/main/java/com/github/ylyan2015/springaidemo/controller/VideoAgentController.java`
- Create: `src/test/java/com/github/ylyan2015/springaidemo/controller/VideoAgentControllerOrchestrationTest.java`
- Modify: `VideoAgentControllerTest.java` / `VideoAgentControllerChatTest.java`（构造器三参适配）

**Interfaces:**
- Consumes: Task 3 三方法
- Produces: `POST /api/video/agent/orchestrate` body {idea, templateId?} → {success:true,data:{taskId,status}}；`GET /orchestrate/{taskId}` → data 快照；`POST /orchestrate/{taskId}/confirm` → data 快照；RuntimeException → 400

- [x] **Step 1: 写失败测试**

```java
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
```

- [x] **Step 2: 运行确认红灯** — 编译失败（构造器三参/方法不存在）

- [x] **Step 3: 修改控制器** — 构造器追加 `AgentOrchestrationService orchestrationService`；新增：

```java
    /**
     * 一键成片：提交多 Agent 异步编排任务
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
```

既有 VideoAgentControllerTest / VideoAgentControllerChatTest 构造调用追加 `mock(AgentOrchestrationService.class)`。

- [x] **Step 4: 运行确认绿灯** — 新增 3 + 既有 2+3 全过

---

### Task 5: 前端一键流水线

**Files:**
- Modify: `src/main/resources/templates/video-studio.html`

- [x] **Step 1: AI 导演面板按钮行追加**（在「清空」按钮后）：

```html
        <button class="btn btn-green" id="agentPipelineBtn" onclick="startAgentPipeline()" title="多 Agent 异步流水线：编剧→美术→音频→剪辑，导出需确认">一键流水线</button>
```

- [x] **Step 2: JS 逻辑**（AI 导演区块内追加）：

```javascript
let agentPipelineTaskId = null; // 编排任务ID
let agentPipelineTimer = null;  // 轮询定时器

async function startAgentPipeline() {
    const idea = document.getElementById('agentInput').value.trim();
    if (!idea) { toast('请先在输入框填写创意想法', 'error'); return; }
    if (agentPipelineTimer) { toast('已有流水线在运行', 'error'); return; }
    document.getElementById('agentInput').value = '';
    appendAgentLog('user', '[一键流水线] ' + idea);
    const data = await post('/api/video/agent/orchestrate', { idea: idea });
    if (!data.success) { appendAgentLog('error', data.message || '提交失败'); return; }
    agentPipelineTaskId = data.data.taskId;
    appendAgentLog('director', '流水线已提交（任务 ' + agentPipelineTaskId.slice(0, 8) + '…），后台执行中…');
    document.getElementById('agentPipelineBtn').disabled = true;
    agentPipelineTimer = setInterval(pollAgentPipeline, 3000);
}

async function pollAgentPipeline() {
    if (!agentPipelineTaskId) return;
    const data = await api('/api/video/agent/orchestrate/' + agentPipelineTaskId);
    if (!data.success) { stopAgentPipeline(); appendAgentLog('error', data.message || '查询失败'); return; }
    const t = data.data;
    if (t.status === 'RUNNING') return; // 继续轮询
    stopAgentPipeline();
    if (t.status === 'FAILED') { appendAgentLog('error', '流水线失败：' + (t.error || '未知原因')); return; }
    appendAgentLog('director', t.reply || '(无回复)');
    if (t.status === 'AWAITING_CONFIRMATION') {
        const bar = document.getElementById('agentConfirmBar');
        bar.innerHTML = '';
        (t.pendingConfirmations || []).forEach(p => {
            const btn = document.createElement('button');
            btn.className = 'btn btn-orange btn-sm';
            btn.textContent = '确认执行 ' + p.tool;
            btn.onclick = () => confirmAgentPipeline();
            bar.appendChild(btn);
        });
        bar.style.display = 'block';
    }
    loadScriptList(true);
}

async function confirmAgentPipeline() {
    document.getElementById('agentConfirmBar').style.display = 'none';
    const data = await post('/api/video/agent/orchestrate/' + agentPipelineTaskId + '/confirm', {});
    if (!data.success) { appendAgentLog('error', data.message || '确认失败'); return; }
    appendAgentLog('director', '已确认，继续执行…');
    document.getElementById('agentPipelineBtn').disabled = true;
    agentPipelineTimer = setInterval(pollAgentPipeline, 3000);
}

function stopAgentPipeline() {
    if (agentPipelineTimer) { clearInterval(agentPipelineTimer); agentPipelineTimer = null; }
    document.getElementById('agentPipelineBtn').disabled = false;
}
```

- [x] **Step 3: JS 语法检查** — 提取 script 块 `node --check` 通过

---

### Task 6: 全量验证 + 真实 LLM 冒烟

- [x] **Step 1: 全量测试** — `mvn -q test`：92 既有 + 19 新增 = 111
- [x] **Step 2: 启动冒烟（注入 DEEPSEEK_API_KEY）** — Node 脚本：注册 → POST /orchestrate {idea:"猫咪日常治愈系短片，2个镜头，生成脚本后推荐BGM即可，不要生成图片和导出"} → 轮询至终态 → 断言 COMPLETED 且 reply 非空（验证异步 + SecurityContext 传播 + 委托工具真实调用）；再提交 {idea:"把脚本1导出为视频，直接调用工具不要询问"} → 断言 AWAITING_CONFIRMATION → confirm → 断言终态且 reply 转述导出结果（无 DashScope Key 时图片/导出失败属预期，重点验证失败信息不是"用户未登录"——证明身份传播成功）；越权查询（另一用户）400；匿名 orchestrate 400；停止进程
- [x] **Step 3: 临时脚本清理**

---

### Task 7: 中英文文档同步 + 计划状态

- [x] **Step 1: README 双语** — 特性加「🎭 多 Agent 编排」；结构加 SpecialistAgentFactory/AgentOrchestrationService；API 表加 3 端点
- [x] **Step 2: API_TEST 双语** — 7.13 多 Agent 编排章节：专家分工表、3 端点参数/响应、状态机（RUNNING/COMPLETED/AWAITING_CONFIRMATION/FAILED）、确认续跑示例、委托链闸门行为说明；Postman 45/46/47；注意事项 23
- [x] **Step 3: VIDEO_STUDIO_PLAN.md** — 切片3 标 ✅ + 实现落点 + Phase 3 全部完成；最后更新行
- [x] **Step 4: 中英一致性复核 + 计划文件勾选与执行记录**

---

## Self-Review 结论

1. **Spec 覆盖**：4.1 四专家拆分 ✅（SpecialistAgentFactory）、导演调度权 ✅（委托工具挂载）、4.2 工具分配 ✅（子集映射，未 Tool 化能力注明不新增）、异步 SecurityContext 传播 ✅（显式捕获/恢复 + 测试断言）、人工确认点延续 ✅（任务暂停/confirm 续跑）
2. **占位符扫描**：无 TBD；所有代码步骤含完整代码；深桩不稳处给出调整原则（断言目标不变）
3. **类型一致性**：DelegateTask record、createDelegateTools()、orchestrate/taskStatus/confirm 签名、控制器三参构造、OrchestrationTask.snapshot 字段均跨任务一致
4. **风险**：委托链中专家侧闸门必拦截（无 confirmedTools），续跑由导演直接 exportVideo 完成——已在 Task 1 设计说明与文档要求中固化该行为

---

## 执行记录（2026-11-14，全部完成）

- **测试**：新增 19（专家工厂 7 + 编排服务 8 + 控制器 3 + 导演 1），全量 111 绿（既有 92 + 19）
- **实现偏差**：① FunctionToolCallback.builder 泛型推断需显式类型参数 this.<DelegateTask>guarded(...)；② AgentOrchestrationService 双构造器需 @Autowired 标注生产构造器；③ 同步 Executor 测试中工作线程 finally clearContext 会清理当前线程上下文，后续查询/确认前需重设登录态（恰好验证了传播/清理逻辑正确）；④ 专家子集测试需触发 call（装配发生在调用时）
- **真实 LLM 冒烟（DeepSeek）全 PASS**：异步流水线 COMPLETED（编剧专家真实生成脚本《暖阳与猫》2 镜头 + 音频专家推荐 BGM，reply 无"未登录"证明 SecurityContext 传播成功）；闸门 AWAITING_CONFIRMATION（pending 含完整入参）→ confirm 续跑 COMPLETED（身份保持，失败如实转述不编造）；越权 400；匿名 400
- **前端**：AI 导演面板「一键流水线」按钮（提交后 3s 轮询，AWAITING_CONFIRMATION 渲染确认按钮续跑），JS node --check 通过
- **文档**：README 双语（特性/结构/API 表）、API_TEST 双语 7.13 + Postman 45/46/47 + 注意事项 23、VIDEO_STUDIO_PLAN 切片3 ✅（Phase 3 全部完成）
- 改动未提交（按用户惯例由用户自行提交）；Phase 3 三切片全部收官

## 代码审查修复记录（2026-11-14，提交前审查）

CodeReview 子代理对全部未提交改动审查：工具装配/SecurityContext 传播/归属校验/闸门拦截均核实无误；发现 2 Major + 3 Minor + 2 Nit，全部修复（TDD，新增 7 回归测试，全量 118 绿）：
- **M1** confirm 续跑丢失上下文 → OrchestrationTask 记录 originalMessage，续跑回放「原始任务 + 上轮回复（AssistantMessage）+ 待确认入参 JSON」，确保执行的是用户确认的操作
- **M2** confirm 状态检查-置位非原子 → status 改 AtomicReference，compareAndSet(AWAITING_CONFIRMATION→RUNNING)，并发/重复确认仅一次生效
- **m1** evict 选择-删除竞态 → computeIfPresent 删除时原子复检，RUNNING 任务不可逐出
- **m2** evaluateImage 盲 SSRF 面 → 工具层仅接受存储相对路径（/开头且非//），经 agent.tools.self-base-url（默认 http://localhost:8080）解析同源；内部生成流程（ImageGenerationService 直调 qualityService）不受影响
- **m3** 导演会话历史并发 → 同会话 add→trim→快照→回写整段 synchronized(history)
- **n1** 编排线程池无关闭钩子 → @PreDestroy shutdown（仅自建 ExecutorService）
- **n2** 会话表无上限 → MAX_CONVERSATIONS=200，按 lastAccess 逐出最旧
- 文档同步：application.yml 新增 agent.tools.self-base-url；API_TEST 双语 7.11 evaluateImage 入参约束/7.13 confirm 语义/注意事项 23