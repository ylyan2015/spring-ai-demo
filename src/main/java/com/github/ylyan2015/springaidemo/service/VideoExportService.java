package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import com.github.ylyan2015.springaidemo.video.PlatformPreset;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 视频预览与导出服务类
 * 快速预览（480p 低分辨率 + 水印，快速确认效果）与高清导出（720p / 1080p），
 * 支持 16:9 / 9:16 / 1:1 多比例、可选 BGM 混音、字幕烧录与品牌 Logo 角标，
 * 平台预设导出与封面截帧（模块 I），导出记录可追溯
 */
@Service
public class VideoExportService {

    private static final Logger log = LoggerFactory.getLogger(VideoExportService.class);

    /** 预览版水印文字 */
    private static final String PREVIEW_WATERMARK = "PREVIEW";

    /** 品牌 Logo 角标图片路径（模块 I；留空表示未配置，withLogo 请求自动忽略） */
    @Value("${video.watermark.logo-path:}")
    private String logoPathConfig;

    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final VideoExportRepository exportRepository;
    private final UserRepository userRepository;
    private final VideoComposeService videoComposeService;
    private final BgmService bgmService;
    private final SubtitleService subtitleService;

    public VideoExportService(StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              VideoExportRepository exportRepository,
                              UserRepository userRepository,
                              VideoComposeService videoComposeService,
                              BgmService bgmService,
                              SubtitleService subtitleService) {
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.exportRepository = exportRepository;
        this.userRepository = userRepository;
        this.videoComposeService = videoComposeService;
        this.bgmService = bgmService;
        this.subtitleService = subtitleService;
    }

    // ==================== 快速预览（480p） ====================

    /**
     * 生成 480p 低分辨率预览版：crf 28 快速出片 + 半透明水印
     *
     * @param scriptId    脚本ID
     * @param aspectRatio 画面比例（16:9 / 9:16 / 1:1）
     * @param bgmId       使用的 BGM（可选）
     * @param subtitle    是否烧录字幕（无旁白时自动忽略）
     */
    public Map<String, Object> exportPreview(Long scriptId, String aspectRatio, Long bgmId, boolean subtitle) {
        Long userId = getCurrentUserId();
        return doExport(scriptId, userId, "480p", normalizeAspect(aspectRatio), bgmId, subtitle, false, true);
    }

    // ==================== 高清导出 ====================

    /**
     * 生成 720p / 1080p 高清正式版（无水印）
     *
     * @param scriptId    脚本ID
     * @param resolution  分辨率档位（720p / 1080p）
     * @param aspectRatio 画面比例（16:9 / 9:16 / 1:1）
     * @param bgmId       使用的 BGM（可选）
     * @param subtitle    是否烧录字幕（无旁白时自动忽略）
     * @param withLogo    是否叠加品牌 Logo 角标（未配置 logo-path 时自动忽略）
     */
    public Map<String, Object> exportHd(Long scriptId, String resolution, String aspectRatio, Long bgmId,
                                         boolean subtitle, boolean withLogo) {
        Long userId = getCurrentUserId();
        String res = normalizeResolution(resolution);
        return doExport(scriptId, userId, res, normalizeAspect(aspectRatio), bgmId, subtitle, withLogo, false);
    }

    // ==================== 平台导出（模块 I） ====================

