package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.MediaAssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 素材库控制器（Phase 5 Track B Task 1）
 * 上传（multipart + 可选裁剪）/列表/删除；归属校验在服务层
 */
@RestController
@RequestMapping("/api/video/assets")
public class MediaAssetController {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetController.class);

    private final MediaAssetService mediaAssetService;

    public MediaAssetController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String name,
                                                      @RequestParam(required = false) String tags,
                                                      @RequestParam(required = false) Double trimStart,
                                                      @RequestParam(required = false) Double trimEnd) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", mediaAssetService.upload(file, name, tags, trimStart, trimEnd));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Asset upload failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** Task 6：文件夹批量入库扫描（仅读配置目录 video.commentary.import-dir，不接受用户路径） */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", mediaAssetService.scanImportDir());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.error("Asset scan failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", mediaAssetService.list());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            mediaAssetService.delete(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}