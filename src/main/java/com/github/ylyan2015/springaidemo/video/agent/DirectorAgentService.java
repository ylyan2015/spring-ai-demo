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
 * 导演 Agent 服务（Phase 3 切片2，切片3 挂载专家委托工具）
 * ChatClient 挂载切片1 六工具 + 切片3 四专家委托工具（Spring AI 内建 tool-calling 循环即 ReAct），
 * exportVideo 经人工确认闸门；对话历史按 userId:sessionId 内存隔离（上限 20 条，重启清空）。
 * 同步执行于请求线程，SecurityContext 天然可用；异步编排见 AgentOrchestrationService。
 */
@Service
public class DirectorAgentService {

    private static final Logger log = LoggerFactory.getLogger(DirectorAgentService.class);

    /** 需要人工确认的工具（耗时/高成本） */
    private static final Set<String> GATED_TOOLS = Set.of("exportVideo");
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_CONVERSATIONS = 200;

    private static final String DIRECTOR_SYSTEM_PROMPT = """
            你是「AI 短视频导演」，负责协调编剧、美术、音频、剪辑能力，把用户的创意变成短视频。
            可用工具：generateStoryboard（生成分镜脚本）、enhancePrompt（优化提示词）、generateImage（单镜头生图）、
            evaluateImage（图片质量评分）、exportVideo（合成导出，需用户确认）、recommendBgm（配乐推荐），
            以及专家委托工具 delegateToScreenwriter（编剧）/delegateToArtist（美术）/delegateToAudio（音频）/delegateToEditor（剪辑）。
            工作准则：
            1. 典型流程：生成分镜 → 逐镜头生成图片 → （可选）质量评估 → 推荐配乐 → 导出；按用户意图灵活裁剪。
            2. 工具返回 success:false 时，向用户解释原因并调整策略，不要原样重复调用。
            3. 工具返回 requiresConfirmation:true 时，停止调用该工具，向用户说明即将执行的操作与参数，请求确认。
            4. 不要编造脚本ID/镜头ID，一律以工具返回值为准；缺少信息时先向用户询问。
            5. 用简体中文回复，简洁说明每一步做了什么、下一步建议。
            6. 复杂创作优先委托专家，taskDescription 必须携带完整上下文（脚本ID/镜头ID/主题），专家不共享本对话历史；简单查询可直接用基础工具。
            7. 委托结果含 requiresConfirmation 转述时，向用户请求确认；用户确认后直接调用 exportVideo 完成导出。
            """;

    private final ModelService modelService;
    private final VideoAgentToolFactory toolFactory;
    private final SpecialistAgentFactory specialistFactory;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** 对话历史：userId:sessionId → 消息列表（内存态，上限 MAX_CONVERSATIONS 逐出最旧） */
    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    /** 会话最近访问时间（逐出最旧会话用） */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    public DirectorAgentService(ModelService modelService,
                                VideoAgentToolFactory toolFactory,
                                SpecialistAgentFactory specialistFactory,
                                UserRepository userRepository,
                                ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.toolFactory = toolFactory;
        this.specialistFactory = specialistFactory;
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

        // 工具装配：gated 工具包确认闸门，另挂载四专家委托工具（切片3）
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
        lastAccess.put(key, System.currentTimeMillis());
        evictConversationsIfNeeded();

        log.info("Director agent chat: user={}, session={}, confirmed={}", userId, sid, confirmedTools);
        String reply;
        // 同会话串行化：add→trim→快照→回写整段加锁，防并发丢消息/历史错乱
        synchronized (history) {
            history.add(new UserMessage(message));
            trim(history);
            reply = client.prompt()
                    .messages(new ArrayList<>(history))
                    .toolContext(toolContext)
                    .call()
                    .content();
            history.add(new AssistantMessage(reply != null ? reply : ""));
            trim(history);
        }

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
            String key = userId + ":" + sessionId;
            conversations.remove(key);
            lastAccess.remove(key);
        }
    }

    /** 会话表超限时逐出最久未访问的会话，防内存无限增长 */
    private void evictConversationsIfNeeded() {
        if (conversations.size() <= MAX_CONVERSATIONS) {
            return;
        }
        lastAccess.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .ifPresent(k -> {
                    conversations.remove(k);
                    lastAccess.remove(k);
                });
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