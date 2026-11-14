package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.Shot;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 故事脚本服务类
 * 负责脚本的生成、保存、编辑、镜头管理等业务逻辑
 */
@Service
public class StoryScriptService {

    private static final Logger log = LoggerFactory.getLogger(StoryScriptService.class);

    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final VideoScriptService videoScriptService;

    public StoryScriptService(StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              UserRepository userRepository,
                              VideoScriptService videoScriptService) {
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.videoScriptService = videoScriptService;
    }

    // ==================== 脚本生成与保存 ====================

    /**
     * 根据创意想法生成分镜脚本并保存到数据库
     *
     * @param idea 创意想法文本
     * @return 保存后的脚本实体（含镜头列表）
     */
    @Transactional
    public StoryScript generateAndSaveScript(String idea) {
        return generateAndSaveScript(idea, null);
    }

    /**
     * 根据创意想法生成分镜脚本并保存（模块 H：可选模板风格引导）
     *
     * @param idea             创意想法文本
     * @param templateGuidance 模板风格引导（注入 System Prompt），null 时不注入
     */
    @Transactional
    public StoryScript generateAndSaveScript(String idea, String templateGuidance) {
        Long userId = getCurrentUserId();

        // 1. 调用 LLM 生成分镜脚本
        log.info("Generating storyboard for user {}: idea='{}', templateGuidance={}",
                userId, idea, templateGuidance != null);
        Storyboard storyboard = videoScriptService.generateScript(idea, templateGuidance);

        // 2. 转换为实体并保存
        StoryScript script = new StoryScript();
        script.setUserId(userId);
        script.setTitle(storyboard.getTitle());
        script.setTheme(storyboard.getTheme());
        script.setIdea(idea);
        script.setStatus("DRAFT");

        if (storyboard.getShots() != null) {
            for (int i = 0; i < storyboard.getShots().size(); i++) {
                Shot shotDto = storyboard.getShots().get(i);
                StoryShot shot = new StoryShot();
                shot.setShotOrder(i + 1);
                shot.setSceneDescription(shotDto.getSceneDescription());
                shot.setCameraMovement(shotDto.getCameraMovement());
                shot.setDurationSeconds(shotDto.getDurationSeconds());
                shot.setNarration(shotDto.getNarration());
                shot.setImagePrompt(shotDto.getImagePrompt());
                shot.setImageStatus("PENDING");
                script.addShot(shot);
            }
        }

        StoryScript saved = scriptRepository.save(script);
        log.info("Story script saved: id={}, title='{}', {} shots",
                saved.getId(), saved.getTitle(), saved.getShots().size());

        return saved;
    }

    /**
     * 从 Storyboard DTO 保存脚本（用于手动创建/导入）
     *
     * @param userId 归属用户ID，传 null 时自动使用当前登录用户
     */
    @Transactional
    public StoryScript saveFromStoryboard(Long userId, Storyboard storyboard, String idea) {
        if (userId == null) {
            userId = getCurrentUserId();
        }
        StoryScript script = new StoryScript();
        script.setUserId(userId);
        script.setTitle(storyboard.getTitle());
        script.setTheme(storyboard.getTheme());
        script.setIdea(idea);
        script.setStatus("DRAFT");

        if (storyboard.getShots() != null) {
            for (int i = 0; i < storyboard.getShots().size(); i++) {
                Shot shotDto = storyboard.getShots().get(i);
                StoryShot shot = new StoryShot();
                shot.setShotOrder(i + 1);
                shot.setSceneDescription(shotDto.getSceneDescription());
                shot.setCameraMovement(shotDto.getCameraMovement());
                shot.setDurationSeconds(shotDto.getDurationSeconds());
                shot.setNarration(shotDto.getNarration());
                shot.setImagePrompt(shotDto.getImagePrompt());
                shot.setImageStatus("PENDING");
                script.addShot(shot);
            }
        }

        return scriptRepository.save(script);
    }

    // ==================== 脚本查询 ====================

