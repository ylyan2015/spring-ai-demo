package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.VideoExportService;
import com.github.ylyan2015.springaidemo.video.PlatformPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 社交媒体导出控制器（模块 I）
 * 平台预设列表 / 平台一键导出 / 封面截帧
 *
 * <p>批量多平台导出由前端并行调用 POST /export/platform 实现（浏览器 Promise.all），
 * 后端不引入异步任务表与轮询协议；FFmpeg 编码为 CPU 密集型，串行/并行差异有限</p>
 */
@RestController
@RequestMapping("/api/video")
public class SocialMediaExportController {

    private static final Logger log = LoggerFactory.getLogger(SocialMediaExportController.class);

    private final VideoExportService videoExportService;

    public SocialMediaExportController(VideoExportService videoExportService) {
        this.videoExportService = videoExportService;
    }

    /**
     * 平台预设列表（前端下拉数据 + API 自描述）
     * 响应: { success, data: [ { name, displayName, aspectRatio, resolution, width, height, maxDurationSeconds, note } ] }
     */
    @GetMapping("/export/platforms")
    public ResponseEntity<Map<String, Object>> listPlatforms() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", Arrays.stream(PlatformPreset.values())
                .map(PlatformPreset::toMap)
                .toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 按平台预设导出（规格由后端映射，无需手动传比例/分辨率）
     * 请求体: { "scriptId": 1, "platform": "DOUYIN", "bgmId": 3, "subtitle": true, "withLogo": false }
     * 响应 data: exportHd 结果 + platform 规格信息 + durationWarning（时长超推荐上限时）
     */
    @PostMapping("/export/platform")
    public ResponseEntity<Map<String, Object>> exportForPlatform(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = toLong(request.get("scriptId"));
            String platform = (String) request.get("platform");
            Long bgmId = toLong(request.get("bgmId"));
            boolean subtitle = toBoolean(request.get("subtitle"), false);
            boolean withLogo = toBoolean(request.get("withLogo"), false);

            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            if (platform == null || platform.isBlank()) {
                result.put("success", false);
                result.put("message", "platform 不能为空（可选: DOUYIN / BILIBILI / XIAOHONGSHU / KUAISHOU / WEIXIN_VIDEO / YOUTUBE_SHORTS）");
                return ResponseEntity.badRequest().body(result);
            }

            Map<String, Object> data = videoExportService.exportForPlatform(scriptId, platform, bgmId, subtitle, withLogo);
            result.put("success", true);
            result.put("data", data);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Platform export failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "平台导出失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 从已导出视频截取封面帧（默认取最近一次成功导出的中间帧）
     * 请求体: { "scriptId": 1, "exportId": 12, "timeOffset": 3.5 }  — exportId / timeOffset 可选
     * 响应 data: { coverUrl, timeOffsetSeconds, exportId }
     */
    @PostMapping("/cover/generate")
    public ResponseEntity<Map<String, Object>> generateCover(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = toLong(request.get("scriptId"));
            Long exportId = toLong(request.get("exportId"));
            Double timeOffset = toDouble(request.get("timeOffset"));

            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            Map<String, Object> data = videoExportService.generateCover(scriptId, exportId, timeOffset);
            result.put("success", true);
            result.put("data", data);
            result.put("message", "封面已生成");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Cover generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "封面生成失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 内部工具 ====================

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) return b;
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return defaultValue;
    }
}
