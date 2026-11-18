package com.github.ylyan2015.springaidemo.video.agent;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.BgmService;
import com.github.ylyan2015.springaidemo.service.ImageGenerationService;
import com.github.ylyan2015.springaidemo.service.ImagePromptOptimizerService;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.StoryCharacterService;
import com.github.ylyan2015.springaidemo.service.VideoExportService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 短视频 Agent 工具工厂（Phase 3 切片1）
 * 用 FunctionToolCallback 将现有服务包装为 LLM 可调用工具：
 * 工具在请求线程内同步执行（SecurityContext 可用）；
 * 任何异常转为 {success:false, message} 结果，让 LLM 能感知失败并调整策略。
 * 切片2 将把 createTools() 注入导演 Agent 的 ChatClient.defaultTools(...)。
 */
@Component
public class VideoAgentToolFactory {

    private static final Logger log = LoggerFactory.getLogger(VideoAgentToolFactory.class);

    private final StoryScriptService storyScriptService;
    private final VideoTemplateService videoTemplateService;
    private final ImagePromptOptimizerService promptOptimizerService;
    private final ImageGenerationService imageGenerationService;
    private final ImageQualityService qualityService;
    private final VideoExportService videoExportService;
    private final BgmService bgmService;
    private final StoryCharacterService storyCharacterService;
    private final com.github.ylyan2015.springaidemo.service.VideoGenerationService videoGenerationService;
    private final com.github.ylyan2015.springaidemo.service.ShotCandidateService shotCandidateService;

    /** 本服务访问基址：evaluateImage 仅接受存储相对路径，解析为同源绝对 URL（SSRF 防护） */
    @Value("${agent.tools.self-base-url:http://localhost:8080}")
    private String selfBaseUrl;

    public VideoAgentToolFactory(StoryScriptService storyScriptService,
                                 VideoTemplateService videoTemplateService,
                                 ImagePromptOptimizerService promptOptimizerService,
                                 ImageGenerationService imageGenerationService,
                                 ImageQualityService qualityService,
                                 VideoExportService videoExportService,
                                 BgmService bgmService,
                                 StoryCharacterService storyCharacterService,
                                 com.github.ylyan2015.springaidemo.service.VideoGenerationService videoGenerationService,
                                 com.github.ylyan2015.springaidemo.service.ShotCandidateService shotCandidateService) {
        this.storyScriptService = storyScriptService;
        this.videoTemplateService = videoTemplateService;
        this.promptOptimizerService = promptOptimizerService;
        this.imageGenerationService = imageGenerationService;
        this.qualityService = qualityService;
        this.videoExportService = videoExportService;
        this.bgmService = bgmService;
        this.storyCharacterService = storyCharacterService;
        this.videoGenerationService = videoGenerationService;
        this.shotCandidateService = shotCandidateService;
    }

    /** 构建全部 9 个工具（每次调用新建，无状态） */
    public List<ToolCallback> createTools() {
        return List.of(
                FunctionToolCallback.builder("generateStoryboard", guarded(this::generateStoryboard))
                        .description("根据创意想法生成分镜脚本并保存，返回脚本ID与镜头列表；可选模板ID按模板风格生成")
                        .inputType(AgentToolRequests.GenerateStoryboard.class).build(),
                FunctionToolCallback.builder("enhancePrompt", guarded(this::enhancePrompt))
                        .description("将中文场景描述优化为高质量英文图像提示词，可选视觉风格")
                        .inputType(AgentToolRequests.EnhancePrompt.class).build(),
                FunctionToolCallback.builder("generateImage", guarded(this::generateImage))
                        .description("为指定脚本的单个镜头生成图片（含失败重试与可选质量评估），返回镜头状态与图片URL")
                        .inputType(AgentToolRequests.GenerateImage.class).build(),
                FunctionToolCallback.builder("evaluateImage", guarded(this::evaluateImage))
                        .description("评估图片质量：清晰度/相关性/美学三维0-10分与综合分、改进建议；未配多模态API时为规则评估；imageUrl 仅接受本服务存储相对路径（如 /images/…）")
                        .inputType(AgentToolRequests.EvaluateImage.class).build(),
                FunctionToolCallback.builder("exportVideo", guarded(this::exportVideo))
                        .description("将脚本合成为视频：preview=true出480p带水印预览，否则720p/1080p高清导出；可选BGM/字幕/Logo；aspectRatio 缺省跟随脚本画幅（无需预填16:9）。耗时较长")
                        .inputType(AgentToolRequests.ExportVideo.class).build(),
                FunctionToolCallback.builder("recommendBgm", guarded(this::recommendBgm))
                        .description("按视频主题情感智能推荐背景音乐，返回推荐分类与曲目")
                        .inputType(AgentToolRequests.RecommendBgm.class).build(),
                FunctionToolCallback.builder("generateCharacters", guarded(this::generateCharacters))
                        .description("返回脚本的角色卡列表（角色卡随分镜脚本自动生成）：名字/英文特征表/性格/声线/定妆参考图，供一致性出图与配音使用")
                        .inputType(AgentToolRequests.GenerateCharacters.class).build(),
                FunctionToolCallback.builder("generateShotVideo", guarded(this::generateShotVideo))
                        .description("为指定镜头图生视频（i2v，含首尾帧连续性），返回镜头视频状态与URL；video.generator=none 时失败")
                        .inputType(AgentToolRequests.GenerateShotVideo.class).build(),
                FunctionToolCallback.builder("inpaintImage", guarded(this::inpaintImage))
                        .description("对镜头图片框选局部重绘（坐标 0-1 相对值，需 wan27 生成器）；重绘产物入候选不覆盖主图，选择由用户完成")
                        .inputType(AgentToolRequests.InpaintImage.class).build());
    }

