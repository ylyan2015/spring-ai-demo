package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.VideoTemplate;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频模板控制器（模块 H）
 * 模板列表/详情、应用模板生成分镜脚本、自定义模板保存与删除
 */
@RestController
@RequestMapping("/api/video/templates")
public class VideoTemplateController {

    private static final Logger log = LoggerFactory.getLogger(VideoTemplateController.class);

    private final VideoTemplateService videoTemplateService;
    private final StoryScriptService storyScriptService;

    public VideoTemplateController(VideoTemplateService videoTemplateService,
                                   StoryScriptService storyScriptService) {
        this.videoTemplateService = videoTemplateService;
        this.storyScriptService = storyScriptService;
    }

    /**
     * 模板列表（系统内置 + 当前用户自定义）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTemplates() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> templates = videoTemplateService.listTemplates();
            result.put("success", true);
            result.put("data", templates);
            result.put("total", templates.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("List templates failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取模板列表失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 模板详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTemplate(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            VideoTemplate template = videoTemplateService.getTemplate(id);
            result.put("success", true);
            result.put("data", videoTemplateService.toMap(template));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Get template {} failed: {}", id, e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 应用模板生成分镜脚本（风格引导注入 System Prompt，生成并保存）
     * 请求体: { "idea": "一个关于猫咪的温馨短片" }
     * 响应 data: 与 POST /api/video/scripts/generate 一致的脚本结构（含镜头列表）
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable Long id, @RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        String idea = (String) request.get("idea");
        if (idea == null || idea.isBlank()) {
            result.put("success", false);
            result.put("message", "创意想法不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            log.info("Applying template {} for idea: '{}'", id, idea);
            StoryScript script = videoTemplateService.applyTemplate(id, idea);
            result.put("success", true);
            result.put("data", storyScriptService.scriptToMap(script));
            result.put("message", "模板应用成功，脚本已生成");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Apply template {} failed: {}", id, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "模板应用失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 保存自定义模板
     * 请求体: { "name": "我的模板", "category": "story", "description": "...",
     *          "shotCount": 6, "stylePrefix": "...", "defaultBgmCategory": "calm" }
     */
    @PostMapping("/custom")
    public ResponseEntity<Map<String, Object>> saveCustomTemplate(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Integer shotCount = request.get("shotCount") instanceof Number n ? n.intValue() : null;
            Map<String, Object> data = videoTemplateService.saveCustomTemplate(
                    (String) request.get("name"),
                    (String) request.get("category"),
                    (String) request.get("description"),
                    shotCount,
                    (String) request.get("stylePrefix"),
                    (String) request.get("defaultBgmCategory"));
            result.put("success", true);
            result.put("data", data);
            result.put("message", "自定义模板已保存");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Save custom template failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 删除自定义模板（系统内置模板不可删除）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            videoTemplateService.deleteTemplate(id);
            result.put("success", true);
            result.put("message", "模板已删除");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Delete template {} failed: {}", id, e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}