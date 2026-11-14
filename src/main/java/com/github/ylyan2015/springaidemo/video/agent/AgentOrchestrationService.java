package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
    @Autowired
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
        task.originalMessage = message;
        SecurityContext captured = SecurityContextHolder.getContext();
        executor.execute(() -> runWithSecurityContext(captured,
                () -> runDirector(task, List.of(new UserMessage(message)), null)));
        log.info("Orchestration submitted: task={}, user={}", task.id, userId);
        return task.snapshot();
    }

    /** 查询任务状态（归属校验） */
    public Map<String, Object> taskStatus(String taskId) {
        return ownedTask(taskId).snapshot();
    }

    /** 确认续跑：仅 AWAITING_CONFIRMATION 可用（CAS 原子迁移防并发双重执行），携 confirmedTools 重新执行导演 */
    public Map<String, Object> confirm(String taskId) {
        OrchestrationTask task = ownedTask(taskId);
        if (!task.status.compareAndSet("AWAITING_CONFIRMATION", "RUNNING")) {
            throw new RuntimeException("任务当前状态不可确认: " + task.status.get());
        }
        // 续跑回放完整上下文：原始任务 + 上轮回复 + 待确认操作入参，确保执行的是用户确认的那个操作
        List<Message> messages = new ArrayList<>();
        if (task.originalMessage != null) {
            messages.add(new UserMessage(task.originalMessage));
        }
        if (task.reply != null) {
            messages.add(new AssistantMessage(task.reply));
        }
        String pendingJson;
        try {
            pendingJson = objectMapper.writeValueAsString(task.pendingConfirmations);
        } catch (Exception e) {
            pendingJson = String.valueOf(task.pendingConfirmations);
        }
        messages.add(new UserMessage(CONFIRM_RESUME_MESSAGE + "\n待确认操作及入参：" + pendingJson));
        SecurityContext captured = SecurityContextHolder.getContext();
        executor.execute(() -> runWithSecurityContext(captured,
                () -> runDirector(task, messages, List.of("exportVideo"))));
        return task.snapshot();
    }

    /** 容器关闭时优雅终止编排线程池（仅关闭自建的池，测试注入的 Executor 不受影响） */
    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdown();
        }
    }

    /** 工作线程显式传播 SecurityContext（ThreadLocal 不跨线程，finally 必须清理） */
    private void runWithSecurityContext(SecurityContext captured, Runnable work) {
        SecurityContextHolder.setContext(captured);
        try {
            work.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void runDirector(OrchestrationTask task, List<Message> messages, List<String> confirmedTools) {
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
                    .messages(messages)
                    .toolContext(toolContext)
                    .call()
                    .content();

            task.reply = reply;
            task.pendingConfirmations = pending;
            task.status.set(pending.isEmpty() ? "COMPLETED" : "AWAITING_CONFIRMATION");
        } catch (Exception e) {
            log.error("Orchestration failed: task={}", task.id, e);
            task.status.set("FAILED");
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
                .filter(t -> !"RUNNING".equals(t.status.get()))
                .min(Comparator.comparingLong(t -> t.createdAt))
                // 删除时原子复检：选中后可能被 confirm 置为 RUNNING，运行中任务不可逐出
                .ifPresent(t -> tasks.computeIfPresent(t.id,
                        (k, v) -> "RUNNING".equals(v.status.get()) ? v : null));
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

    /** 编排任务（内存态；status 用 AtomicReference 保证状态迁移原子性，其余 volatile 保证可见性） */
    static class OrchestrationTask {
        final String id;
        final Long userId;
        final long createdAt = System.currentTimeMillis();
        final AtomicReference<String> status = new AtomicReference<>("RUNNING");
        volatile String originalMessage;
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
            out.put("status", status.get());
            out.put("reply", reply);
            out.put("pendingConfirmations", pendingConfirmations);
            out.put("error", error);
            return out;
        }
    }
}