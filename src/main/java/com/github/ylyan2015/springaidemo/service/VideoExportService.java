package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /** Phase 4 切片 4：音效推荐总开关（默认关，开启且请求勾选 sfx 才推荐混音） */
    @Value("${video.sfx.enabled:false}")
    private boolean sfxEnabled;

    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final VideoExportRepository exportRepository;
    private final UserRepository userRepository;
    private final VideoComposeService videoComposeService;
    private final BgmService bgmService;
    private final SubtitleService subtitleService;
    private final StoryCharacterRepository characterRepository;
    private final SfxService sfxService;

    public VideoExportService(StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              VideoExportRepository exportRepository,
                              UserRepository userRepository,
                              VideoComposeService videoComposeService,
                              BgmService bgmService,
                              SubtitleService subtitleService,
                              StoryCharacterRepository characterRepository,
                              SfxService sfxService) {
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.exportRepository = exportRepository;
        this.userRepository = userRepository;
        this.videoComposeService = videoComposeService;
        this.bgmService = bgmService;
        this.subtitleService = subtitleService;
        this.characterRepository = characterRepository;
        this.sfxService = sfxService;
    }

    /** Phase 4 切片 4：导出后期增强开关（超分/补帧/音效；存量入口全 false 零变化） */
    public record PostFlags(boolean upscale, boolean interpolate, boolean sfx) {
        static final PostFlags NONE = new PostFlags(false, false, false);
    }

    // ==================== 快速预览（480p） ====================

    /**
     * 生成 480p 低分辨率预览版：crf 28 快速出片 + 半透明水印
     *
     * @param scriptId    脚本ID
     * @param aspectRatio 画面比例（16:9 / 9:16 / 1:1）
     * @param bgmId       使用的 BGM（可选）
     * @param subtitle    是否烧录字幕（无旁白时自动忽略）
     * @param subtitleLanguage 字幕语言 zh/en/bilingual（模块 F）
     */
    public Map<String, Object> exportPreview(Long scriptId, String aspectRatio, Long bgmId, boolean subtitle,
                                             String subtitleLanguage) {
        Long userId = getCurrentUserId();
        return doExport(scriptId, userId, "480p", normalizeAspect(aspectRatio), bgmId, subtitle, false, true,
                subtitleLanguage, PostFlags.NONE);
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
     * @param subtitleLanguage 字幕语言 zh/en/bilingual（模块 F）
     */
    public Map<String, Object> exportHd(Long scriptId, String resolution, String aspectRatio, Long bgmId,
                                         boolean subtitle, boolean withLogo, String subtitleLanguage) {
        return exportHdWithPost(scriptId, resolution, aspectRatio, bgmId, subtitle, withLogo,
                subtitleLanguage, PostFlags.NONE);
    }

    /**
     * 高清导出 + 可选后期增强（Phase 4 切片 4）：超分/补帧/音效按 PostFlags 开启，
     * 存量 exportHd/Agent 工具/平台导出签名与行为不变
     */
    public Map<String, Object> exportHdWithPost(Long scriptId, String resolution, String aspectRatio, Long bgmId,
                                                boolean subtitle, boolean withLogo, String subtitleLanguage,
                                                PostFlags postFlags) {
        Long userId = getCurrentUserId();
        String res = normalizeResolution(resolution);
        return doExport(scriptId, userId, res, normalizeAspect(aspectRatio), bgmId, subtitle, withLogo, false,
                subtitleLanguage, postFlags == null ? PostFlags.NONE : postFlags);
    }

    // ==================== 平台导出（模块 I） ====================

    /**
     * 按社媒平台预设一键导出（抖音/B站/小红书/快手/视频号/YT Shorts）
     * 平台推荐时长超限时仅随响应提示、不拦截（用户可自行决定是否发布长视频）
     */
    public Map<String, Object> exportForPlatform(Long scriptId, String platform, Long bgmId,
                                                 boolean subtitle, boolean withLogo, String subtitleLanguage) {
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
                bgmId, subtitle, withLogo, subtitleLanguage);
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

    // ==================== 批量打包下载（模块 I） ====================

    /** 单次打包上限（防止超大 ZIP 占用过多临时磁盘与带宽） */
    private static final int BATCH_ZIP_MAX = 10;

    /**
     * 校验并加载待打包的导出记录（当前用户归属、导出成功、有视频文件，1-10 条）
     * 在开始流式输出前同步完成校验，避免 ZIP 流中途报错
     */
    public List<VideoExport> prepareBatchZip(List<Long> exportIds) {
        Long userId = getCurrentUserId();
        if (exportIds == null || exportIds.isEmpty()) {
            throw new RuntimeException("请至少选择一条导出记录");
        }
        if (exportIds.size() > BATCH_ZIP_MAX) {
            throw new RuntimeException("单次最多打包 " + BATCH_ZIP_MAX + " 条导出记录");
        }
        List<VideoExport> records = new ArrayList<>();
        for (Long id : exportIds) {
            VideoExport record = exportRepository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> new RuntimeException("导出记录不存在或无权访问: " + id));
            if (!"SUCCESS".equals(record.getStatus())
                    || record.getVideoUrl() == null || record.getVideoUrl().isBlank()) {
                throw new RuntimeException("导出记录 " + id + " 没有可下载的视频文件");
            }
            records.add(record);
        }
        return records;
    }

    /**
     * 流式写出 ZIP：每条记录的视频（+已有封面）+ manifest.txt 清单
     * 媒体通过访问 URL 下载（兼容四种存储后端），临时文件用完即清理
     */
    public void writeBatchZip(List<VideoExport> records, OutputStream out) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("video-batch-zip-");
            ZipOutputStream zos = new ZipOutputStream(out);
            StringBuilder manifest = new StringBuilder();
            manifest.append("AI 短视频工作室 - 批量导出打包\n\n");

            for (int i = 0; i < records.size(); i++) {
                VideoExport record = records.get(i);
                String baseName = zipEntryBaseName(i + 1, record);

                Path videoFile = workDir.resolve(baseName + ".mp4");
                videoComposeService.downloadMediaTo(record.getVideoUrl(), videoFile);
                writeZipEntry(zos, baseName + ".mp4", videoFile);

                manifest.append(baseName).append(".mp4 | ")
                        .append(record.getResolution()).append(" | ")
                        .append(record.getAspectRatio()).append(" | ")
                        .append(record.getDurationSeconds() == null ? "?" : record.getDurationSeconds()).append("s | ")
                        .append("BGM: ").append(record.getBgmName() == null ? "无" : record.getBgmName()).append(" | ")
                        .append("字幕: ").append(Boolean.TRUE.equals(record.getHasSubtitle())
                                ? "已烧录(" + (record.getSubtitleLanguage() == null ? "zh" : record.getSubtitleLanguage()) + ")"
                                : "无").append('\n');

                // 封面可选：下载失败不阻断打包
                if (record.getCoverUrl() != null && !record.getCoverUrl().isBlank()) {
                    try {
                        Path coverFile = workDir.resolve(baseName + "_cover.jpg");
                        videoComposeService.downloadMediaTo(record.getCoverUrl(), coverFile);
                        writeZipEntry(zos, baseName + "_cover.jpg", coverFile);
                    } catch (Exception e) {
                        log.warn("Cover download failed for export {}, skipping: {}", record.getId(), e.getMessage());
                    }
                }
            }

            zos.putNextEntry(new ZipEntry("manifest.txt"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            log.info("Batch ZIP written: {} export(s)", records.size());
        } catch (IOException e) {
            throw new RuntimeException("打包文件写入失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("批量打包失败: " + e.getMessage(), e);
        } finally {
            cleanupDir(workDir);
        }
    }

    /** ZIP 条目基础名：{序号}_{分辨率}_{比例冒号转x}_{导出ID}（避免 Windows 文件名非法字符） */
    static String zipEntryBaseName(int index, VideoExport record) {
        String aspect = record.getAspectRatio() == null ? "16x9" : record.getAspectRatio().replace(':', 'x');
        return String.format("%02d_%s_%s_%d", index, record.getResolution(), aspect, record.getId());
    }

    private void writeZipEntry(ZipOutputStream zos, String entryName, Path file) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    /** 清理打包临时目录 */
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
                                         boolean withLogo, boolean preview, String subtitleLanguage,
                                         PostFlags postFlags) {
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

        // 模块 F：选择烧录且存在旁白时，预生成 ASS 字幕文本（时间轴已按 xfade 对齐，支持多语言）
        String subtitleAss = null;
        if (subtitle) {
            boolean anyNarration = shots.stream()
                    .anyMatch(s -> (s.getNarration() != null && !s.getNarration().isBlank())
                            || (s.getDialogue() != null && !s.getDialogue().isBlank()));
            if (anyNarration) {
                subtitleAss = subtitleService.generateAss(shots, null, subtitleLanguage);
            } else {
                log.info("Subtitle requested but script {} has no narration, skipping", scriptId);
            }
        }

        // 组装合成入参（兼容 VideoComposeService 的 Map 结构）
        List<Map<String, Object>> shotMaps = new ArrayList<>();
        for (StoryShot shot : shots) {
            Map<String, Object> m = toComposeMap(shot);
            // 切片 4：对白声线取角色卡 voice（角色不存在→null→旁白声线）
            if (shot.getDialogueCharacterId() != null) {
                characterRepository.findById(shot.getDialogueCharacterId())
                        .ifPresent(c -> m.put("dialogueVoice", c.getVoice()));
            }
            shotMaps.add(m);
        }

        // Phase 4 切片 4：音效推荐（配置开启且请求勾选才执行；起始秒按镜头累计起始扣 xfade 重叠）
        List<VideoComposeService.SfxSpec> sfxSpecs = new ArrayList<>();
        if (sfxEnabled && postFlags.sfx()) {
            Map<Long, String> recommended = sfxService.recommendForScript(shots);
            double cursor = 0;
            for (StoryShot shot : shots) {
                String category = recommended.get(shot.getId());
                if (category != null) {
                    Path sfxFile = sfxService.getSfxFilePath(category);
                    if (sfxFile != null) {
                        sfxSpecs.add(new VideoComposeService.SfxSpec(sfxFile, cursor));
                    }
                }
                cursor += (shot.getDurationSeconds() == null ? 5 : shot.getDurationSeconds())
                        - VideoComposeService.XFADE_DURATION;
            }
        }

        int[] size = resolveDimensions(resolution, aspectRatio);
        VideoComposeService.ComposeOptions options = new VideoComposeService.ComposeOptions()
                .width(size[0])
                .height(size[1])
                .crf(preview ? 28 : 23)          // 预览低画质快速出片，正式版标准画质
                .bgmPath(bgmFile)
                .subtitleAss(subtitleAss)
                .logoPath(logoFile)
                .sfxTracks(sfxSpecs)
                .upscale(postFlags.upscale())
                .interpolate(postFlags.interpolate());
        if (preview) {
            options.watermark(PREVIEW_WATERMARK);
        }

        ExportMeta meta = new ExportMeta(scriptId, userId, resolution, aspectRatio, size,
                preview, bgmId, bgmName, subtitleAss != null, logoFile != null,
                subtitleAss != null ? SubtitleService.normalizeLanguage(subtitleLanguage) : null);

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

    /** 组装单镜头合成入参；cameraMovement 必须贯穿，否则运镜滤镜回退默认 zoom in（固定镜头仍缩放） */
    static Map<String, Object> toComposeMap(StoryShot shot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageUrl", shot.getImageUrl());
        m.put("durationSeconds", shot.getDurationSeconds() == null ? 5 : shot.getDurationSeconds());
        m.put("narration", shot.getNarration());
        m.put("imagePrompt", shot.getImagePrompt());
        m.put("cameraMovement", shot.getCameraMovement());
        m.put("videoUrl", shot.getVideoUrl());
        m.put("dialogue", shot.getDialogue());
        m.put("dialogueCharacterId", shot.getDialogueCharacterId());
        return m;
    }

    /** 导出记录公共字段（saveExportRecord 参数收敛，避免 16+ 参数方法签名） */
    private record ExportMeta(Long scriptId, Long userId, String resolution, String aspectRatio,
                              int[] size, boolean preview, Long bgmId, String bgmName,
                              boolean hasSubtitle, boolean hasLogo, String subtitleLanguage) {}

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
        export.setSubtitleLanguage(meta.subtitleLanguage());
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
        map.put("subtitleLanguage", export.getSubtitleLanguage());
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