    /**
     * 按社媒平台预设一键导出（抖音/B站/小红书/快手/视频号/YT Shorts）
     * 平台推荐时长超限时仅随响应提示、不拦截（用户可自行决定是否发布长视频）
     */
    public Map<String, Object> exportForPlatform(Long scriptId, String platform, Long bgmId,
                                                 boolean subtitle, boolean withLogo) {
        Long userId = getCurrentUserId();
        PlatformPreset preset = PlatformPreset.fromName(platform)
                .orElseThrow(() -> new RuntimeException("不支持的平台: " + platform
                        + "（可选: DOUYIN / BILIBILI / XIAOHONGSHU / KUAISHOU / WEIXIN_VIDEO / YOUTUBE_SHORTS）"));
        requireOwnedScript(scriptId, userId);

        // 时长预警按实际输出时长计算（多镜头扣除 xfade 转场重叠）
        String durationWarning = null;
        if (preset.getMaxDurationSeconds() > 0) {
            List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
            int raw = shots.stream()
                    .mapToInt(s -> s.getDurationSeconds() == null ? 5 : s.getDurationSeconds())
                    .sum();
            int actual = shots.size() > 1
                    ? (int) Math.round(raw - (shots.size() - 1) * VideoComposeService.XFADE_DURATION)
                    : raw;
            if (actual > preset.getMaxDurationSeconds()) {
                durationWarning = String.format("视频时长约 %ds，超过 %s 推荐上限 %ds",
                        actual, preset.getDisplayName(), preset.getMaxDurationSeconds());
                log.warn("Script {} duration {}s exceeds {} recommended max {}s",
                        scriptId, actual, preset.getDisplayName(), preset.getMaxDurationSeconds());
            }
        }

        Map<String, Object> data = exportHd(scriptId, preset.getResolution(), preset.getAspectRatio(),
                bgmId, subtitle, withLogo);
        data.put("platform", preset.toMap());
        if (durationWarning != null) {
            data.put("durationWarning", durationWarning);
        }
        return data;
    }

    // ==================== 封面生成（模块 I） ====================

    /**
     * 从已导出视频截取封面帧
     *
     * @param scriptId   脚本ID（校验归属）
     * @param exportId   指定导出记录；null 时取最近一次成功导出
     * @param timeOffset 截帧时刻（秒）；null 时取视频中间帧（比首帧更稳的「精彩帧」近似）
     */
    public Map<String, Object> generateCover(Long scriptId, Long exportId, Double timeOffset) {
        Long userId = getCurrentUserId();
        requireOwnedScript(scriptId, userId);

        VideoExport record;
        if (exportId != null) {
            record = exportRepository.findByIdAndScriptIdAndUserId(exportId, scriptId, userId)
                    .orElseThrow(() -> new RuntimeException("导出记录不存在或无权访问: " + exportId));
        } else {
            record = exportRepository
                    .findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(scriptId, userId, "SUCCESS")
                    .orElseThrow(() -> new RuntimeException("该脚本尚无成功的导出记录，请先导出视频"));
        }
        if (record.getVideoUrl() == null || record.getVideoUrl().isBlank()) {
            throw new RuntimeException("导出记录没有可用的视频文件");
        }

        double t = timeOffset != null && timeOffset >= 0
                ? timeOffset
                : (record.getDurationSeconds() == null ? 0 : record.getDurationSeconds() / 2.0);

        String coverUrl;
        try {
            coverUrl = videoComposeService.renderVideoCover(record.getVideoUrl(), t);
        } catch (Exception e) {
            log.error("Cover generation failed for export {}: {}", record.getId(), e.getMessage(), e);
            throw new RuntimeException("封面生成失败: " + e.getMessage());
        }

        record.setCoverUrl(coverUrl);
        exportRepository.save(record);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("coverUrl", coverUrl);
        data.put("timeOffsetSeconds", t);
        data.put("exportId", record.getId());
        log.info("Cover generated for export {} at {}s", record.getId(), t);
        return data;
    }

    // ==================== 导出历史 ====================

    /**
     * 查询脚本的导出历史
     */
    public List<Map<String, Object>> listExports(Long scriptId) {
        Long userId = getCurrentUserId();
        requireOwnedScript(scriptId, userId);
        return exportRepository.findByScriptIdOrderByCreatedAtDesc(scriptId).stream()
                .map(this::toMap)
                .toList();
    }

    // ==================== 内部实现 ====================