    /**
     * 获取当前用户的所有脚本列表
     */
    public List<StoryScript> listScripts() {
        Long userId = getCurrentUserId();
        return scriptRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * 获取脚本详情（含所有镜头）
     */
    public StoryScript getScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
    }

    /**
     * 将 StoryScript 转换为 Storyboard DTO（用于后续图片生成/视频合成）
     */
    public Storyboard toStoryboard(StoryScript script) {
        Storyboard storyboard = new Storyboard();
        storyboard.setTitle(script.getTitle());
        storyboard.setTheme(script.getTheme());

        List<Shot> shots = script.getShots().stream().map(entity -> {
            Shot dto = new Shot();
            dto.setOrder(entity.getShotOrder());
            dto.setSceneDescription(entity.getSceneDescription());
            dto.setCameraMovement(entity.getCameraMovement());
            dto.setDurationSeconds(entity.getDurationSeconds());
            dto.setNarration(entity.getNarration());
            dto.setImagePrompt(entity.getImagePrompt());
            return dto;
        }).collect(Collectors.toList());

        storyboard.setShots(shots);
        return storyboard;
    }

    // ==================== 脚本更新 ====================

    /**
     * 更新脚本基本信息（标题、主题、状态）
     */
    @Transactional
    public StoryScript updateScript(Long scriptId, String title, String theme, String status) {
        StoryScript script = getScript(scriptId);

        if (title != null && !title.isBlank()) {
            script.setTitle(title);
        }
        if (theme != null && !theme.isBlank()) {
            script.setTheme(theme);
        }
        if (status != null && !status.isBlank()) {
            script.setStatus(status);
        }
        script.setUpdatedAt(LocalDateTime.now());

        return scriptRepository.save(script);
    }

    /**
     * 删除脚本
     */
    @Transactional
    public void deleteScript(Long scriptId) {
        Long userId = getCurrentUserId();
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        scriptRepository.delete(script);
        log.info("Story script deleted: id={}, userId={}", scriptId, userId);
    }

    // ==================== 镜头管理 ====================

