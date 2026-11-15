package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图生视频控制器（Phase 4 切片 2，镜像 ImageGenerationController idiom）
 * 归属校验在服务层；SSE 必须 GET。
 */
@RestController
@RequestMapping("/api/video/videos")
public class VideoGenerationController {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationController.class);

    private final VideoGenerationService videoGenerationService;
    private final StoryScriptService storyScriptService;

    public VideoGenerationController(VideoGenerationService videoGenerationService,
                                     StoryScriptService storyScriptService) {
        this.videoGenerationService = videoGenerationService;
        this.storyScriptService = storyScriptService;
    }

    /** 单镜头同步生成 */
    @PostMapping("/shot/{scriptId}/{shotId}")
    public ResponseEntity<Map<String, Object>> generateShot(@PathVariable Long scriptId,
                                                            @PathVariable Long shotId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            StoryShot shot = videoGenerationService.generateForShot(scriptId, shotId);
            result.put("success", true);
            result.put("data", storyScriptService.shotToMap(shot));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.error("Shot video generation failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 批量生成 SSE 进度（GET） */
    @GetMapping(value = "/script/{scriptId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> generateScriptStream(@PathVariable Long scriptId) {
        return videoGenerationService.generateForScriptStream(scriptId);
    }

    @GetMapping("/generators")
    public ResponseEntity<Map<String, Object>> listGenerators() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", videoGenerationService.listGenerators());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/generator")
    public ResponseEntity<Map<String, Object>> switchGenerator(@RequestParam String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            videoGenerationService.switchGenerator(type);
            result.put("success", true);
            result.put("activeType", type);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}