    /**
     * 导出主流程：校验 → 组装镜头 → 合成（含 TTS / BGM）→ 存储与记录
     * 注意：方法内含分钟级 FFmpeg 调用，不加 @Transactional 避免长事务占死数据库连接
     */
    private Map<String, Object> doExport(Long scriptId, Long userId, String resolution,
                                         String aspectRatio, Long bgmId, boolean subtitle,
                                         boolean withLogo, boolean preview) {
        StoryScript script = requireOwnedScript(scriptId, userId);
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        if (shots.isEmpty()) {
            throw new RuntimeException("脚本没有镜头，无法导出");
        }
        List<String> notReady = shots.stream()
                .filter(s -> !"READY".equals(s.getImageStatus()) || s.getImageUrl() == null || s.getImageUrl().isBlank())
                .map(s -> "镜头 " + s.getShotOrder())
                .toList();
        if (!notReady.isEmpty()) {
            throw new RuntimeException("以下镜头尚未生成图片: " + String.join("、", notReady));
        }

        // BGM 校验与文件定位（合成前完成，避免 FFmpeg 跑完才发现 BGM 不可用）
        Path bgmFile = null;
        String bgmName = null;
        if (bgmId != null) {
            bgmFile = bgmService.getBgmFilePath(bgmId, userId);
            bgmName = bgmService.getBgmName(bgmId);
        }

        // 模块 I：品牌 Logo 角标（路径由配置指定，请求可选启用；未配置/文件缺失自动跳过）
        Path logoFile = null;
        if (withLogo) {
            if (logoPathConfig != null && !logoPathConfig.isBlank()) {
                Path p = Path.of(logoPathConfig);
                if (Files.exists(p)) {
                    logoFile = p;
                } else {
                    log.warn("Logo file not found (video.watermark.logo-path={}), skipping logo overlay", logoPathConfig);
                }
            } else {
                log.warn("Logo overlay requested but video.watermark.logo-path is not configured, skipping");
            }
        }

        // 模块 F：选择烧录且存在旁白时，预生成 ASS 字幕文本（时间轴已按 xfade 对齐）
        String subtitleAss = null;
        if (subtitle) {
            boolean anyNarration = shots.stream()
                    .anyMatch(s -> s.getNarration() != null && !s.getNarration().isBlank());
            if (anyNarration) {
                subtitleAss = subtitleService.generateAss(shots, null);
            } else {
                log.info("Subtitle requested but script {} has no narration, skipping", scriptId);
            }
        }

        // 组装合成入参（兼容 VideoComposeService 的 Map 结构）
        List<Map<String, Object>> shotMaps = new ArrayList<>();
        for (StoryShot shot : shots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("imageUrl", shot.getImageUrl());
            m.put("durationSeconds", shot.getDurationSeconds() == null ? 5 : shot.getDurationSeconds());
            m.put("narration", shot.getNarration());
            m.put("imagePrompt", shot.getImagePrompt());
            shotMaps.add(m);
        }

        int[] size = resolveDimensions(resolution, aspectRatio);
        VideoComposeService.ComposeOptions options = new VideoComposeService.ComposeOptions()
                .width(size[0])
                .height(size[1])
                .crf(preview ? 28 : 23)          // 预览低画质快速出片，正式版标准画质
                .bgmPath(bgmFile)
                .subtitleAss(subtitleAss)
                .logoPath(logoFile);
        if (preview) {
            options.watermark(PREVIEW_WATERMARK);
        }

        ExportMeta meta = new ExportMeta(scriptId, userId, resolution, aspectRatio, size,
                preview, bgmId, bgmName, subtitleAss != null, logoFile != null);

        log.info("Exporting script {} as {} {} {} (user {}, bgm={}, subtitle={}, logo={})",
                scriptId, preview ? "preview" : "HD", resolution, aspectRatio, userId, bgmId,
                subtitleAss != null, logoFile != null);

        Map<String, Object> video;
        try {
            video = videoComposeService.compose(shotMaps, options);
        } catch (RuntimeException e) {
            // 失败也记录，历史可追溯失败原因
            saveExportRecord(meta, null, null, null, "FAILED", truncate(e.getMessage(), 500));
            throw e;
        }

        VideoExport record = saveExportRecord(meta, (String) video.get("videoUrl"),
                (Long) video.get("fileSizeBytes"), ((Number) video.get("totalDuration")).intValue(),
                "SUCCESS", null);

        // 高清导出成功后脚本进入 EXPORTED 状态（预览不改变状态）
        if (!preview && !"EXPORTED".equals(script.getStatus())) {
            script.setStatus("EXPORTED");
            scriptRepository.save(script);
        }

        Map<String, Object> result = toMap(record);
        result.put("video", video);
        log.info("Export {} done: url={}, size={} bytes", record.getId(),
                video.get("videoUrl"), video.get("fileSizeBytes"));
        return result;
    }

