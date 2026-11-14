package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoTemplate;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频模板服务类（模块 H）
 * 系统模板启动预置（幂等）、模板列表/详情、应用模板生成脚本、自定义模板保存与删除
 *
 * 应用模板 = 将模板的风格引导（stylePrefix + 推荐镜头数）注入脚本生成 System Prompt，
 * 由 StoryScriptService 完成 LLM 生成与保存
 */
@Service
public class VideoTemplateService {

    private static final Logger log = LoggerFactory.getLogger(VideoTemplateService.class);

    private final VideoTemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final StoryScriptService storyScriptService;

    public VideoTemplateService(VideoTemplateRepository templateRepository,
                                UserRepository userRepository,
                                StoryScriptService storyScriptService) {
        this.templateRepository = templateRepository;
        this.userRepository = userRepository;
        this.storyScriptService = storyScriptService;
    }

    // ==================== 系统模板预置 ====================

    /**
     * 启动时预置系统模板（已有系统模板时跳过，保证幂等）
     */
    @PostConstruct
    public void seedSystemTemplates() {
        try {
            if (!templateRepository.findByIsSystemTrue().isEmpty()) {
                return;
            }
            List<VideoTemplate> presets = new ArrayList<>();
            presets.add(preset("产品展示", "product", "商品卖点展示短片：外观特写 → 细节质感 → 使用场景 → 品牌收尾",
                    5, "commercial product showcase, clean studio background, softbox lighting, close-up details, premium advertising aesthetic", "happy"));
            presets.add(preset("故事讲述", "story", "情感叙事短片：铺垫 → 冲突 → 转折 → 共鸣收尾",
                    6, "cinematic narrative storytelling, emotional character moments, warm color grading, film still aesthetic", "calm"));
            presets.add(preset("知识科普", "knowledge", "知识点讲解短片：抛出问题 → 原理拆解 → 举例 → 总结",
                    5, "clean educational visual style, bright flat illustration, clear visual metaphors, modern minimal design", "calm"));
            presets.add(preset("风景旅拍", "travel", "旅行风光短片：大场景开场 → 特色细节 → 人文瞬间 → 意境收尾",
                    6, "breathtaking travel cinematography, golden hour landscape, wide aerial perspective, vivid natural colors", "epic"));
            presets.add(preset("美食制作", "food", "美食种草短片：成品诱惑 → 食材特写 → 制作过程 → 品尝瞬间",
                    5, "appetizing food photography, macro close-up, steam and texture details, warm kitchen lighting, gourmet magazine style", "happy"));
            presets.add(preset("情感语录", "emotion", "氛围感语录短片：意境画面 + 走心文案，适合深夜情感号",
                    4, "moody emotional atmosphere, soft bokeh, melancholic cinematic tones, minimalist composition with negative space", "sad"));
            presets.add(preset("科技数码", "tech", "数码产品评测短片：科技感开场 → 功能演示 → 场景体验 → 观点收尾",
                    5, "futuristic tech aesthetic, neon blue accents, dark background, sleek gadget rendering, high-tech product visualization", "tense"));
            presets.add(preset("运动健身", "fitness", "运动燃向短片：动作张力 → 汗水细节 → 突破瞬间 → 励志收尾",
                    5, "dynamic sports photography, high-energy action freeze frame, dramatic rim lighting, powerful athletic composition", "epic"));
            templateRepository.saveAll(presets);
            log.info("Seeded {} system video templates", presets.size());
        } catch (Exception e) {
            log.error("Video template seeding failed: {}", e.getMessage(), e);
        }
    }

    private VideoTemplate preset(String name, String category, String description,
                                 int shotCount, String stylePrefix, String bgmCategory) {
        VideoTemplate t = new VideoTemplate();
        t.setName(name);
        t.setCategory(category);
        t.setDescription(description);
        t.setShotCount(shotCount);
        t.setStylePrefix(stylePrefix);
        t.setDefaultBgmCategory(bgmCategory);
        t.setDefaultVoice("zh-CN-XiaoxiaoNeural");
        t.setIsSystem(true);
        return t;
    }

    // ==================== 模板查询 ====================

