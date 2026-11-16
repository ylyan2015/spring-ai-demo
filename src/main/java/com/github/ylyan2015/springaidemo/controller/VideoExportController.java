package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.service.VideoExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

/**
 * 视频预览与导出控制器
 * 快速预览（480p + 水印）、高清导出（720p / 1080p，多比例，可选 BGM）与导出历史
 */
@RestController
@RequestMapping("/api/video")
public class VideoExportController {

    private static final Logger log = LoggerFactory.getLogger(VideoExportController.class);

    private final VideoExportService videoExportService;

    public VideoExportController(VideoExportService videoExportService) {
        this.videoExportService = videoExportService;
    }

    /**
     * 生成 480p 快速预览版（低分辨率 + 水印，秒级出片确认效果）
     * 请求体: { "scriptId": 1, "aspectRatio": "16:9", "bgmId": 3, "subtitle": true }
     */
    @PostMapping("/export/preview")
    public ResponseEntity<Map<String, Object>> exportPreview(@RequestBody Map<String, Object> request) {
        return executeExport(request, true);
    }

    /**
     * 生成高清正式版（无水印）
     * 请求体: { "scriptId": 1, "resolution": "720p", "aspectRatio": "9:16", "bgmId": 3, "subtitle": true,
     *          "upscale": true, "interpolate": false, "sfx": true }（后三者为 Phase 4 切片 4 可选后期增强）
     */
    @PostMapping("/export/hd")
    public ResponseEntity<Map<String, Object>> exportHd(@RequestBody Map<String, Object> request) {
        return executeExport(request, false);
    }

    /**
     * 异步高清导出（Task 6）：提交即返 jobId，后台 worker 合成；GET /export/jobs/{id} 轮询终态。
     * 请求体同 /export/hd。同步 /export/hd 保持不动。
     */
    @PostMapping("/export/hd/async")
    public ResponseEntity<Map<String, Object>> exportHdAsync(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = toLong(request.get("scriptId"));
            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            VideoExportService.PostFlags postFlags = new VideoExportService.PostFlags(
                    toBoolean(request.get("upscale"), false),
                    toBoolean(request.get("interpolate"), false),
                    toBoolean(request.get("sfx"), false));
            Map<String, Object> data = videoExportService.exportHdAsync(scriptId,
                    (String) request.get("resolution"), (String) request.get("aspectRatio"),
                    toLong(request.get("bgmId")), toBoolean(request.get("subtitle"), false),
                    toBoolean(request.get("withLogo"), false), (String) request.get("subtitleLanguage"),
                    postFlags);
            result.put("success", true);
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Async HD export submit failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 异步导出任务状态查询（Task 6）：RUNNING/SUCCESS/FAILED */
    @GetMapping("/export/jobs/{id}")
    public ResponseEntity<Map<String, Object>> exportJobStatus(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", videoExportService.jobStatus(id));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取脚本的导出历史
     */
    @GetMapping("/exports/{scriptId}")
    public ResponseEntity<Map<String, Object>> listExports(@PathVariable Long scriptId) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", videoExportService.listExports(scriptId));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("List exports failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 批量打包下载（模块 I）：将多条导出记录的视频（+已有封面）打包为 ZIP 流式下载
     * 请求体: { "exportIds": [12, 13, 14] }（1-10 条，仅限当前用户的成功导出）
     */
    @PostMapping("/export/batch-download")
    public ResponseEntity<?> batchDownload(@RequestBody Map<String, Object> request) {
        try {
            List<Long> exportIds = new ArrayList<>();
            if (request.get("exportIds") instanceof List<?> list) {
                for (Object item : list) {
                    Long id = toLong(item);
                    if (id != null) exportIds.add(id);
                }
            }
            // 先同步校验（归属/状态/数量），再开始流式输出，避免 ZIP 流中途报错
            List<VideoExport> records = videoExportService.prepareBatchZip(exportIds);
            String fileName = "video_exports_" + System.currentTimeMillis() + ".zip";
            StreamingResponseBody body = out -> videoExportService.writeBatchZip(records, out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);
        } catch (Exception e) {
            log.error("Batch download failed: {}", e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ==================== 内部工具 ====================

    private ResponseEntity<Map<String, Object>> executeExport(
            Map<String, Object> request, boolean preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = toLong(request.get("scriptId"));
            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            Long bgmId = toLong(request.get("bgmId"));
            String aspectRatio = (String) request.get("aspectRatio");
            boolean subtitle = toBoolean(request.get("subtitle"), false);
            boolean withLogo = toBoolean(request.get("withLogo"), false);
            String subtitleLanguage = (String) request.get("subtitleLanguage");

            Map<String, Object> data;
            if (preview) {
                data = videoExportService.exportPreview(scriptId, aspectRatio, bgmId, subtitle, subtitleLanguage);
            } else {
                // Phase 4 切片 4：可选后期增强参数（缺省全 false，存量请求零变化）
                VideoExportService.PostFlags postFlags = new VideoExportService.PostFlags(
                        toBoolean(request.get("upscale"), false),
                        toBoolean(request.get("interpolate"), false),
                        toBoolean(request.get("sfx"), false));
                data = videoExportService.exportHdWithPost(scriptId,
                        (String) request.get("resolution"), aspectRatio, bgmId, subtitle, withLogo,
                        subtitleLanguage, postFlags);
            }

            result.put("success", true);
            result.put("data", data);
            result.put("message", preview ? "预览生成完成" : "高清导出完成");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("{} export failed: {}", preview ? "Preview" : "HD", e.getMessage(), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
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
