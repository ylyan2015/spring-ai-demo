package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.ImagePromptOptimizer;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ImagePromptOptimizerRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.VisualStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图像提示词优化服务类
 * 负责将中文场景描述优化为高质量的英文 AI 图像生成提示词
 */
@Service
public class ImagePromptOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(ImagePromptOptimizerService.class);

    private final ModelService modelService;
    private final ImagePromptOptimizerRepository optimizerRepository;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository characterRepository;

    /** 提示词优化 System Prompt */
    private static final String SYSTEM_PROMPT = """
            你是一位专业的 AI 图像生成提示词工程师。
            将用户提供的场景描述转化为高质量的英文图像生成提示词。

            优化规则：
            1. 明确主体：谁/什么在画面中心
            2. 环境细节：场景、背景、天气、时间
            3. 光影氛围：光线方向、色温、整体氛围
            4. 构图镜头：视角（俯拍/平视/仰拍）、景别（远景/中景/特写）
            5. 艺术风格：写实/插画/3D渲染/电影质感
            6. 画质关键词：highly detailed, 8k, professional photography, cinematic lighting

            输出格式要求：
            - 直接输出一段英文提示词，100-200 词
            - 不要包含任何文字/对白内容
            - 不要输出任何中文解释或前缀
            - 如果用户指定了风格，按指定风格优化；否则默认"电影质感写实风格"
            """;

    public ImagePromptOptimizerService(ModelService modelService,
                                       ImagePromptOptimizerRepository optimizerRepository,
                                       StoryScriptRepository scriptRepository,
                                       StoryShotRepository shotRepository,
                                       UserRepository userRepository,
                                       com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository characterRepository) {
        this.modelService = modelService;
        this.optimizerRepository = optimizerRepository;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
    }

    // ==================== 单个提示词优化 ====================

    /**
     * 优化单个场景描述为高质量英文图像提示词
     *
     * @param sceneDescription 中文场景描述
     * @param style            视觉风格（可选，为 null 时使用默认风格）
     * @return 优化结果（含 enhancedPrompt 和 negativePrompt）
     */
    public Map<String, String> enhancePrompt(String sceneDescription, String style) {
        Long userId = getCurrentUserId();
        VisualStyle visualStyle = VisualStyle.fromName(style);

        log.info("Enhancing prompt for user {}: style='{}', description='{}'",
                userId, visualStyle.getDisplayName(), truncate(sceneDescription, 80));

        // 构建用户消息
        String userMessage = buildUserMessage(sceneDescription, visualStyle);

        try {
            // 调用 LLM 优化提示词
            String enhancedPrompt = modelService.getChatClient()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();

            if (enhancedPrompt == null || enhancedPrompt.isBlank()) {
                throw new RuntimeException("LLM 返回空结果");
            }

            // 清理可能的多余格式（如 Markdown 代码块包裹）
            enhancedPrompt = cleanPromptOutput(enhancedPrompt);

            // 构建结果
            Map<String, String> result = new LinkedHashMap<>();
            result.put("enhancedPrompt", enhancedPrompt);
            result.put("negativePrompt", visualStyle.getNegativePrompt());
            result.put("style", visualStyle.name());
            result.put("styleName", visualStyle.getDisplayName());

            // 保存优化记录
            saveOptimizationRecord(userId, null, null, sceneDescription,
                    enhancedPrompt, visualStyle.getNegativePrompt(),
                    visualStyle.name(), modelService.getCurrentModel(), "SUCCESS", null);

            log.info("Prompt enhanced successfully: '{}' -> '{}'",
                    truncate(sceneDescription, 30), truncate(enhancedPrompt, 50));

            return result;

        } catch (Exception e) {
            log.error("Prompt enhancement failed: {}", e.getMessage(), e);

            // 保存失败记录
            saveOptimizationRecord(userId, null, null, sceneDescription,
                    "", "", visualStyle.name(), modelService.getCurrentModel(),
                    "FAILED", truncate(e.getMessage(), 400));

            throw new RuntimeException("提示词优化失败: " + e.getMessage(), e);
        }
    }

    // ==================== 脚本镜头提示词批量优化 ====================

    /**
     * 批量优化脚本中所有镜头的 imagePrompt
     *
     * 注意：本方法内部逐个调用 LLM（耗时长），故不加 @Transactional，
     * 避免长时间占用数据库连接；每次 repository.save 自带独立事务
     *
     * @param scriptId 脚本ID
     * @param style    视觉风格（可选）
     * @return 每个镜头的优化结果
     */
    public List<Map<String, Object>> enhanceScriptShots(Long scriptId, String style) {
        Long userId = getCurrentUserId();
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));

        VisualStyle visualStyle = VisualStyle.fromName(style);
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        List<com.github.ylyan2015.springaidemo.entity.StoryCharacter> allChars =
                characterRepository.findByScriptIdOrderByIdAsc(scriptId);
        List<Map<String, Object>> results = new ArrayList<>();

        log.info("Batch enhancing {} shots for script {} with style '{}'",
                shots.size(), scriptId, visualStyle.getDisplayName());

        for (StoryShot shot : shots) {
            String description = shot.getSceneDescription();
            if (description == null || description.isBlank()) {
                description = shot.getImagePrompt(); // 回退到已有的 imagePrompt
            }
            if (description == null || description.isBlank()) {
                log.warn("Shot {} has no scene description, skipping", shot.getId());
                continue;
            }

            try {
                String userMessage = buildUserMessage(description, visualStyle,
                        characterContext(shot, allChars));

                String enhancedPrompt = modelService.getChatClient()
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userMessage)
                        .call()
                        .content();

                enhancedPrompt = cleanPromptOutput(enhancedPrompt);

                // 更新镜头的 imagePrompt；提示词变化后旧图片失效，需清空引用并重新生成
                shot.setImagePrompt(enhancedPrompt);
                shot.setImageStatus("PENDING");
                shot.setImageUrl(null);
                shot.setImageFileId(null);
                shotRepository.save(shot);

                // 保存优化记录
                saveOptimizationRecord(userId, scriptId, shot.getId(), description,
                        enhancedPrompt, visualStyle.getNegativePrompt(),
                        visualStyle.name(), modelService.getCurrentModel(), "SUCCESS", null);

                Map<String, Object> shotResult = new LinkedHashMap<>();
                shotResult.put("shotId", shot.getId());
                shotResult.put("shotOrder", shot.getShotOrder());
                shotResult.put("enhancedPrompt", enhancedPrompt);
                shotResult.put("negativePrompt", visualStyle.getNegativePrompt());
                shotResult.put("status", "SUCCESS");
                results.add(shotResult);

                log.info("Shot {} enhanced: '{}'", shot.getShotOrder(), truncate(enhancedPrompt, 50));

            } catch (Exception e) {
                log.error("Failed to enhance shot {}: {}", shot.getId(), e.getMessage());

                saveOptimizationRecord(userId, scriptId, shot.getId(), description,
                        "", "", visualStyle.name(), modelService.getCurrentModel(),
                        "FAILED", truncate(e.getMessage(), 400));

                Map<String, Object> shotResult = new LinkedHashMap<>();
                shotResult.put("shotId", shot.getId());
                shotResult.put("shotOrder", shot.getShotOrder());
                shotResult.put("status", "FAILED");
                shotResult.put("error", e.getMessage());
                results.add(shotResult);
            }
        }

        // 更新脚本修改时间
        script.setUpdatedAt(java.time.LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Batch enhancement complete: {}/{} shots succeeded",
                results.stream().filter(r -> "SUCCESS".equals(r.get("status"))).count(),
                results.size());

        return results;
    }

    // ==================== 优化记录查询 ====================

    /**
     * 获取当前用户的所有优化记录
     */
    public List<ImagePromptOptimizer> listHistory() {
        Long userId = getCurrentUserId();
        return optimizerRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 获取某个脚本的优化记录
     */
    public List<ImagePromptOptimizer> listHistoryByScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return optimizerRepository.findByUserIdAndScriptIdOrderByCreatedAtDesc(userId, scriptId);
    }

    /**
     * 获取所有可用的视觉风格预设
     */
    public List<Map<String, String>> listStyles() {
        return Arrays.stream(VisualStyle.values())
                .map(s -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("name", s.name());
                    map.put("displayName", s.getDisplayName());
                    map.put("promptPrefix", s.getPromptPrefix());
                    map.put("negativePrompt", s.getNegativePrompt());
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ==================== 内部方法 ====================

    /**
     * 构建发送给 LLM 的用户消息
     */
    private String buildUserMessage(String sceneDescription, VisualStyle visualStyle) {
        return buildUserMessage(sceneDescription, visualStyle, null);
    }

    /**
     * Phase 4 切片 1：角色一致性锚点注入（特征表逐角色，空则不追加）
     */
    private String buildUserMessage(String sceneDescription, VisualStyle visualStyle, String characterContext) {
        String base = String.format("""
                请将以下场景描述优化为高质量的英文图像生成提示词：

                场景描述：%s

                目标风格：%s（%s）

                请直接输出英文提示词，不要包含任何解释。
                """, sceneDescription, visualStyle.getDisplayName(), visualStyle.getPromptPrefix());
        if (characterContext != null && !characterContext.isBlank()) {
            base += "\n角色一致性锚点（必须保持）：" + characterContext + "\n";
        }
        return base;
    }

    private String characterContext(StoryShot shot,
                                    List<com.github.ylyan2015.springaidemo.entity.StoryCharacter> allChars) {
        List<Long> ids = StoryScriptService.parseCharacterIds(shot.getCharacterIds());
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (com.github.ylyan2015.springaidemo.entity.StoryCharacter c : allChars) {
            if (ids.contains(c.getId()) && c.getFeatureTable() != null && !c.getFeatureTable().isBlank()) {
                sb.append("Character ").append(c.getName()).append(": ").append(c.getFeatureTable()).append(". ");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 清理 LLM 输出中的多余格式
     * 去除可能的 Markdown 代码块包裹、前后空白等
     */
    private String cleanPromptOutput(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();

        // 去除 Markdown 代码块包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }

        // 去除可能的引号包裹
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }

    /**
     * 保存优化记录
     */
    private void saveOptimizationRecord(Long userId, Long scriptId, Long shotId,
                                        String originalDescription, String optimizedPrompt,
                                        String negativePrompt, String visualStyle,
                                        String modelUsed, String status, String errorMessage) {
        try {
            ImagePromptOptimizer record = new ImagePromptOptimizer();
            record.setUserId(userId);
            record.setScriptId(scriptId);
            record.setShotId(shotId);
            record.setOriginalDescription(originalDescription);
            record.setOptimizedPrompt(optimizedPrompt);
            record.setNegativePrompt(negativePrompt);
            record.setVisualStyle(visualStyle);
            record.setModelUsed(modelUsed);
            record.setStatus(status);
            record.setErrorMessage(errorMessage);
            optimizerRepository.save(record);
        } catch (Exception e) {
            // 记录保存失败不应影响主流程
            log.error("Failed to save optimization record: {}", e.getMessage());
        }
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