    /**
     * 更新单个镜头的内容
     */
    @Transactional
    public StoryShot updateShot(Long scriptId, Long shotId, Map<String, Object> updates) {
        StoryScript script = getScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));

        if (updates.containsKey("sceneDescription")) {
            shot.setSceneDescription((String) updates.get("sceneDescription"));
        }
        if (updates.containsKey("cameraMovement")) {
            shot.setCameraMovement((String) updates.get("cameraMovement"));
        }
        if (updates.containsKey("durationSeconds")) {
            shot.setDurationSeconds(((Number) updates.get("durationSeconds")).intValue());
        }
        if (updates.containsKey("narration")) {
            shot.setNarration((String) updates.get("narration"));
        }
        if (updates.containsKey("imagePrompt")) {
            shot.setImagePrompt((String) updates.get("imagePrompt"));
            // 提示词修改后，图片需要重新生成
            shot.setImageStatus("PENDING");
            shot.setImageUrl(null);
            shot.setImageFileId(null);
        }
        if (updates.containsKey("imageUrl")) {
            shot.setImageUrl((String) updates.get("imageUrl"));
        }
        if (updates.containsKey("imageFileId")) {
            shot.setImageFileId((String) updates.get("imageFileId"));
        }
        if (updates.containsKey("imageStatus")) {
            shot.setImageStatus((String) updates.get("imageStatus"));
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        return shotRepository.save(shot);
    }

    /**
     * 在指定位置插入新镜头
     */
    @Transactional
    public StoryShot addShot(Long scriptId, Integer position, StoryShot newShot) {
        StoryScript script = getScript(scriptId);

        // 确定插入位置
        int insertOrder = (position != null && position >= 1) ? position : script.getShots().size() + 1;

        // 将插入位置及之后的镜头顺序后移
        List<StoryShot> existingShots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        for (StoryShot existing : existingShots) {
            if (existing.getShotOrder() >= insertOrder) {
                existing.setShotOrder(existing.getShotOrder() + 1);
            }
        }
        if (!existingShots.isEmpty()) {
            shotRepository.saveAll(existingShots);
        }

        // 设置新镜头的顺序并添加
        newShot.setShotOrder(insertOrder);
        script.addShot(newShot);
        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shot added to script {}: order={}, scene='{}'",
                scriptId, insertOrder, newShot.getSceneDescription());

        return newShot;
    }

    /**
     * 删除镜头
     */
    @Transactional
    public void deleteShot(Long scriptId, Long shotId) {
        StoryScript script = getScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));

        int deletedOrder = shot.getShotOrder();
        script.removeShot(shot);

        // 将删除位置之后的镜头顺序前移
        List<StoryShot> remainingShots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        for (StoryShot s : remainingShots) {
            if (s.getShotOrder() > deletedOrder) {
                s.setShotOrder(s.getShotOrder() - 1);
            }
        }
        if (!remainingShots.isEmpty()) {
            shotRepository.saveAll(remainingShots);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shot deleted from script {}: shotId={}, order={}", scriptId, shotId, deletedOrder);
    }

    /**
     * 重新排序镜头（拖拽排序后调用）
     *
     * @param scriptId 脚本ID
     * @param shotIds  按新顺序排列的镜头ID列表
     */
    @Transactional
    public void reorderShots(Long scriptId, List<Long> shotIds) {
        StoryScript script = getScript(scriptId);

        for (int i = 0; i < shotIds.size(); i++) {
            Long shotId = shotIds.get(i);
            StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                    .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
            shot.setShotOrder(i + 1);
            shotRepository.save(shot);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shots reordered for script {}: {} shots", scriptId, shotIds.size());
    }

    /**
     * 批量更新镜头（整体替换脚本的镜头列表）
     */
    @Transactional
    public List<StoryShot> batchUpdateShots(Long scriptId, List<StoryShot> updatedShots) {
        StoryScript script = getScript(scriptId);

        // 清空现有镜头
        List<StoryShot> existing = new ArrayList<>(script.getShots());
        existing.forEach(script::removeShot);
        shotRepository.deleteAll(existing);

        // 添加新镜头
        for (int i = 0; i < updatedShots.size(); i++) {
            StoryShot shot = updatedShots.get(i);
            shot.setShotOrder(i + 1);
            shot.setId(null); // 确保是新记录
            script.addShot(shot);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        return shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
    }

    // ==================== 工具方法 ====================

    /**
     * 将 StoryScript 实体转换为完整 Map（含镜头详情，供控制器统一复用）
     */
    public Map<String, Object> scriptToMap(StoryScript script) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", script.getId());
        map.put("title", script.getTitle());
        map.put("theme", script.getTheme());
        map.put("idea", script.getIdea());
        map.put("status", script.getStatus());
        map.put("createdAt", script.getCreatedAt());
        map.put("updatedAt", script.getUpdatedAt());

        List<Map<String, Object>> shotMaps = script.getShots().stream()
                .sorted(Comparator.comparingInt(StoryShot::getShotOrder))
                .map(this::shotToMap)
                .collect(Collectors.toList());
        map.put("shots", shotMaps);
        map.put("shotCount", shotMaps.size());

        int totalDuration = shotMaps.stream()
                .mapToInt(s -> ((Number) s.getOrDefault("durationSeconds", 0)).intValue())
                .sum();
        map.put("totalDuration", totalDuration);

        return map;
    }

    /**
     * 将 StoryShot 实体转换为 Map
     */
    public Map<String, Object> shotToMap(StoryShot shot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", shot.getId());
        map.put("scriptId", shot.getScriptId());
        map.put("shotOrder", shot.getShotOrder());
        map.put("sceneDescription", shot.getSceneDescription());
        map.put("cameraMovement", shot.getCameraMovement());
        map.put("durationSeconds", shot.getDurationSeconds());
        map.put("narration", shot.getNarration());
        map.put("imagePrompt", shot.getImagePrompt());
        map.put("imageUrl", shot.getImageUrl());
        map.put("imageFileId", shot.getImageFileId());
        map.put("imageStatus", shot.getImageStatus());
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
