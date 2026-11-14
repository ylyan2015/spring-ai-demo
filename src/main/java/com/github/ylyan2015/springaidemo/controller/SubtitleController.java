package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 字幕系统控制器（模块 F）
 * 提供 SRT 字幕生成导出与字幕效果预览（预览帧截图）
 */
@RestController
@RequestMapping("/api/video/subtitle")
public class SubtitleController {

    private static final Logger log = LoggerFactory.getLogger(SubtitleController.class);

    private final SubtitleService subtitleService;
    private final VideoComposeService videoComposeService;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final StorageService storageService;
    private final UserRepository userRepository;

    public SubtitleController(SubtitleService subtitleService,
                              VideoComposeService videoComposeService,
                              StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              StorageService storageService,
                              UserRepository userRepository) {
        this.subtitleService = subtitleService;
        this.videoComposeService = videoComposeService;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.storageService = storageService;
        this.userRepository = userRepository;
    }

    /**
     * 生成脚本的 SRT 字幕文件（时间轴按 xfade 转场对齐），上传存储并返回内容
     * 请求体: { "scriptId": 1, "language": "zh" }  — language 可选: zh 原文 / en 英文 / bilingual 中英双语
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateSrt(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = toLong(request.get("scriptId"));
            Long userId = getCurrentUserId();
            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            List<StoryShot> shots = requireOwnedShots(scriptId, userId);
            String language = SubtitleService.normalizeLanguage((String) request.get("language"));
            String srt = subtitleService.generateSrt(shots, language);
            if (srt.isBlank()) {
                result.put("success", false);
                result.put("message", "脚本没有旁白文本，无法生成字幕");
                return ResponseEntity.badRequest().body(result);
            }

            // 上传 SRT 到存储服务（社媒平台可直接使用）
            String fileName = "script_" + scriptId + "_subtitle_" + language + "_" + System.currentTimeMillis() + ".srt";
            String fileId = storageService.upload(
                    new ByteArrayInputStream(srt.getBytes(StandardCharsets.UTF_8)),
                    fileName, "application/x-subrip");
            String srtUrl = storageService.getAccessUrl(fileId);

            int cueCount = srt.split("-->").length - 1;
            log.info("SRT generated: scriptId={}, cues={}, url={}", scriptId, cueCount, srtUrl);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("srtUrl", srtUrl);
            data.put("srtContent", srt);
            data.put("cueCount", cueCount);
            data.put("language", language);
            result.put("success", true);
            result.put("data", data);
            result.put("message", "字幕文件生成成功（" + cueCount + " 条）");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("SRT generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "字幕生成失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 渲染字幕效果预览帧：首镜头图片按目标比例裁剪 + 烧录第一条字幕，输出 PNG
     * 请求体: { "scriptId": 1, "aspectRatio": "16:9", "language": "zh", "style": { "fontSize": 28, "position": "bottom" } }
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> previewSubtitle(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path workDir = null;
        try {
            Long scriptId = toLong(request.get("scriptId"));
            Long userId = getCurrentUserId();
            if (scriptId == null) {
                result.put("success", false);
                result.put("message", "scriptId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            List<StoryShot> shots = requireOwnedShots(scriptId, userId);
            if (shots.get(0).getImageUrl() == null || shots.get(0).getImageUrl().isBlank()) {
                result.put("success", false);
                result.put("message", "首镜头尚未生成图片，无法预览字幕效果");
                return ResponseEntity.badRequest().body(result);
            }

            String ass = subtitleService.generateAss(shots, buildStyle(request),
                    SubtitleService.normalizeLanguage((String) request.get("language")));
            if (ass.isBlank()) {
                result.put("success", false);
                result.put("message", "脚本没有旁白文本，无法预览字幕");
                return ResponseEntity.badRequest().body(result);
            }

            int[] size = previewSize((String) request.get("aspectRatio"));
            workDir = Files.createTempDirectory("subtitle-preview-");
            Path png = videoComposeService.renderSubtitlePreview(
                    shots.get(0).getImageUrl(), ass, size[0], size[1], workDir);

            String fileName = "subtitle_preview_" + System.currentTimeMillis() + ".png";
            String fileId;
            try (var is = Files.newInputStream(png)) {
                fileId = storageService.upload(is, fileName, "image/png");
            }
            String previewUrl = storageService.getAccessUrl(fileId);
            log.info("Subtitle preview rendered: scriptId={}, {}x{}, url={}",
                    scriptId, size[0], size[1], previewUrl);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("previewUrl", previewUrl);
            data.put("width", size[0]);
            data.put("height", size[1]);
            result.put("success", true);
            result.put("data", data);
            result.put("message", "字幕预览已生成");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Subtitle preview failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "字幕预览失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } finally {
            cleanupDir(workDir);
        }
    }

    // ==================== 内部工具 ====================

    /** 校验脚本归属并返回全部镜头 */
    private List<StoryShot> requireOwnedShots(Long scriptId, Long userId) {
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(script.getId());
        if (shots.isEmpty()) {
            throw new RuntimeException("脚本没有镜头: " + scriptId);
        }
        return shots;
    }

    /** 从请求体解析可选样式覆盖（缺省字段保持默认样式） */
    private SubtitleStyle buildStyle(Map<String, Object> request) {
        SubtitleStyle style = subtitleService.defaultStyle();
        if (request.get("style") instanceof Map<?, ?> map) {
            if (map.get("fontName") instanceof String s && !s.isBlank()) style.fontName(s);
            if (map.get("fontSize") instanceof Number n) style.fontSize(n.intValue());
            if (map.get("fontColor") instanceof String s && !s.isBlank()) style.fontColor(s);
            if (map.get("outlineColor") instanceof String s && !s.isBlank()) style.outlineColor(s);
            if (map.get("outlineWidth") instanceof Number n) style.outlineWidth(n.intValue());
            if (map.get("position") instanceof String s && !s.isBlank()) style.position(s);
        }
        return style;
    }

    /** 预览帧尺寸（720p 档位 × 目标比例） */
    private int[] previewSize(String aspectRatio) {
        return switch (aspectRatio == null ? "16:9" : aspectRatio) {
            case "9:16" -> new int[]{720, 1280};
            case "1:1" -> new int[]{720, 720};
            default -> new int[]{1280, 720};
        };
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

    /** 清理预览临时目录 */
    private void cleanupDir(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.debug("Cleanup failed for {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"))
                .getId();
    }
}
