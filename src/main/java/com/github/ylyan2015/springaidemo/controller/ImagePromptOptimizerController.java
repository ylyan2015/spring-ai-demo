package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.ImagePromptOptimizer;
import com.github.ylyan2015.springaidemo.service.ImagePromptOptimizerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图像提示词优化控制器
 * 提供提示词优化、风格预设查询、优化历史等 REST API 接口
 */
@RestController
@RequestMapping("/api/video/enhance-prompt")
public class ImagePromptOptimizerController {

    private static final Logger log = LoggerFactory.getLogger(ImagePromptOptimizerController.class);

    private final ImagePromptOptimizerService optimizerService;

    public ImagePromptOptimizerController(ImagePromptOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
    }

    // ==================== 提示词优化 ====================

    /**
     * 优化单个场景描述为高质量英文图像提示词
     *
     * @param request 包含 sceneDescription 和 style（可选）的请求体
     * @return 优化后的提示词和负面提示词
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> enhancePrompt(@RequestBody Map<String, String> request) {
        String sceneDescription = request.get("sceneDescription");
        String style = request.get("style");
        Map<String, Object> result = new LinkedHashMap<>();

        if (sceneDescription == null || sceneDescription.isBlank()) {
            result.put("success", false);
            result.put("message", "场景描述不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("Enhancing prompt: style='{}', description='{}'",
                    style, truncate(sceneDescription, 50));

            Map<String, String> enhanced = optimizerService.enhancePrompt(sceneDescription, style);

            result.put("success", true);
            result.put("data", enhanced);
            result.put("message", "提示词优化成功");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Prompt enhancement failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "提示词优化失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 批量优化脚本中所有镜头的 imagePrompt
     *
     * @param scriptId 脚本ID
     * @param request  包含 style（可选）的请求体
     * @return 每个镜头的优化结果
     */
    @PostMapping("/script/{scriptId}")
    public ResponseEntity<Map<String, Object>> enhanceScriptPrompts(
            @PathVariable Long scriptId,
            @RequestBody(required = false) Map<String, String> request) {

        String style = (request != null) ? request.get("style") : null;
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            log.info("Batch enhancing prompts for script {}: style='{}'", scriptId, style);

            List<Map<String, Object>> enhancements = optimizerService.enhanceScriptShots(scriptId, style);

            long successCount = enhancements.stream()
                    .filter(e -> "SUCCESS".equals(e.get("status")))
                    .count();

            result.put("success", true);
            result.put("data", enhancements);
            result.put("total", enhancements.size());
            result.put("succeeded", successCount);
            result.put("failed", enhancements.size() - successCount);
            result.put("message", String.format("批量优化完成：%d/%d 成功", successCount, enhancements.size()));
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.error("Batch enhancement failed for script {}: {}", scriptId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "批量优化失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 风格预设查询 ====================

    /**
     * 获取所有可用的视觉风格预设
     */
    @GetMapping("/styles")
    public ResponseEntity<Map<String, Object>> listStyles() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            List<Map<String, String>> styles = optimizerService.listStyles();

            result.put("success", true);
            result.put("data", styles);
            result.put("total", styles.size());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("List styles failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取风格列表失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 优化历史查询 ====================

    /**
     * 获取当前用户的所有优化记录
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> listHistory() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            List<ImagePromptOptimizer> records = optimizerService.listHistory();
            List<Map<String, Object>> items = records.stream()
                    .map(this::recordToMap)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("data", items);
            result.put("total", items.size());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("List history failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取优化历史失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 获取某个脚本的优化记录
     *
     * @param scriptId 脚本ID
     */
    @GetMapping("/history/script/{scriptId}")
    public ResponseEntity<Map<String, Object>> listHistoryByScript(@PathVariable Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            List<ImagePromptOptimizer> records = optimizerService.listHistoryByScript(scriptId);
            List<Map<String, Object>> items = records.stream()
                    .map(this::recordToMap)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("data", items);
            result.put("total", items.size());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("List history for script {} failed: {}", scriptId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取脚本优化历史失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 数据转换方法 ====================

    /**
     * 将优化记录实体转换为 Map
     */
    private Map<String, Object> recordToMap(ImagePromptOptimizer record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("scriptId", record.getScriptId());
        map.put("shotId", record.getShotId());
        map.put("originalDescription", record.getOriginalDescription());
        map.put("optimizedPrompt", record.getOptimizedPrompt());
        map.put("negativePrompt", record.getNegativePrompt());
        map.put("visualStyle", record.getVisualStyle());
        map.put("modelUsed", record.getModelUsed());
        map.put("status", record.getStatus());
        map.put("errorMessage", record.getErrorMessage());
        map.put("createdAt", record.getCreatedAt());
        return map;
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
