package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.dto.ImageGenerateRequest;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateResponse;
import com.github.ylyan2015.springaidemo.entity.ImageRecord;
import com.github.ylyan2015.springaidemo.exception.ImageGenerationException;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 图像生成控制器
 * 提供 AI 图像生成和管理 API 接口
 */
@RestController
@RequestMapping("/api/image")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    /**
     * 生成图像
     * 调用阿里云百炼通义万相模型生成图片，并保存到配置的存储服务
     * 同步返回，超时时间 60 秒
     *
     * @param request 图像生成请求（prompt 必填）
     * @return 生成结果，包含图片访问URL
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateImage(@RequestBody ImageGenerateRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 参数校验
            if (request.getPrompt() == null || request.getPrompt().isBlank()) {
                result.put("success", false);
                result.put("message", "提示词不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            ImageGenerateResponse response = imageService.generateImage(request);

            result.put("success", true);
            result.put("message", "图像生成成功");
            result.put("data", responseToMap(response));
            return ResponseEntity.ok(result);
        } catch (ImageGenerationException e) {
            log.error("❌ 图像生成失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "图像生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        } catch (StorageException e) {
            log.error("❌ 图片存储失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "图片存储失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        } catch (Exception e) {
            log.error("❌ 图像生成异常: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "图像生成异常: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 获取当前用户的图像生成历史
     */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> getImageRecords() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<ImageRecord> records = imageService.getUserImageRecords();
            List<Map<String, Object>> items = records.stream()
                    .map(this::recordToMap)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("records", items);
            result.put("count", items.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取记录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 获取指定会话中的图像生成记录
     */
    @GetMapping("/records/{sessionId}")
    public ResponseEntity<Map<String, Object>> getImageRecordsBySession(@PathVariable String sessionId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<ImageRecord> records = imageService.getUserImageRecordsBySession(sessionId);
            List<Map<String, Object>> items = records.stream()
                    .map(this::recordToMap)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("records", items);
            result.put("count", items.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取记录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 将 ImageGenerateResponse 转为 Map（统一响应格式）
     */
    private Map<String, Object> responseToMap(ImageGenerateResponse response) {
        Map<String, Object> map = new HashMap<>();
        map.put("imageUrl", response.getImageUrl());
        map.put("fileId", response.getFileId());
        map.put("fileName", response.getFileName());
        map.put("storageType", response.getStorageType());
        map.put("prompt", response.getPrompt());
        map.put("fileSize", response.getFileSize());
        map.put("width", response.getWidth());
        map.put("height", response.getHeight());
        map.put("createTime", response.getCreateTime() != null ? response.getCreateTime().toString() : null);
        map.put("sessionId", response.getSessionId());
        return map;
    }

    /**
     * 将 ImageRecord 实体转为 Map（统一响应格式）
     */
    private Map<String, Object> recordToMap(ImageRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.getId());
        map.put("prompt", record.getPrompt());
        map.put("imageUrl", record.getImageUrl());
        map.put("fileId", record.getFileId());
        map.put("fileName", record.getFileName());
        map.put("storageType", record.getStorageType());
        map.put("fileSize", record.getFileSize());
        map.put("width", record.getWidth());
        map.put("height", record.getHeight());
        map.put("sessionId", record.getSessionId());
        map.put("createTime", record.getCreateTime() != null ? record.getCreateTime().toString() : null);
        return map;
    }
}