    /** 统一异常包装：工具失败返回 JSON 而非抛异常，便于 LLM 感知与重试决策 */
    private <I> Function<I, Map<String, Object>> guarded(Function<I, Map<String, Object>> fn) {
        return input -> {
            try {
                return fn.apply(input);
            } catch (Exception e) {
                log.warn("Agent tool execution failed: {}", e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return err;
            }
        };
    }

    private Map<String, Object> generateStoryboard(AgentToolRequests.GenerateStoryboard req) {
        if (req.idea() == null || req.idea().isBlank()) {
            throw new IllegalArgumentException("idea 不能为空");
        }
        StoryScript script = req.templateId() != null
                ? videoTemplateService.applyTemplate(req.templateId(), req.idea())
                : storyScriptService.generateAndSaveScript(req.idea());
        Map<String, Object> out = new LinkedHashMap<>(storyScriptService.scriptToMap(script));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> enhancePrompt(AgentToolRequests.EnhancePrompt req) {
        Map<String, Object> out = new LinkedHashMap<>(
                promptOptimizerService.enhancePrompt(req.sceneDescription(), req.style()));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> generateImage(AgentToolRequests.GenerateImage req) {
        StoryShot shot = imageGenerationService.generateForShot(req.scriptId(), req.shotId(), req.style());
        Map<String, Object> out = new LinkedHashMap<>(storyScriptService.shotToMap(shot));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> evaluateImage(AgentToolRequests.EvaluateImage req) {
        // SSRF 防护：imageUrl 由 LLM/用户控制，仅允许本服务存储相对路径，拒绝任意绝对 URL/协议
        String imageUrl = req.imageUrl();
        if (imageUrl == null || !imageUrl.startsWith("/") || imageUrl.startsWith("//")) {
            throw new IllegalArgumentException("imageUrl 仅支持本服务存储相对路径（如 /images/…），不接受外部 URL");
        }
        ImageQualityScore score = qualityService.evaluate(selfBaseUrl + imageUrl, req.prompt());
        Map<String, Object> out = new LinkedHashMap<>();
        if (score == null) {
            out.put("success", false);
            out.put("message", "图片下载失败，无法评估");
            return out;
        }
        out.put("success", true);
        out.put("overallScore", score.getOverallScore());
        out.put("clarityScore", score.getClarityScore());
        out.put("relevanceScore", score.getRelevanceScore());
        out.put("aestheticScore", score.getAestheticScore());
        out.put("feedback", score.getFeedback());
        out.put("llmEvaluated", score.isLlmEvaluated());
        return out;
    }

    private Map<String, Object> exportVideo(AgentToolRequests.ExportVideo req) {
        boolean preview = Boolean.TRUE.equals(req.preview());
        // null 透传：由 doExport fallback 脚本 aspectRatio（双 null 仍为存量 16:9），预填会屏蔽竖屏脚本
        String aspect = req.aspectRatio();
        boolean subtitle = Boolean.TRUE.equals(req.subtitle());
        String lang = req.subtitleLanguage() != null ? req.subtitleLanguage() : "zh";
        Map<String, Object> out;
        if (preview) {
            out = videoExportService.exportPreview(req.scriptId(), aspect, req.bgmId(), subtitle, lang);
        } else {
            String resolution = req.resolution() != null ? req.resolution() : "720p";
            out = videoExportService.exportHd(req.scriptId(), resolution, aspect, req.bgmId(),
                    subtitle, Boolean.TRUE.equals(req.withLogo()), lang);
        }
        Map<String, Object> result = new LinkedHashMap<>(out);
        result.put("success", true);
        return result;
    }

    private Map<String, Object> recommendBgm(AgentToolRequests.RecommendBgm req) {
        Map<String, Object> out = new LinkedHashMap<>(
                bgmService.recommendBgm(req.theme(), bgmService.requireCurrentUserId()));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> generateCharacters(AgentToolRequests.GenerateCharacters req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("characters", storyCharacterService.listByScript(req.scriptId()));
        return out;
    }

    private Map<String, Object> generateShotVideo(AgentToolRequests.GenerateShotVideo req) {
        StoryShot shot = videoGenerationService.generateForShot(req.scriptId(), req.shotId());
        Map<String, Object> out = new LinkedHashMap<>(storyScriptService.shotToMap(shot));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> inpaintImage(AgentToolRequests.InpaintImage req) {
        double[] rect = new double[]{req.x(), req.y(), req.w(), req.h()};
        com.github.ylyan2015.springaidemo.entity.ShotCandidate candidate =
                shotCandidateService.inpaint(req.shotId(), rect, req.instruction());
        Map<String, Object> out = new LinkedHashMap<>(
                com.github.ylyan2015.springaidemo.service.ShotCandidateService.candidateToMap(candidate));
        out.put("success", true);
        return out;
    }
}