    /** 导出记录公共字段（saveExportRecord 参数收敛，避免 16+ 参数方法签名） */
    private record ExportMeta(Long scriptId, Long userId, String resolution, String aspectRatio,
                              int[] size, boolean preview, Long bgmId, String bgmName,
                              boolean hasSubtitle, boolean hasLogo) {}

    private VideoExport saveExportRecord(ExportMeta meta, String videoUrl, Long fileSizeBytes,
                                          Integer durationSeconds, String status, String error) {
        VideoExport export = new VideoExport();
        export.setScriptId(meta.scriptId());
        export.setUserId(meta.userId());
        export.setResolution(meta.resolution());
        export.setAspectRatio(meta.aspectRatio());
        export.setWidth(meta.size()[0]);
        export.setHeight(meta.size()[1]);
        export.setVideoUrl(videoUrl);
        export.setFileSizeBytes(fileSizeBytes);
        export.setDurationSeconds(durationSeconds);
        export.setHasWatermark(meta.preview());
        export.setBgmId(meta.bgmId());
        export.setBgmName(meta.bgmName());
        export.setHasSubtitle(meta.hasSubtitle());
        export.setHasLogo(meta.hasLogo());
        export.setStatus(status);
        export.setErrorMessage(error);
        return exportRepository.save(export);
    }

    /** 分辨率档位 × 画面比例 → 实际输出宽高（长边按 16:9 换算并取偶，符合 yuv420p 要求） */
    private int[] resolveDimensions(String resolution, String aspectRatio) {
        int shortSide = switch (resolution) {
            case "480p" -> 480;
            case "1080p" -> 1080;
            default -> 720;
        };
        int longSide = ((shortSide * 16 / 9) + 1) & ~1; // 480→854, 720→1280, 1080→1920
        return switch (aspectRatio) {
            case "9:16" -> new int[]{shortSide, longSide};   // 竖屏（抖音）
            case "1:1" -> new int[]{shortSide, shortSide};    // 方形（小红书）
            default -> new int[]{longSide, shortSide};       // 横屏（B站）
        };
    }

    private String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank() || !"1080p".equals(resolution)) {
            return "720p";
        }
        return resolution;
    }

    private String normalizeAspect(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "16:9";
        }
        return switch (aspectRatio) {
            case "9:16", "1:1" -> aspectRatio;
            default -> "16:9";
        };
    }

    private StoryScript requireOwnedScript(Long scriptId, Long userId) {
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private Map<String, Object> toMap(VideoExport export) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", export.getId());
        map.put("scriptId", export.getScriptId());
        map.put("resolution", export.getResolution());
        map.put("aspectRatio", export.getAspectRatio());
        map.put("width", export.getWidth());
        map.put("height", export.getHeight());
        map.put("videoUrl", export.getVideoUrl());
        map.put("fileSizeBytes", export.getFileSizeBytes());
        map.put("durationSeconds", export.getDurationSeconds());
        map.put("hasWatermark", export.getHasWatermark());
        map.put("bgmId", export.getBgmId());
        map.put("bgmName", export.getBgmName());
        map.put("hasSubtitle", export.getHasSubtitle());
        map.put("hasLogo", export.getHasLogo());
        map.put("coverUrl", export.getCoverUrl());
        map.put("status", export.getStatus());
        map.put("errorMessage", export.getErrorMessage());
        map.put("createdAt", export.getCreatedAt() == null ? null : export.getCreatedAt().toString());
        return map;
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
