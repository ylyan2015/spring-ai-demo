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
import java.util.function.Function;

/**
 * 专家 Agent 工厂（Phase 3 切片3，规格 4.1/4.2）
 * 编剧/美术/音频/剪辑四专家：各自 System Prompt + 工具子集，
 * 以「专家即工具」delegateToXxx 供导演委托；专家调用无状态（单轮 ReAct），
 * 在调用线程内同步执行（异步编排时即编排工作线程，SecurityContext 已传播）。
 * 剪辑专家的 exportVideo 包确认闸门：专家侧无 confirmedTools 必被拦截，
 * 由专家转述 requiresConfirmation、导演汇总请求用户确认，confirm 续跑时导演直接调 exportVideo 放行。
 * TTS/混音已内嵌于 exportVideo 管线，不另设工具。
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
        return FunctionToolCallback.builder(toolName,
                        this.<AgentToolRequests.DelegateTask>guarded(input -> runSpecialist(specialist, input)))
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
        // 专家不共享导演历史：单轮任务描述；确认放行由导演在 confirm 续跑时直接调用自己的 exportVideo 完成
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

    /** 统一异常包装：委托失败返回 JSON 而非抛异常，便于导演感知并调整策略 */
    private <I> Function<I, Map<String, Object>> guarded(Function<I, Map<String, Object>> fn) {
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