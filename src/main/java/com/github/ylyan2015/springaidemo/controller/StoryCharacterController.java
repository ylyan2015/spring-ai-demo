package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.StoryCharacterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色卡控制器（Phase 4 切片 1）
 * CRUD + 定妆参考图上传 + 声线列表；归属校验在服务层（script.userId）
 */
@RestController
@RequestMapping("/api/video/characters")
public class StoryCharacterController {

    private static final Logger log = LoggerFactory.getLogger(StoryCharacterController.class);

    private final StoryCharacterService storyCharacterService;

    public StoryCharacterController(StoryCharacterService storyCharacterService) {
        this.storyCharacterService = storyCharacterService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", storyCharacterService.listByScript(scriptId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestParam Long scriptId,
                                                      @RequestBody Map<String, String> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", storyCharacterService.create(scriptId, request.get("name"),
                    request.get("featureTable"), request.get("personality"), request.get("voice"),
                    request.get("loraName"), request.get("triggerWord")));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestParam Long scriptId,
                                                      @RequestBody Map<String, String> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", storyCharacterService.update(scriptId, id, request.get("name"),
                    request.get("featureTable"), request.get("personality"), request.get("voice"),
                    request.get("loraName"), request.get("triggerWord")));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id,
                                                      @RequestParam Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            storyCharacterService.delete(scriptId, id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/{id}/reference-image")
    public ResponseEntity<Map<String, Object>> uploadReference(@PathVariable Long id,
                                                               @RequestParam Long scriptId,
                                                               @RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", storyCharacterService.uploadReferenceImage(scriptId, id,
                    file.getInputStream(), file.getOriginalFilename(), file.getContentType()));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Reference image upload failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/voices")
    public ResponseEntity<Map<String, Object>> voices() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", storyCharacterService.listVoices());
        return ResponseEntity.ok(result);
    }
}