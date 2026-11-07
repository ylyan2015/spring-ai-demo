package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.BgmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 背景音乐（BGM）控制器
 * 提供音乐库列表、LLM 智能推荐与自定义音乐上传
 */
@RestController
@RequestMapping("/api/video/bgm")
public class BgmController {

    private static final Logger log = LoggerFactory.getLogger(BgmController.class);

    private final BgmService bgmService;

    public BgmController(BgmService bgmService) {
        this.bgmService = bgmService;
    }

    /**
     * 获取 BGM 列表（内置 + 当前用户上传），可按分类过滤
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listBgm(@RequestParam(required = false) String category) {
        try {
            Long userId = bgmService.requireCurrentUserId();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", bgmService.listBgm(category, userId));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("List BGM failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 根据视频主题智能推荐 BGM（LLM 分析情感风格并匹配分类曲目）
     */
    @GetMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommendBgm(@RequestParam String theme) {
        try {
            Long userId = bgmService.requireCurrentUserId();
            Map<String, Object> data = bgmService.recommendBgm(theme, userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Recommend BGM failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 上传自定义 BGM
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadBgm(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        try {
            Long userId = bgmService.requireCurrentUserId();
            Map<String, Object> data = bgmService.uploadBgm(file, name, category, userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("message", "BGM 上传成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Upload BGM failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