    /**
     * 模板列表（系统内置 + 当前用户自定义）
     */
    public List<Map<String, Object>> listTemplates() {
        Long userId = getCurrentUserId();
        return templateRepository.findByIsSystemTrueOrUserIdOrderByIdAsc(userId).stream()
                .map(this::toMap)
                .toList();
    }

    /**
     * 模板详情（系统模板或本人自定义，否则拒绝）
     */
    public VideoTemplate getTemplate(Long templateId) {
        VideoTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("模板不存在: " + templateId));
        if (!Boolean.TRUE.equals(template.getIsSystem())
                && !getCurrentUserId().equals(template.getUserId())) {
            throw new RuntimeException("无权访问该模板: " + templateId);
        }
        return template;
    }

    // ==================== 模板应用 ====================

    /**
     * 应用模板生成分镜脚本：风格引导注入 System Prompt，生成并保存
     * 注意：内含分钟级 LLM 调用，不加 @Transactional 避免长事务
     */
    public StoryScript applyTemplate(Long templateId, String idea) {
        if (idea == null || idea.isBlank()) {
            throw new RuntimeException("创意想法不能为空");
        }
        VideoTemplate template = getTemplate(templateId);
        log.info("Applying video template {} ({}) for idea: '{}'", templateId, template.getName(), idea);
        return storyScriptService.generateAndSaveScript(idea, buildGuidance(template));
    }

    /**
     * 构建注入脚本生成 System Prompt 的模板引导文本
     */
    public static String buildGuidance(VideoTemplate template) {
        StringBuilder sb = new StringBuilder();
        if (template.getStylePrefix() != null && !template.getStylePrefix().isBlank()) {
            sb.append("模板风格要求（每个镜头的 imagePrompt 必须体现以下风格）：")
                    .append(template.getStylePrefix()).append("。\n");
        }
        if (template.getShotCount() != null && template.getShotCount() > 0) {
            sb.append("镜头数量约 ").append(template.getShotCount()).append(" 个。\n");
        }
        return sb.toString();
    }

    // ==================== 自定义模板 ====================

    /**
     * 保存自定义模板（当前用户归属，非系统模板）
     */
    public Map<String, Object> saveCustomTemplate(String name, String category, String description,
                                                  Integer shotCount, String stylePrefix,
                                                  String defaultBgmCategory) {
        if (name == null || name.isBlank()) {
            throw new RuntimeException("模板名称不能为空");
        }
        VideoTemplate template = new VideoTemplate();
        template.setName(name.trim());
        template.setCategory(category == null || category.isBlank() ? "other" : category);
        template.setDescription(description);
        template.setShotCount(shotCount == null || shotCount <= 0 ? 5 : shotCount);
        template.setStylePrefix(stylePrefix);
        template.setDefaultBgmCategory(defaultBgmCategory);
        template.setIsSystem(false);
        template.setUserId(getCurrentUserId());
        VideoTemplate saved = templateRepository.save(template);
        log.info("Custom video template saved: id={}, name={}, userId={}",
                saved.getId(), saved.getName(), saved.getUserId());
        return toMap(saved);
    }

    /**
     * 删除模板（仅本人自定义模板可删，系统模板拒绝）
     */
    public void deleteTemplate(Long templateId) {
        VideoTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("模板不存在: " + templateId));
        if (Boolean.TRUE.equals(template.getIsSystem())) {
            throw new RuntimeException("系统内置模板不可删除");
        }
        if (!getCurrentUserId().equals(template.getUserId())) {
            throw new RuntimeException("无权删除该模板: " + templateId);
        }
        templateRepository.delete(template);
        log.info("Custom video template deleted: id={}, userId={}", templateId, template.getUserId());
    }

    // ==================== 工具方法 ====================

    public Map<String, Object> toMap(VideoTemplate template) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", template.getId());
        map.put("name", template.getName());
        map.put("category", template.getCategory());
        map.put("description", template.getDescription());
        map.put("shotCount", template.getShotCount());
        map.put("stylePrefix", template.getStylePrefix());
        map.put("defaultBgmCategory", template.getDefaultBgmCategory());
        map.put("defaultVoice", template.getDefaultVoice());
        map.put("isSystem", template.getIsSystem());
        map.put("createdAt", template.getCreatedAt() == null ? null : template.getCreatedAt().toString());
        return map;
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
}