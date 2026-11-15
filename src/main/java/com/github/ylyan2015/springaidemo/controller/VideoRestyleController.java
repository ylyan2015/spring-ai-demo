package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.VideoRestyleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频转绘控制器（Phase 4 切片 5：独立输出模式，不触碰主 pipeline）
 * 上传实拍视频（mp4/mov，2-10 秒）→ AI 风格转绘 → 独立任务列表查询
 */
@RestController
@RequestMapping("/api/video/restyle")
public class VideoRestyleController {

    private static final Logger log = LoggerFactory.getLogger(VideoRestyleController.class);

    private final VideoRestyleService videoRestyleService;

    public VideoRestyleController(VideoRestyleService videoRestyleService) {
        this.videoRestyleService = videoRestyleService;
    }

    /**
     * 提交转绘任务（multipart：file 视频 + style 风格提示词，空则默认日系动漫）
     * 同步返回任务（RUNNING），前端 3s 轮询 GET /{id} 至终态
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "style", required = false) String style) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (file == null || file.isEmpty()) {
                result.put("success", false);
                result.put("message", "请上传视频文件");
                return ResponseEntity.badRequest().body(result);
            }
            Map<String, Object> data = videoRestyleService.submit(file.getInputStream(),
                    file.getOriginalFilename(), file.getContentType(), style);
            result.put("success", true);
            result.put("data", data);
            result.put("message", "转绘任务已提交");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Restyle submit failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 任务状态查询（归属校验） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", videoRestyleService.taskStatus(id));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Restyle status failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 我的转绘任务列表 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", videoRestyleService.listMine());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Restyle list failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}