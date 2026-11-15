package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.ImageGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * AI 图像生成控制器
 * 提供镜头图片生成、批量并行生成（SSE 进度推送）、生成器管理等 REST API 接口
 */
@RestController
@RequestMapping("/api/video/images")
public class ImageGenerationController {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationController.class);

    private final ImageGenerationService imageGenerationService;

    public ImageGenerationController(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
    }

    // ==================== 单镜头生成 ====================

    /**
     * 为单个镜头生成图片
     *
     * @param scriptId 脚本ID
     * @param shotId   镜头ID
     * @param request  可选请求体，包含 style（视觉风格）
     * @return 更新后的镜头
     */
    @PostMapping("/shot/{scriptId}/{shotId}")
    public ResponseEntity<Map<String, Object>> generateForShot(
            @PathVariable Long scriptId,
            @PathVariable Long shotId,
            @RequestBody(required = false) Map<String, String> request) {

        String style = (request != null) ? request.get("style") : null;
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            log.info("Generating image for shot {} of script {}", shotId, scriptId);
            StoryShot shot = imageGenerationService.generateForShot(scriptId, shotId, style);

            result.put("success", true);
            result.put("data", shotToMap(shot));
            result.put("message", "图片生成成功");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.error("Image generation failed for shot {}: {}", shotId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "图片生成失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 批量生成（SSE 进度推送） ====================

    /**
     * 为脚本所有镜头并行生成图片，通过 SSE 实时推送进度
     * 前端使用原生 EventSource 监听（EventSource 仅支持 GET，故此接口为 GET），事件类型：
     * START / SHOT_START / SHOT_COMPLETE / SHOT_FAILED / COMPLETE / ERROR
     *
     * @param scriptId 脚本ID
     * @param style    视觉风格（可选，如 CINEMATIC_REALISM）
     * @return SSE 事件流
     */
    @GetMapping(value = "/script/{scriptId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> generateForScriptStream(
            @PathVariable Long scriptId,
            @RequestParam(required = false) String style) {

        // 入口登录校验（release 审查 M4/M5）：EventSource 仅支持 GET 保持 GET，
        // 但匿名请求直接拒绝，避免跨站 <img>/EventSource 触发批量生图
        if (!isLoggedIn()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("ERROR").data("用户未登录").build());
        }
        log.info("Starting batch image generation with SSE progress: scriptId={}, style='{}'", scriptId, style);
        return imageGenerationService.generateForScriptStream(scriptId, style);
    }

    // ==================== 生成器管理 ====================

    /**
     * 获取所有可用的图像生成器列表
     */
    @GetMapping("/generators")
    public ResponseEntity<Map<String, Object>> listGenerators() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", imageGenerationService.listGenerators());
        return ResponseEntity.ok(result);
    }

    /**
     * 运行时切换图像生成器（全局开关，需登录，release 审查 M5：防匿名 DoS 全体用户）
     *
     * @param type 生成器类型（dashscope / local）
     */
    @PutMapping("/generator")
    public ResponseEntity<Map<String, Object>> switchGenerator(@RequestParam String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isLoggedIn()) {
            result.put("success", false);
            result.put("message", "用户未登录");
            return ResponseEntity.status(401).body(result);
        }
        try {
            imageGenerationService.switchGenerator(type);
            result.put("success", true);
            result.put("message", "图像生成器已切换为: " + type);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 数据转换方法 ====================

    /** 当前请求是否已登录（服务层另有 userId 归属校验，此处仅拦匿名） */
    private boolean isLoggedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName());
    }

    /**
     * 将 StoryShot 实体转换为 Map
     */
    private Map<String, Object> shotToMap(StoryShot shot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", shot.getId());
        map.put("scriptId", shot.getScriptId());
        map.put("shotOrder", shot.getShotOrder());
        map.put("imagePrompt", shot.getImagePrompt());
        map.put("imageUrl", shot.getImageUrl());
        map.put("imageFileId", shot.getImageFileId());
        map.put("imageStatus", shot.getImageStatus());
        map.put("qualityScore", shot.getQualityScore());
        // 切片 2：与 StoryScriptService.shotToMap 装配点一致，暴露 video 状态
        map.put("videoUrl", shot.getVideoUrl());
        map.put("videoFileId", shot.getVideoFileId());
        map.put("videoStatus", shot.getVideoStatus() != null ? shot.getVideoStatus() : "NONE");
        map.put("lastFrameUrl", shot.getLastFrameUrl());
        return map;
    }
}
