package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 故事脚本控制器
 * 提供脚本的生成、保存、编辑、镜头管理等 REST API 接口
 */
@RestController
@RequestMapping("/api/video/scripts")
public class StoryScriptController {

    private static final Logger log = LoggerFactory.getLogger(StoryScriptController.class);

    private final StoryScriptService storyScriptService;
    private final VideoTemplateService videoTemplateService;

    public StoryScriptController(StoryScriptService storyScriptService,
                                 VideoTemplateService videoTemplateService) {
        this.storyScriptService = storyScriptService;
        this.videoTemplateService = videoTemplateService;
    }

    // ==================== 脚本生成与保存 ====================

    /**
     * 根据创意想法生成分镜脚本并保存
     *
     * @param request 包含 idea 字段的请求体，可选 templateId（模块 H：应用视频模板）
     * @return 生成的脚本（含镜头列表）
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateScript(@RequestBody Map<String, Object> request) {
        String idea = (String) request.get("idea");
        Map<String, Object> result = new LinkedHashMap<>();

        if (idea == null || idea.isBlank()) {
            result.put("success", false);
            result.put("message", "创意想法不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            Long templateId = toLong(request.get("templateId"));
            log.info("Generating story script from idea: '{}', templateId={}", truncate(idea, 50), templateId);
            StoryScript script = templateId != null
                    ? videoTemplateService.applyTemplate(templateId, idea)
                    : storyScriptService.generateAndSaveScript(idea);

            result.put("success", true);
            result.put("data", scriptToMap(script));
            result.put("message", "脚本生成成功");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Script generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "脚本生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 手动保存一个脚本（从前端传入的 Storyboard 数据）
     *
     * @param request 包含 storyboard 和 idea 的请求体
     * @return 保存后的脚本
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveScript(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> storyboardData = (Map<String, Object>) request.get("storyboard");
            String idea = (String) request.getOrDefault("idea", "");

            Storyboard storyboard = mapToStoryboard(storyboardData);
            StoryScript script = storyScriptService.saveFromStoryboard(null, storyboard, idea);

            result.put("success", true);
            result.put("data", scriptToMap(script));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Script save failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "脚本保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 脚本查询 ====================

    /**
     * 获取当前用户的所有脚本列表
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listScripts() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            List<StoryScript> scripts = storyScriptService.listScripts();
            List<Map<String, Object>> items = scripts.stream()
                    .map(this::scriptToListMap)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("data", items);
            result.put("total", items.size());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("List scripts failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取脚本列表失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 获取脚本详情（含所有镜头）
     *
     * @param id 脚本ID
     * @return 脚本详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getScript(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            StoryScript script = storyScriptService.getScript(id);
            result.put("success", true);
            result.put("data", scriptToMap(script));
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 脚本更新与删除 ====================

    /**
     * 更新脚本基本信息（标题、主题、状态）
     *
     * @param id      脚本ID
     * @param request 包含 title/theme/status 的请求体
     * @return 更新后的脚本
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateScript(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String title = request.get("title");
            String theme = request.get("theme");
            String status = request.get("status");

            StoryScript updated = storyScriptService.updateScript(id, title, theme, status);

            result.put("success", true);
            result.put("data", scriptToMap(updated));
            result.put("message", "脚本已更新");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Phase 4 切片 1：脚本级整体画风锁更新
     */
    @PutMapping("/{id}/style")
    public ResponseEntity<Map<String, Object>> updateStyle(@PathVariable Long id,
                                                           @RequestBody Map<String, String> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            StoryScript updated = storyScriptService.updateStyle(id, request.get("style"));
            result.put("success", true);
            result.put("data", scriptToMap(updated));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Phase 4 切片 2：首尾帧连续性模式更新
     */
    @PutMapping("/{id}/continuity")
    public ResponseEntity<Map<String, Object>> updateContinuity(@PathVariable Long id,
                                                                @RequestBody Map<String, String> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            StoryScript updated = storyScriptService.updateContinuity(id, request.get("mode"));
            result.put("success", true);
            result.put("data", scriptToMap(updated));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 删除脚本
     *
     * @param id 脚本ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteScript(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            storyScriptService.deleteScript(id);
            result.put("success", true);
            result.put("message", "脚本已删除");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 镜头管理 ====================

    /**
     * 批量更新脚本的所有镜头（整体替换）
     *
     * @param id      脚本ID
     * @param request 包含 shots 列表的请求体
     * @return 更新后的镜头列表
     */
    @PutMapping("/{id}/shots")
    public ResponseEntity<Map<String, Object>> batchUpdateShots(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shotsData = (List<Map<String, Object>>) request.get("shots");
            if (shotsData == null || shotsData.isEmpty()) {
                result.put("success", false);
                result.put("message", "镜头列表不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            List<StoryShot> shots = shotsData.stream()
                    .map(this::mapToStoryShot)
                    .collect(Collectors.toList());

            List<StoryShot> updated = storyScriptService.batchUpdateShots(id, shots);

            result.put("success", true);
            result.put("data", updated.stream().map(this::shotToMap).collect(Collectors.toList()));
            result.put("message", "镜头已更新");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 添加新镜头到脚本
     *
     * @param id      脚本ID
     * @param request 包含 position 和镜头信息的请求体
     * @return 新添加的镜头
     */
    @PostMapping("/{id}/shots")
    public ResponseEntity<Map<String, Object>> addShot(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Integer position = request.get("position") != null
                    ? ((Number) request.get("position")).intValue() : null;
            StoryShot newShot = mapToStoryShot(request);

            StoryShot added = storyScriptService.addShot(id, position, newShot);

            result.put("success", true);
            result.put("data", shotToMap(added));
            result.put("message", "镜头已添加");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 更新单个镜头
     *
     * @param id      脚本ID
     * @param shotId  镜头ID
     * @param updates 要更新的字段
     * @return 更新后的镜头
     */
    @PutMapping("/{id}/shots/{shotId}")
    public ResponseEntity<Map<String, Object>> updateShot(
            @PathVariable Long id,
            @PathVariable Long shotId,
            @RequestBody Map<String, Object> updates) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            StoryShot updated = storyScriptService.updateShot(id, shotId, updates);

            result.put("success", true);
            result.put("data", shotToMap(updated));
            result.put("message", "镜头已更新");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 删除单个镜头
     *
     * @param id     脚本ID
     * @param shotId 镜头ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}/shots/{shotId}")
    public ResponseEntity<Map<String, Object>> deleteShot(
            @PathVariable Long id,
            @PathVariable Long shotId) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            storyScriptService.deleteShot(id, shotId);

            result.put("success", true);
            result.put("message", "镜头已删除");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 拖拽排序镜头
     *
     * @param id      脚本ID
     * @param request 包含 shotIds（按新顺序排列的ID列表）的请求体
     * @return 操作结果
     */
    @PutMapping("/{id}/shots/reorder")
    public ResponseEntity<Map<String, Object>> reorderShots(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Number> idsNum = (List<Number>) request.get("shotIds");
            if (idsNum == null || idsNum.isEmpty()) {
                result.put("success", false);
                result.put("message", "镜头ID列表不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            List<Long> shotIds = idsNum.stream().map(Number::longValue).collect(Collectors.toList());
            storyScriptService.reorderShots(id, shotIds);

            result.put("success", true);
            result.put("message", "镜头顺序已更新");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 数据转换方法 ====================

    /**
     * 将 StoryScript 实体转换为完整 Map（含镜头详情，委托服务层统一实现）
     */
    private Map<String, Object> scriptToMap(StoryScript script) {
        return storyScriptService.scriptToMap(script);
    }

    /**
     * 将 StoryScript 实体转换为列表摘要 Map（不含镜头详情）
     */
    private Map<String, Object> scriptToListMap(StoryScript script) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", script.getId());
        map.put("title", script.getTitle());
        map.put("theme", script.getTheme());
        map.put("status", script.getStatus());
        map.put("createdAt", script.getCreatedAt());
        map.put("updatedAt", script.getUpdatedAt());
        map.put("shotCount", script.getShots() != null ? script.getShots().size() : 0);
        return map;
    }

    /**
     * 将 StoryShot 实体转换为 Map（委托服务层统一实现）
     */
    private Map<String, Object> shotToMap(StoryShot shot) {
        return storyScriptService.shotToMap(shot);
    }

    /**
     * 将 Map 数据转换为 StoryShot 实体
     */
    private StoryShot mapToStoryShot(Map<String, Object> data) {
        StoryShot shot = new StoryShot();

        if (data.get("shotOrder") != null) {
            shot.setShotOrder(((Number) data.get("shotOrder")).intValue());
        }
        shot.setSceneDescription((String) data.get("sceneDescription"));
        shot.setCameraMovement((String) data.get("cameraMovement"));
        if (data.get("durationSeconds") != null) {
            shot.setDurationSeconds(((Number) data.get("durationSeconds")).intValue());
        }
        shot.setNarration((String) data.get("narration"));
        shot.setImagePrompt((String) data.get("imagePrompt"));
        shot.setImageUrl((String) data.get("imageUrl"));
        shot.setImageFileId((String) data.get("imageFileId"));
        shot.setImageStatus((String) data.getOrDefault("imageStatus", "PENDING"));
        if (data.get("characterIds") instanceof List<?> list) {
            shot.setCharacterIds(list.isEmpty() ? null : list.stream()
                    .map(String::valueOf).collect(Collectors.joining(",")));
        }
        if (data.get("shotSize") != null) {
            shot.setShotSize((String) data.get("shotSize"));
        }
        shot.setDialogue((String) data.get("dialogue"));
        if (data.get("dialogueCharacterId") != null) {
            shot.setDialogueCharacterId(toLong(data.get("dialogueCharacterId")));
        }

        return shot;
    }

    /**
     * 将前端 Map 数据转换为 Storyboard DTO
     */
    @SuppressWarnings("unchecked")
    private Storyboard mapToStoryboard(Map<String, Object> data) {
        if (data == null) return new Storyboard();

        Storyboard storyboard = new Storyboard();
        storyboard.setTitle((String) data.get("title"));
        storyboard.setTheme((String) data.get("theme"));

        if (data.get("characters") instanceof List<?> charsData) {
            List<com.github.ylyan2015.springaidemo.video.CharacterDesign> chars = new ArrayList<>();
            for (Object o : charsData) {
                if (o instanceof Map<?, ?> cm) {
                    com.github.ylyan2015.springaidemo.video.CharacterDesign cd =
                            new com.github.ylyan2015.springaidemo.video.CharacterDesign();
                    cd.setName((String) cm.get("name"));
                    cd.setFeatureTable((String) cm.get("featureTable"));
                    cd.setPersonality((String) cm.get("personality"));
                    cd.setSuggestedVoice((String) cm.get("suggestedVoice"));
                    chars.add(cd);
                }
            }
            storyboard.setCharacters(chars);
        }

        List<Map<String, Object>> shotsData = (List<Map<String, Object>>) data.get("shots");
        if (shotsData != null) {
            List<com.github.ylyan2015.springaidemo.video.Shot> shots = shotsData.stream()
                    .map(s -> {
                        com.github.ylyan2015.springaidemo.video.Shot shot = new com.github.ylyan2015.springaidemo.video.Shot();
                        if (s.get("order") != null) shot.setOrder(((Number) s.get("order")).intValue());
                        shot.setSceneDescription((String) s.get("sceneDescription"));
                        shot.setCameraMovement((String) s.get("cameraMovement"));
                        if (s.get("durationSeconds") != null) shot.setDurationSeconds(((Number) s.get("durationSeconds")).intValue());
                        shot.setNarration((String) s.get("narration"));
                        shot.setImagePrompt((String) s.get("imagePrompt"));
                        if (s.get("characterNames") instanceof List<?> names) {
                            shot.setCharacterNames(names.stream().map(String::valueOf)
                                    .collect(Collectors.toList()));
                        }
                        shot.setShotSize((String) s.get("shotSize"));
                        shot.setDialogue((String) s.get("dialogue"));
                        shot.setDialogueCharacterName((String) s.get("dialogueCharacterName"));
                        return shot;
                    })
                    .collect(Collectors.toList());
            storyboard.setShots(shots);
        }

        return storyboard;
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
