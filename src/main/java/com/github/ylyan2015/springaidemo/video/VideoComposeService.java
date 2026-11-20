package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.exception.StorageException;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
public class VideoComposeService {

    private static final Logger log = LoggerFactory.getLogger(VideoComposeService.class);

    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int FPS = 30;

    /** xfade 转场时长（秒）：字幕时间轴等模块需引用此常量保持与合成时长严格对齐 */
    public static final double XFADE_DURATION = 0.8;

    /** 音轨床单段下限：极短镜头扣完转场余量后不得为 0/负，否则 ffmpeg whole_dur/d= 报错 */
    static final double MIN_AUDIO_BED_SECONDS = 0.25;

    private final StorageService storageService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${server.port:8080}")
    private int serverPort;

    /** 单次 FFmpeg 命令超时（分钟），1080p 多镜头合成耗时较长 */
    @Value("${video.compose-timeout-minutes:8}")
    private int composeTimeoutMinutes;

    /**
     * FFmpeg 可执行文件路径。默认裸名 ffmpeg，依赖系统 PATH；
     * 指向绝对路径时可在未配置 PATH 的机器上直接调用。
     */
    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /** ffprobe 可执行文件路径（Phase 4 切片 2：实测时长回写） */
    @Value("${video.ffprobe-path:ffprobe}")
    private String ffprobePath;

    /** Task 5：出处水印字体文件路径（空=不带 fontfile；中文渲染需配置如 C:/Windows/Fonts/msyh.ttc，B0 教训） */
    @Value("${video.commentary.attribution-font:}")
    private String attributionFontPath;

    /**
     * 运行 edge-tts 的 Python 解释器命令。Windows 上 PATH 里的裸 python 常被微软商店的
     * "应用执行别名" 桩挡住（退出码 49），此时必须填绝对路径。
     */
    @Value("${video.python-command:python}")
    private String pythonCommand;

    /**
     * 旁白声线与语速（edge-tts）。实测 2026-11-17：YunyangNeural +0% ≈ 4.78 字/秒、
     * YunxiNeural +0% ≈ 4.45 字/秒，每 +10% 约多 0.45 字/秒。默认保持存量行为（+0%）。
     */
    @Value("${video.tts.narration-voice:zh-CN-YunxiNeural}")
    private String ttsNarrationVoice;

    @Value("${video.tts.narration-rate:+0%}")
    private String ttsNarrationRate;

    @Value("${video.tts.dialogue-rate:+0%}")
    private String ttsDialogueRate;

    /** Phase 4 切片 4：Real-ESRGAN ncnn-vulkan 可执行路径（空=跳过超分） */
    @Value("${video.realesrgan-path:}")
    private String realesrganPath;

    @Value("${video.realesrgan-model:realesrgan-x4plus-anime}")
    private String realesrganModel;

    /** Phase 4 切片 4：RIFE ncnn-vulkan 可执行路径（空=跳过补帧） */
    @Value("${video.rife-path:}")
    private String rifePath;

    /** RIFE 模型目录 */
    @Value("${video.rife-model:}")
    private String rifeModel;

    /** 运镜缩放幅度（占画面比例）；越小画面越稳 */
    @Value("${video.motion.zoom-amount:0.06}")
    private double zoomAmount = 0.06;

    /** zoompan 超采样倍数上限：先放大再 zoom 消除逐帧整数截断抖动；1=关闭（更快但有抖动）；
     *  实际倍数按分辨率自适应封顶中间长边 ≤3840（1080p→2×/720p→3×/480p→4×），控内存与耗时 */
    @Value("${video.motion.supersample-factor:4}")
    private int supersampleFactor = 4;

    /** 超采样中间分辨率长边上限（4K）：防 1080p×4 达 7680×4320 的内存/超时风险（审查 Major） */
    private static final int MAX_INTERMEDIATE_LONG_EDGE = 3840;

    public VideoComposeService(StorageService storageService) {
        this.storageService = storageService;
    }

    /** 启动时校验 FFmpeg 可用性，缺失时给出清晰指引（release 审查 M10；不阻断启动，非视频功能不受影响） */
    @jakarta.annotation.PostConstruct
    public void checkFfmpegAvailable() {
        if (!probeVersion(ffmpegPath, "-version")) {
            log.warn("FFmpeg 不可用（配置: {}）。视频导出将失败：请安装 FFmpeg ≥4.3 并加入 PATH，"
                    + "或设置环境变量 FFMPEG_PATH / FFPROBE_PATH 指向绝对路径", ffmpegPath);
        }
    }

    /**
     * Compose a video from shot data: download images → ffmpeg → optional TTS → final video
     * (Legacy entry: fixed 1280x720, no watermark, no BGM)
     */
    public Map<String, Object> compose(List<Map<String, Object>> shots) {
        return compose(shots, new ComposeOptions());
    }

    /**
     * Compose a video with custom options (resolution / aspect ratio / watermark / BGM)
     */
    public Map<String, Object> compose(List<Map<String, Object>> shots, ComposeOptions options) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("video-compose-");
            log.info("Video compose work dir: {} ({}x{}, crf={}, watermark={}, bgm={})",
                    workDir, options.getWidth(), options.getHeight(), options.getCrf(),
                    options.getWatermark(), options.getBgmPath());

            MediaList media = downloadShotsMedia(shots, workDir);
            if (media.paths().size() != shots.size()) {
                throw new RuntimeException("Failed to download all shot media ("
                        + media.paths().size() + "/" + shots.size() + ")");
            }
            
            List<Integer> durations = new ArrayList<>();
            for (int i = 0; i < shots.size(); i++) {
                if (Boolean.TRUE.equals(media.isVideo().get(i))) {
                    // 真片段：ffprobe 实测时长（字幕/BGM 时间轴按实际时长对齐）
                    durations.add(Math.max(2, probeDurationSeconds(media.paths().get(i))));
                } else {
                    // 缺省/非法时长兜底 5s，防 NPE→500（release 审查 Minor#11）
                    durations.add(shots.get(i).get("durationSeconds") instanceof Number n ? n.intValue() : 5);
                }
            }
            // Phase 4 切片 4：对白/旁白分行多声线（对白在前，声线取角色卡 voice；旁白默认 Yunxi）
            List<List<TtsLine>> linesPerShot = new ArrayList<>();
            for (Map<String, Object> s : shots) {
                List<TtsLine> lines = new ArrayList<>();
                String dialogue = (String) s.getOrDefault("dialogue", "");
                if (dialogue != null && !dialogue.isBlank()) {
                    lines.add(new TtsLine(dialogue, (String) s.get("dialogueVoice"), ttsDialogueRate));
                }
                String narration = (String) s.getOrDefault("narration", "");
                if (narration != null && !narration.isBlank()) {
                    lines.add(new TtsLine(narration, ttsNarrationVoice, ttsNarrationRate));
                }
                linesPerShot.add(lines);
            }
            // 运镜字段贯穿到滤镜层：固定=静态、推/拉/pan 按设置生效（不再按序号奇偶交替）
            List<String> movements = shots.stream()
                    .map(s -> (String) s.getOrDefault("cameraMovement", ""))
                    .toList();

            boolean hasNarration = linesPerShot.stream().anyMatch(ls -> !ls.isEmpty());
            boolean ttsAvailable = hasNarration && isEdgeTtsAvailable();

            List<Path> audioFiles = null;
            if (ttsAvailable) {
                audioFiles = generateTtsAudio(linesPerShot, durations, workDir);
            }

            // Task 4 合成自动对齐：TTS 预探测——逐镜有效时长取 max(镜头时长, 旁白实测 + 转场余量)，
            // 防旁白比镜头长被 -shortest 截断；真片段短于有效时长时循环填充（needsLoop），静图本就 -loop 1 无限流
            List<Integer> effectiveDurations = new ArrayList<>();
            List<Boolean> needsLoop = new ArrayList<>();
            for (int i = 0; i < durations.size(); i++) {
                int eff = durations.get(i);
                if (audioFiles != null && i < audioFiles.size() && audioFiles.get(i) != null
                        && Files.exists(audioFiles.get(i))) {
                    eff = effectiveDurationFor(eff, probeDurationExact(audioFiles.get(i)),
                            i < durations.size() - 1);
                }
                effectiveDurations.add(eff);
                needsLoop.add(Boolean.TRUE.equals(media.isVideo().get(i)) && durations.get(i) < eff);
            }

            // xfade 转场会吃掉相邻镜头的重叠时长，BGM 淡出与展示时长必须按实际视频时长计算，
            // 否则结尾淡出会被 -shortest 截掉，BGM 以全音量戛然而止
            double exactDuration = media.paths().size() == 1
                    ? effectiveDurations.get(0)
                    : effectiveDurations.stream().mapToInt(Integer::intValue).sum()
                        - (media.paths().size() - 1) * XFADE_DURATION;
            int totalDuration = (int) Math.round(exactDuration);

            Path videoOnly = composeVideoWithFfmpeg(media.paths(), effectiveDurations, movements, media.isVideo(),
                    workDir, options, exactDuration, needsLoop);

            Path finalVideo;
            boolean hasNarrationTrack = false;
            if (audioFiles != null && !audioFiles.isEmpty()) {
                // 音轨床必须跟画面一样被 xfade 压缩：按整段有效时长拼接时音轨总长 = Σ有效，
                // 而画面只有 Σ有效−(n−1)·xfade，口播会逐镜滞后画面并在末镜被 -shortest 截掉
                Path mergedAudio = concatAudioWithSilence(audioFiles,
                        audioBedDurations(effectiveDurations, XFADE_DURATION), workDir);
                finalVideo = mergeVideoAndAudio(videoOnly, mergedAudio, workDir);
                hasNarrationTrack = true;
            } else {
                finalVideo = videoOnly;
            }

            boolean hasBgm = false;
            if (options.getBgmPath() != null && Files.exists(options.getBgmPath())) {
                finalVideo = mixBgmIntoVideo(finalVideo, options.getBgmPath(), totalDuration,
                        hasNarrationTrack, workDir);
                hasBgm = true;
            } else if (options.getBgmPath() != null) {
                log.warn("BGM file not found, skipping BGM mix: {}", options.getBgmPath());
            }

            // Phase 4 切片 4：音效分层混音（人声100%/BGM15%/SFX30%，BGM 之后叠加）
            boolean hasSfx = false;
            if (options.getSfxTracks() != null && !options.getSfxTracks().isEmpty()) {
                List<SfxSpec> existing = options.getSfxTracks().stream()
                        .filter(t -> t.path() != null && Files.exists(t.path()))
                        .toList();
                if (!existing.isEmpty()) {
                    finalVideo = mixSfxIntoVideo(finalVideo, existing, totalDuration,
                            hasNarrationTrack || hasBgm, workDir);
                    hasSfx = true;
                } else {
                    log.warn("SFX tracks requested but no file exists, skipping SFX mix");
                }
            }

            // 模块 I：可选品牌 Logo 角标叠加（右下角，独立重编码 pass，不影响主合成滤镜链）
            boolean hasLogo = false;
            if (options.getLogoPath() != null && Files.exists(options.getLogoPath())) {
                finalVideo = overlayLogoIntoVideo(finalVideo, options.getLogoPath(), options, workDir);
                hasLogo = true;
            } else if (options.getLogoPath() != null) {
                log.warn("Logo file not found, skipping logo overlay: {}", options.getLogoPath());
            }

            // 模块 F：可选字幕硬烧录。预生成 ASS 按名义声明时长排轴；重排基准必须是名义时长——
            // durations 已含真片段 ffprobe 实测值，拿它对比会漏掉「实测≠声明但无 TTS 抬升」的漂移（P0-1）。
            boolean hasSubtitle = false;
            List<Integer> nominalDurations = nominalDurationsOf(shots);
            String assText = resolveSubtitleAss(options.getSubtitleAss(), options.getSubtitleAssFactory(),
                    nominalDurations, effectiveDurations);
            if (assText != null && !assText.isBlank()) {
                Path assFile = workDir.resolve("subtitle.ass");
                Files.writeString(assFile, assText, StandardCharsets.UTF_8);
                finalVideo = burnSubtitlesIntoVideo(finalVideo, assFile, options, workDir);
                hasSubtitle = true;
            }

            // Phase 4 切片 4：可选超分/补帧增强 pass（失败 warn 跳过不阻断导出，未配置静默跳过）
            boolean hasEnhance = false;
            if (options.isUpscale() || options.isInterpolate()) {
                try {
                    Path enhanced = enhanceVideo(finalVideo, options, workDir,
                            hasNarrationTrack || hasBgm || hasSfx);
                    if (enhanced != null) {
                        finalVideo = enhanced;
                        hasEnhance = true;
                    }
                } catch (Exception e) {
                    log.warn("Enhance pass failed, keeping original video: {}", e.getMessage());
                }
            }

            StoreResult stored = storeVideo(finalVideo, options);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("videoUrl", stored.url());
            result.put("fileSizeBytes", stored.fileSizeBytes());
            result.put("width", options.getWidth());
            result.put("height", options.getHeight());
            result.put("totalDuration", totalDuration);
            // 镜边界（压缩轴）：字幕 cue、音轨床、发布元数据时间戳共用同一条轴，
            // 下游不得再自行用名义时长累加算一套（已两次因此出现字幕/口播漂移）
            result.put("shotStarts", shotStarts(effectiveDurations, XFADE_DURATION));
            result.put("hasNarration", hasNarrationTrack);
            result.put("hasAudio", hasNarrationTrack || hasBgm || hasSfx); // 最终视频是否含音轨
            result.put("hasBgm", hasBgm);
            result.put("hasSfx", hasSfx);
            result.put("hasEnhance", hasEnhance);
            result.put("hasSubtitle", hasSubtitle);
            result.put("hasLogo", hasLogo);
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Video compose failed: {}", e.getMessage(), e);
            throw new RuntimeException("视频合成失败: " + e.getMessage(), e);
        } finally {
            cleanupDir(workDir);
        }
    }

    // ==================== Subtitle Timeline ====================

    /**
     * 字幕重排决策（P0-1）：预生成 ASS 按名义声明时长排轴，有效时长一旦偏离名义值
     * （TTS 预探测抬升 / 真片段实测漂移）就按有效时长重排 cue；无漂移沿用预生成，
     * 避免多付一次 en/bilingual 翻译成本。重排失败/结果空白降级保留预生成，不阻断合成。
     */
    static String resolveSubtitleAss(String precomputedAss, Function<List<Integer>, String> factory,
                                     List<Integer> nominalDurations, List<Integer> effectiveDurations) {
        if (factory == null || nominalDurations.equals(effectiveDurations)) {
            return precomputedAss;
        }
        try {
            String regen = factory.apply(effectiveDurations);
            if (regen != null && !regen.isBlank()) {
                log.info("Subtitle timeline rebuilt on effective durations {} (nominal {})",
                        effectiveDurations, nominalDurations);
                return regen;
            }
        } catch (Exception e) {
            log.warn("Subtitle timeline rebuild failed, keeping pre-computed ASS: {}", e.getMessage());
        }
        return precomputedAss;
    }

    /** 名义声明时长（预生成 ASS 的时间轴基准）：durationSeconds 缺省/非法兜底 5s，与 SubtitleService.durationsOf 同规则 */
    static List<Integer> nominalDurationsOf(List<Map<String, Object>> shots) {
        List<Integer> nominal = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            nominal.add(shot.get("durationSeconds") instanceof Number n ? n.intValue() : 5);
        }
        return nominal;
    }

    // ==================== Media Download ====================

    /** 混合媒体下载结果（Phase 4 切片 2：真片段+静图） */
    private record MediaList(List<Path> paths, List<Boolean> isVideo) {}

    private MediaList downloadShotsMedia(List<Map<String, Object>> shots, Path workDir) {
        List<Path> paths = new ArrayList<>();
        List<Boolean> isVideo = new ArrayList<>();
        for (int i = 0; i < shots.size(); i++) {
            String videoUrl = (String) shots.get(i).get("videoUrl");
            String imageUrl = (String) shots.get(i).get("imageUrl");
            boolean video = videoUrl != null && !videoUrl.isBlank();
            String url = video ? videoUrl : imageUrl;
            if (url == null || url.isBlank()) {
                log.warn("Shot {} has no media URL, skipping", i);
                continue;
            }
            try {
                Path file = workDir.resolve("shot_" + i + (video ? ".mp4" : ".png"));
                downloadImageTo(url, file);
                paths.add(file);
                isVideo.add(video);
                log.info("Downloaded shot {} {}: {}", i, video ? "video" : "image", file.getFileName());
            } catch (Exception e) {
                log.error("Failed to download shot {} media: {}", i, e.getMessage());
            }
        }
        return new MediaList(paths, isVideo);
    }

    /** 下载单张图片到指定文件（含本地相对地址解析，亦可用于视频等任意媒体下载） */
    private void downloadImageTo(String imageUrl, Path targetFile) throws Exception {
        String fullUrl = resolveImageUrl(imageUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Download image failed: HTTP " + response.statusCode());
        }
        try (InputStream is = response.body()) {
            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String resolveImageUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://localhost:" + serverPort + url;
    }

    /**
     * 公共媒体下载入口（模块 I：批量打包下载复用）
     * 通过访问 URL 下载，兼容 local/oss/minio/fastdfs 四种存储后端
     */
    public void downloadMediaTo(String mediaUrl, Path targetFile) throws Exception {
        downloadImageTo(mediaUrl, targetFile);
    }

    // ==================== FFmpeg Video Composition ====================

    /**
     * 运镜滤镜段（接在 scale+crop 之后）：按 cameraMovement 字段映射——
     * 固定/静止=纯静态（无 zoompan）；拉=zoom out；平移/横移=水平 pan；其余（推近/推进/缺省）=zoom in。
     * zoompan 前超采样放大 supersampleFactor 倍、s= 目标尺寸缩回输出：
     * 小图上 zoompan 的 x/y 逐帧整数截断会产生 ±1px 高频抖动（画面“晃”），超采样将截断误差降到亚像素级。
     */
    static String motionFilter(String movement, int frames, int fps, int width, int height,
                               double zoomAmount, int supersampleFactor) {
        String m = movement == null ? "" : movement;
        if (m.contains("固定") || m.contains("静止")) {
            return String.format("fps=%d", fps);
        }
        String pre = "";
        int factor = Math.max(1, Math.min(supersampleFactor, MAX_INTERMEDIATE_LONG_EDGE / Math.max(width, height)));
        if (factor > 1) {
            pre = String.format("scale=iw*%d:ih*%d:flags=lanczos,", factor, factor);
        }
        String za = String.format("%.2f", zoomAmount);
        String top = String.format("%.2f", 1 + zoomAmount);
        String x = "iw/2-(iw/zoom/2)";
        String y = "ih/2-(ih/zoom/2)";
        String z;
        if (m.contains("拉")) {
            z = String.format("max(%s-%s*on/%d,1.0)", top, za, frames);
        } else if (m.contains("平移") || m.contains("横移")) {
            z = top;
            x = String.format("(iw-iw/zoom)*on/%d", frames);
        } else {
            z = String.format("min(1+%s*on/%d,%s)", za, frames, top);
        }
        return pre + String.format("zoompan=z='%s':x='%s':y='%s':d=1:s=%dx%d:fps=%d",
                z, x, y, width, height, fps);
    }

    /** 7 参兼容入口：无循环需求（存量混剪用例/旧调用），needsLoop 全 false */
    private Path composeVideoWithFfmpeg(List<Path> mediaFiles, List<Integer> durations, List<String> movements,
                                        List<Boolean> isVideo, Path workDir, ComposeOptions options,
                                        double exactDuration)
            throws Exception {
        return composeVideoWithFfmpeg(mediaFiles, durations, movements, isVideo, workDir, options, exactDuration,
                Collections.nCopies(mediaFiles.size(), false));
    }

    private Path composeVideoWithFfmpeg(List<Path> mediaFiles, List<Integer> durations, List<String> movements,
                                        List<Boolean> isVideo, Path workDir, ComposeOptions options,
                                        double exactDuration, List<Boolean> needsLoop)
            throws Exception {
        Path output = workDir.resolve("output_video.mp4");

        if (mediaFiles.size() == 1) {
            List<String> attrs = options.getAttributions();
            String attr = attributionFilter(attrs != null && !attrs.isEmpty() ? attrs.get(0) : null,
                    options.getHeight(), attributionFontPath);
            if (Boolean.TRUE.equals(isVideo.get(0))) {
                buildSingleVideoClip(mediaFiles.get(0), durations.get(0), output, options, needsLoop.get(0), attr);
            } else {
                buildSingleImageVideo(mediaFiles.get(0), durations.get(0), movements.get(0), output, options, attr);
            }
            return output;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        for (int i = 0; i < mediaFiles.size(); i++) {
            if (Boolean.TRUE.equals(isVideo.get(i))) {
                // 真片段短于有效时长：-stream_loop -1 循环填充，滤镜段 trim 截断到有效时长
                if (Boolean.TRUE.equals(needsLoop.get(i))) {
                    cmd.add("-stream_loop");
                    cmd.add("-1");
                }
            } else {
                // 静图无限流必须 -loop 1
                cmd.add("-loop");
                cmd.add("1");
            }
            cmd.add("-i");
            cmd.add(mediaFiles.get(i).toAbsolutePath().toString());
        }

        StringBuilder filter = new StringBuilder();

        List<String> attributions = options.getAttributions();
        for (int i = 0; i < mediaFiles.size(); i++) {
            String attr = attributionFilter(attributions != null && i < attributions.size()
                    ? attributions.get(i) : null, options.getHeight(), attributionFontPath);
            if (Boolean.TRUE.equals(isVideo.get(i))) {
                // 真片段：仅对齐比例/帧率，不加 zoompan（运动来自片段本身）；needsLoop 时 trim 截断；出处水印插 setpts 前
                filter.append(videoInputFilter(i, durations.get(i), Boolean.TRUE.equals(needsLoop.get(i)),
                        options.getWidth(), options.getHeight(), FPS, attr));
            } else {
                // 先按目标比例居中裁剪，再按运镜字段加动效（超采样消抖），避免非 16:9 比例下画面变形；出处水印插 setpts 前
                filter.append(String.format(
                        "[%d:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,%s,%s"
                                + "setpts=PTS-STARTPTS,format=yuva420p[v%d];",
                        i, options.getWidth(), options.getHeight(), options.getWidth(), options.getHeight(),
                        motionFilter(movements.get(i), durations.get(i) * FPS, FPS,
                                options.getWidth(), options.getHeight(), zoomAmount, supersampleFactor), attr, i));
            }
        }

        double offset = 0;
        for (int i = 0; i < mediaFiles.size() - 1; i++) {
            offset += durations.get(i);
            double xfadeOffset = offset - (i + 1) * XFADE_DURATION;

            String labelA = (i == 0) ? "[v0]" : "[xf" + (i - 1) + "]";
            String labelB = "[v" + (i + 1) + "]";
            String labelOut = (i < mediaFiles.size() - 2) ? "[xf" + i + "]" : "[vout]";

            // 输出标签必须紧跟在 xfade 之后；放在滤镜名之前会被解析成第三个输入，
            // 报 "More input link labels specified for filter 'xfade' than it has inputs: 3 > 2"
            filter.append(String.format(
                    "%s%sxfade=transition=fade:duration=%.1f:offset=%.2f%s;",
                    labelA, labelB, XFADE_DURATION, Math.max(0, xfadeOffset), labelOut));
        }

        // 可选水印：半透明居中大字（预览版），正式导出不叠加
        String finalLabel = "[vout]";
        if (options.getWatermark() != null && !options.getWatermark().isBlank()) {
            filter.append(String.format(
                    "[vout]drawtext=text='%s':fontsize=%d:fontcolor=white@0.35"
                            + ":x=(w-text_w)/2:y=(h-text_h)/2[vfinal];",
                    options.getWatermark(), Math.max(16, options.getHeight() / 14)));
            finalLabel = "[vfinal]";
        }

        String filterStr = filter.toString();
        if (filterStr.endsWith(";")) {
            filterStr = filterStr.substring(0, filterStr.length() - 1);
        }

        cmd.add("-filter_complex");
        cmd.add(filterStr);

        cmd.add("-map");
        cmd.add(finalLabel);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-crf");
        cmd.add(String.valueOf(options.getCrf()));
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-movflags");
        cmd.add("+faststart");
        // -loop 1 的图片输入是无限流，xfade 也不会自己收尾，必须显式截断时长
        cmd.add("-t");
        cmd.add(String.format("%.2f", exactDuration));
        cmd.add(output.toAbsolutePath().toString());

        executeCommand(cmd, workDir, "ffmpeg video compose");
        return output;
    }

    private void buildSingleImageVideo(Path image, int duration, String movement, Path output, ComposeOptions options,
                                       String attributionSegment)
            throws Exception {
        String attr = attributionSegment == null ? "" : attributionSegment;
        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,%s,%s"
                        + "setpts=PTS-STARTPTS,format=yuva420p",
                options.getWidth(), options.getHeight(), options.getWidth(), options.getHeight(),
                motionFilter(movement, duration * FPS, FPS, options.getWidth(), options.getHeight(),
                        zoomAmount, supersampleFactor), attr);
        if (options.getWatermark() != null && !options.getWatermark().isBlank()) {
            vf += String.format(",drawtext=text='%s':fontsize=%d:fontcolor=white@0.35"
                            + ":x=(w-text_w)/2:y=(h-text_h)/2",
                    options.getWatermark(), Math.max(16, options.getHeight() / 14));
        }
        executeCommand(List.of(
                ffmpegPath, "-y",
                "-loop", "1",
                "-i", image.toAbsolutePath().toString(),
                "-vf", vf,
                "-t", String.valueOf(duration),
                "-c:v", "libx264", "-profile:v", "high", "-crf", String.valueOf(options.getCrf()),
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                output.toAbsolutePath().toString()
        ), image.getParent(), "ffmpeg single image video");
    }

    // ==================== TTS Audio ====================

    /** 包私有测试 seam：混合媒体合成（真片段+静图），委托真实私有合成方法（无循环需求） */
    Path composeMediaForTest(List<Path> mediaFiles, List<Integer> durations, List<String> movements,
                             List<Boolean> isVideo, Path workDir, ComposeOptions options, double exactDuration)
            throws Exception {
        return composeVideoWithFfmpeg(mediaFiles, durations, movements, isVideo, workDir, options, exactDuration);
    }

    /** 包私有测试 seam（Task 4）：带 needsLoop 循环标志的混合媒体合成 */
    Path composeMediaForTest(List<Path> mediaFiles, List<Integer> durations, List<String> movements,
                             List<Boolean> isVideo, Path workDir, ComposeOptions options, double exactDuration,
                             List<Boolean> needsLoop)
            throws Exception {
        return composeVideoWithFfmpeg(mediaFiles, durations, movements, isVideo, workDir, options, exactDuration,
                needsLoop);
    }

    /**
     * 真片段输入滤镜段（Task 4 包私有 static seam）：对齐比例/帧率，不加 zoompan（运动来自片段本身）。
     * needsLoop=true（片段短于有效时长，输入已 -stream_loop -1）时插 trim=duration 截断到有效时长，防无限流饿死下游。
     */
    static String videoInputFilter(int idx, int effectiveSec, boolean needsLoop, int w, int h, int fps) {
        return videoInputFilter(idx, effectiveSec, needsLoop, w, h, fps, "");
    }

    /** 真片段输入滤镜段（Task 5）：attributionSegment 非空时在 setpts 前插出处水印 drawtext */
    static String videoInputFilter(int idx, int effectiveSec, boolean needsLoop, int w, int h, int fps,
                                   String attributionSegment) {
        String trim = needsLoop ? String.format("trim=duration=%d,", effectiveSec) : "";
        String attr = attributionSegment == null ? "" : attributionSegment;
        return String.format("[%d:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=%d,"
                        + "%s%ssetpts=PTS-STARTPTS,format=yuva420p[v%d];",
                idx, w, h, w, h, fps, trim, attr, idx);
    }

    /**
     * 逐镜出处水印滤镜段（Task 5 包私有 static seam）：左下角半透明黑底白字小字。
     * 空/null 返回 ""（无插入镜不叠加）；fontFile 非空时落 fontfile（B0 教训：中文无 fontfile 静默不渲染）。
     * fontFile 为空时同样返回 ""：Windows 实测无 fontfile 时 CJK 渲染为豆腐块（比不渲染更糟），
     * 出处展示改由镜头图内烤字承担；占比护栏仍按 insertAttribution 字段计，不受影响。
     * 返回段以逗号结尾，便于直接插在 setpts 前。
     */
    static String attributionFilter(String text, int h) {
        return attributionFilter(text, h, null);
    }

    static String attributionFilter(String text, int h, String fontFile) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (fontFile == null || fontFile.isBlank()) {
            log.warn("attribution overlay skipped: video.commentary.attribution-font 未配置"
                    + "（B0：CJK drawtext 无 fontfile 渲染豆腐块）；出处请烤入镜头图");
            return "";
        }
        // 审查 Minor#2：滤镜图级+选项级两层解析各消耗一层→运行时 1 个反斜杠落 4 个；冒号转义；单引号换弯引号防断链
        String safe = text.replace("\\", "\\\\\\\\").replace(":", "\\:").replace("'", "\u2019");
        String font = (fontFile != null && !fontFile.isBlank())
                ? ":fontfile='" + fontFile.replace("\\", "/").replace(":", "\\:") + "'" : "";
        // 审查 Major#1：expansion=none 禁用 %{…} 文本展开（用户文案含 %{bogus} 会致整次导出失败/注入）
        return String.format("drawtext=text='%s'%s:expansion=none:fontsize=%d:fontcolor=white@0.9"
                        + ":box=1:boxcolor=black@0.5:boxborderw=6:x=20:y=h-text_h-20,",
                safe, font, Math.max(14, h / 28));
    }

    /** 真片段单镜头：对齐比例/帧率，无 zoompan；needsLoop 时 -stream_loop -1 循环填充 + trim 截断 */
    private void buildSingleVideoClip(Path video, int duration, Path output, ComposeOptions options,
                                      boolean needsLoop, String attributionSegment)
            throws Exception {
        String trim = needsLoop ? String.format("trim=duration=%d,", duration) : "";
        String attr = attributionSegment == null ? "" : attributionSegment;
        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=%d,%s%sformat=yuv420p",
                options.getWidth(), options.getHeight(), options.getWidth(), options.getHeight(), FPS, trim, attr);
        if (options.getWatermark() != null && !options.getWatermark().isBlank()) {
            vf += String.format(",drawtext=text='%s':fontsize=%d:fontcolor=white@0.35"
                            + ":x=(w-text_w)/2:y=(h-text_h)/2",
                    options.getWatermark(), Math.max(16, options.getHeight() / 14));
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        if (needsLoop) {
            cmd.add("-stream_loop");
            cmd.add("-1");
        }
        cmd.add("-i");
        cmd.add(video.toAbsolutePath().toString());
        cmd.add("-vf");
        cmd.add(vf);
        cmd.add("-t");
        cmd.add(String.valueOf(duration));
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-crf");
        cmd.add(String.valueOf(options.getCrf()));
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());
        executeCommand(cmd, video.getParent(), "ffmpeg single video clip");
    }

    /** ffprobe 实测时长（秒，四舍五入取整；失败抛异常） */
    public int probeDurationSeconds(Path video) {
        return (int) Math.round(probeDurationExact(video));
    }

    /** ffprobe 实测时长（秒，精确 double）；输出落盘+先 waitFor，对齐 executeCommand 超时 idiom */
    private double probeDurationExact(Path video) {
        Path outFile = null;
        Process p = null;
        try {
            outFile = Files.createTempFile("ffprobe-", ".txt");
            p = new ProcessBuilder(ffprobePath, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", video.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(outFile.toFile())
                    .start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("ffprobe 超时(30s): " + video.getFileName());
            }
            String out = Files.readString(outFile, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0) {
                throw new RuntimeException("ffprobe failed: " + out);
            }
            return Double.parseDouble(out);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ffprobe 时长失败: " + e.getMessage(), e);
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 抽取视频真末帧（-sseof -0.5 + -update 1 覆盖写至 EOF）供连续性模式使用 */
    public Path extractLastFrame(Path video, Path workDir) {
        try {
            Path out = workDir.resolve("last_" + System.nanoTime() + ".jpg");
            executeCommand(List.of(ffmpegPath, "-y", "-sseof", "-0.5",
                    "-i", video.toAbsolutePath().toString(),
                    "-update", "1", "-q:v", "2", out.toAbsolutePath().toString()),
                    workDir, "ffmpeg extract last frame");
            return out;
        } catch (Exception e) {
            throw new RuntimeException("末帧抽取失败: " + e.getMessage(), e);
        }
    }

    private boolean isEdgeTtsAvailable() {
        // 带超时探测，防挂起进程阻塞请求线程（release 审查 Minor#5）
        if (probeVersion(pythonCommand, "-m", "edge_tts", "--version")) return true;
        if (probeVersion("edge-tts", "--version")) return true;

        log.warn("edge-tts not available via '{}' or 'edge-tts'. "
                + "Install with `pip install edge-tts`, or point video.python-command at a real interpreter.",
                pythonCommand);
        return false;
    }

    private boolean probeVersion(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private List<Path> generateTtsAudio(List<List<TtsLine>> linesPerShot, List<Integer> durations, Path workDir) {
        List<Path> audioFiles = new ArrayList<>();
        Path ttsDir = workDir.resolve("tts");
        try {
            Files.createDirectories(ttsDir);
        } catch (IOException e) {
            log.error("Failed to create TTS dir: {}", e.getMessage());
            return audioFiles;
        }

        for (int i = 0; i < linesPerShot.size(); i++) {
            List<TtsLine> lines = linesPerShot.get(i);
            if (lines == null || lines.isEmpty()) {
                audioFiles.add(null);
                continue;
            }
            try {
                List<Path> lineFiles = new ArrayList<>();
                for (int j = 0; j < lines.size(); j++) {
                    TtsLine line = lines.get(j);
                    if (line.text() == null || line.text().isBlank()) {
                        continue;
                    }
                    Path mp3File = ttsDir.resolve("narration_" + i + "_" + j + ".mp3");
                    executeCommand(buildTtsCommand(pythonCommand, line, mp3File),
                            workDir, "edge-tts shot " + i + " line " + j);
                    lineFiles.add(mp3File);
                }
                if (lineFiles.isEmpty()) {
                    audioFiles.add(null);
                    continue;
                }
                if (lineFiles.size() == 1) {
                    audioFiles.add(lineFiles.get(0));
                    log.info("TTS generated for shot {}: {} line(s)", i, 1);
                    continue;
                }
                // 镜内多行拼接（对白+旁白），镜间拼接沿用 concatAudioWithSilence
                Path shotAudio = ttsDir.resolve("shot_" + i + ".mp3");
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegPath);
                cmd.add("-y");
                StringBuilder filter = new StringBuilder();
                for (int j = 0; j < lineFiles.size(); j++) {
                    cmd.add("-i");
                    cmd.add(lineFiles.get(j).toAbsolutePath().toString());
                    filter.append("[").append(j).append(":a]");
                }
                filter.append("concat=n=").append(lineFiles.size()).append(":v=0:a=1[out]");
                cmd.add("-filter_complex");
                cmd.add(filter.toString());
                cmd.add("-map");
                cmd.add("[out]");
                cmd.add(shotAudio.toAbsolutePath().toString());
                executeCommand(cmd, workDir, "ffmpeg concat tts lines shot " + i);
                audioFiles.add(shotAudio);
                log.info("TTS generated for shot {}: {} line(s)", i, lineFiles.size());
            } catch (Exception e) {
                log.warn("TTS failed for shot {}: {}", i, e.getMessage());
                audioFiles.add(null);
            }
        }
        return audioFiles;
    }

    /** 包私有测试 seam：单行 TTS 命令（voice 空→默认旁白声线 YunxiNeural，无对白时行为与存量一致） */
    static List<String> buildTtsCommand(String pythonCommand, TtsLine line, Path mp3File) {
        String voice = line.voice() == null || line.voice().isBlank() ? "zh-CN-YunxiNeural" : line.voice();
        List<String> cmd = new ArrayList<>(List.of(
                pythonCommand, "-m", "edge_tts",
                "--voice", voice,
                "--text", line.text()));
        if (line.rate() != null && !line.rate().isBlank() && !"+0%".equals(line.rate())) {
            cmd.add("--rate");
            cmd.add(line.rate());
        }
        cmd.add("--write-media");
        cmd.add(mp3File.toAbsolutePath().toString());
        return cmd;
    }

    /**
     * 逐镜有效时长（包私有测试 seam）：音轨床被 xfade 压缩后，「有效时长 ≥ ceil(旁白)」不再等价于
     * 「床长 ≥ 旁白」，旁白贴着名义时长仍会溢出床尾把后续口播整体推后。故后一镜存在转场时，
     * 至少抬到 ceil(旁白 + xfade)；末镜床等于全长，不需余量。名义时长本身已容得下床长时不拉长画面。
     */
    static int effectiveDurationFor(int baseSeconds, double narrationSeconds, boolean hasFollowingTransition) {
        double needed = narrationSeconds + (hasFollowingTransition ? XFADE_DURATION : 0);
        return Math.max(baseSeconds, (int) Math.ceil(needed));
    }

    /**
     * 逐镜在成片时间轴上的起点（秒，包私有测试 seam）：直接累加 {@link #audioBedDurations}，
     * 保证与音轨床边界严格重合；末镜起点 + 末镜全时长 == exactDuration。
     */
    static List<Double> shotStarts(List<Integer> effectiveDurations, double xfadeDuration) {
        List<Double> starts = new ArrayList<>();
        double acc = 0.0;
        for (double bed : audioBedDurations(effectiveDurations, xfadeDuration)) {
            starts.add(acc);
            acc += bed;
        }
        return starts;
    }

    /** 镜起点序列 → CSV（两位小数），供导出记录落库与发布包时间戳复用（跨包，故 public） */
    public static String toStartsCsv(List<Double> starts) {
        if (starts == null || starts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < starts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.2f", starts.get(i)));
        }
        return sb.toString();
    }

    /** CSV → 镜起点序列；null/空串/脏数据均降级为空列表，不因旧记录无该列而炸掉发布链路（跨包，故 public） */
    public static List<Double> parseStartsCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("Ignore malformed shot start value '{}'", part);
            }
        }
        return out;
    }

    /**
     * 旁白音轨床时长（包私有测试 seam）：视频时间轴被 xfade 压缩掉 (n−1)·xfade，
     * 音轨要落在同一轴上，故除末镜外每段床长扣一次转场重叠；末镜后面没有转场，保留全长。
     * 返回值总和与 compose 的 exactDuration 同式，保证口播逐镜起点对齐画面。
     */
    static List<Double> audioBedDurations(List<Integer> effectiveDurations, double xfadeDuration) {
        List<Double> bed = new ArrayList<>();
        int n = effectiveDurations.size();
        for (int i = 0; i < n; i++) {
            double seg = effectiveDurations.get(i);
            if (i < n - 1) {
                seg -= xfadeDuration;
            }
            bed.add(Math.max(MIN_AUDIO_BED_SECONDS, seg));
        }
        return bed;
    }

    private Path concatAudioWithSilence(List<Path> audioFiles, List<Double> durations, Path workDir)
            throws Exception {
        Path output = workDir.resolve("full_audio.mp3");

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        StringBuilder filter = new StringBuilder();
        StringBuilder concatInputs = new StringBuilder();

        for (int i = 0; i < audioFiles.size(); i++) {
            Path audioFile = audioFiles.get(i);
            double duration = durations.get(i);

            if (audioFile != null && Files.exists(audioFile)) {
                cmd.add("-i");
                cmd.add(audioFile.toAbsolutePath().toString());
                filter.append(String.format(
                        "[%d:a]apad=whole_dur=%.2f[a%d];", i, duration, i));
                concatInputs.append(String.format("[a%d]", i));
            } else {
                cmd.add("-f");
                cmd.add("lavfi");
                cmd.add("-i");
                cmd.add(String.format("anullsrc=r=24000:cl=mono:d=%.2f", duration));
                int silentIdx = i;
                concatInputs.append(String.format("[%d:a]", silentIdx));
            }
        }

        filter.append(String.format("%sconcat=n=%d:v=0:a=1[out]",
                concatInputs, audioFiles.size()));

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[out]");
        cmd.add("-c:a");
        cmd.add("libmp3lame");
        cmd.add("-q:a");
        cmd.add("4");
        cmd.add(output.toAbsolutePath().toString());

        executeCommand(cmd, workDir, "ffmpeg audio concat");
        return output;
    }

    private Path mergeVideoAndAudio(Path videoFile, Path audioFile, Path workDir) throws Exception {
        Path output = workDir.resolve("final_video.mp4");

        executeCommand(List.of(
                ffmpegPath, "-y",
                "-i", videoFile.toAbsolutePath().toString(),
                "-i", audioFile.toAbsolutePath().toString(),
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "128k",
                "-shortest",
                "-movflags", "+faststart",
                output.toAbsolutePath().toString()
        ), workDir, "ffmpeg merge video+audio");

        return output;
    }

    // ==================== BGM Mix ====================

    /**
     * 将 BGM 混入视频：音量降至 15%（约 -16dB，不抢旁白），
     * 开头 2s 淡入，结尾 3s 淡出；BGM 短于视频时自动循环
     *
     * @param totalDuration    实际视频时长（秒，已扣除 xfade 转场重叠）
     * @param hasNarrationTrack 视频是否已有旁白音轨，决定 amix 混合还是作为唯一音轨
     */
    private Path mixBgmIntoVideo(Path videoFile, Path bgmFile, int totalDuration,
                                 boolean hasNarrationTrack, Path workDir) throws Exception {
        Path output = workDir.resolve("video_with_bgm.mp4");
        double fadeOutStart = Math.max(0, totalDuration - 3);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(videoFile.toAbsolutePath().toString());
        cmd.add("-stream_loop");
        cmd.add("-1");
        cmd.add("-i");
        cmd.add(bgmFile.toAbsolutePath().toString());

        String bgmFilter = String.format(
                "[1:a]volume=0.15,afade=t=in:st=0:d=2,afade=t=out:st=%.2f:d=3[bg]", fadeOutStart);

        if (hasNarrationTrack) {
            // 已有旁白：BGM 与旁白混合；normalize=0 防止 amix 把旁白音量也减半（需 ffmpeg ≥ 4.4）
            cmd.add("-filter_complex");
            cmd.add(String.format(
                    "%s;[0:a][bg]amix=inputs=2:duration=first:dropout_transition=2:normalize=0[aout]",
                    bgmFilter));
            cmd.add("-map");
            cmd.add("0:v");
            cmd.add("-map");
            cmd.add("[aout]");
        } else {
            // 无旁白：BGM 作为唯一音轨
            cmd.add("-filter_complex");
            cmd.add(bgmFilter);
            cmd.add("-map");
            cmd.add("0:v");
            cmd.add("-map");
            cmd.add("[bg]");
        }
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-shortest");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());

        executeCommand(cmd, workDir, "ffmpeg mix bgm");
        log.info("BGM mixed: {} (video duration={}s, narrationTrack={})",
                output.getFileName(), totalDuration, hasNarrationTrack);
        return output;
    }

    // ==================== Subtitle Burn (模块 F) ====================

    /**
     * 将 ASS 字幕硬烧录进视频画面（subtitles 滤镜，libass）
     * 烧录需重新编码视频流；音频流直接 copy 保持无损
     */
    private Path burnSubtitlesIntoVideo(Path videoFile, Path assFile, ComposeOptions options, Path workDir)
            throws Exception {
        Path output = workDir.resolve("video_with_subtitle.mp4");
        executeCommand(List.of(
                ffmpegPath, "-y",
                "-i", videoFile.toAbsolutePath().toString(),
                // executeCommand 以 workDir 为工作目录，用相对文件名可避开 Windows 盘符冒号转义问题
                "-vf", "subtitles=" + assFile.getFileName(),
                "-c:v", "libx264", "-profile:v", "high",
                "-crf", String.valueOf(options.getCrf()),
                "-pix_fmt", "yuv420p",
                "-c:a", "copy",
                "-movflags", "+faststart",
                output.toAbsolutePath().toString()
        ), workDir, "ffmpeg burn subtitles");
        return output;
    }

    /**
     * 渲染字幕效果预览帧：下载首镜头图片，按目标比例裁剪并烧录 ASS 字幕，输出 PNG
     * （t=0 时刻仅显示第一条字幕，用于确认字体/字号/描边/位置效果）
     */
    public Path renderSubtitlePreview(String imageUrl, String assText, int width, int height, Path workDir)
            throws Exception {
        Path image = workDir.resolve("preview_source.png");
        downloadImageTo(imageUrl, image);

        Path assFile = workDir.resolve("subtitle.ass");
        Files.writeString(assFile, assText, StandardCharsets.UTF_8);

        Path output = workDir.resolve("subtitle_preview.png");
        executeCommand(List.of(
                ffmpegPath, "-y",
                "-i", image.toAbsolutePath().toString(),
                "-vf", String.format(
                        "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,subtitles=%s",
                        width, height, width, height, assFile.getFileName()),
                "-frames:v", "1",
                output.toAbsolutePath().toString()
        ), workDir, "ffmpeg subtitle preview");
        return output;
    }

    // ==================== Social Media Export (模块 I) ====================

    /**
     * 在视频右下角叠加品牌 Logo 角标（overlay 滤镜，独立重编码 pass）
     * Logo 建议为透明底 PNG 且宽度 ≤300px；音频流直接 copy 保持无损
     */
    private Path overlayLogoIntoVideo(Path videoFile, Path logoFile, ComposeOptions options, Path workDir)
            throws Exception {
        Path output = workDir.resolve("video_with_logo.mp4");
        executeCommand(List.of(
                ffmpegPath, "-y",
                "-i", videoFile.toAbsolutePath().toString(),
                "-i", logoFile.toAbsolutePath().toString(),
                "-filter_complex", "[0:v][1:v]overlay=main_w-overlay_w-10:main_h-overlay_h-10",
                "-c:v", "libx264", "-profile:v", "high",
                "-crf", String.valueOf(options.getCrf()),
                "-pix_fmt", "yuv420p",
                "-c:a", "copy",
                "-movflags", "+faststart",
                output.toAbsolutePath().toString()
        ), workDir, "ffmpeg overlay logo");
        return output;
    }

    /**
     * 从已导出视频截取封面帧（模块 I）
     * 内部闭环：下载视频 → FFmpeg -ss 截帧（-q:v 2 高质量 JPEG）→ 上传存储 → 清理临时目录
     *
     * @param videoUrl         已导出视频访问地址
     * @param timeOffsetSeconds 截帧时刻（秒）；timeOffset 传记录的「实际视频时长」可截取中间帧
     * @return 封面图访问地址
     */
    public String renderVideoCover(String videoUrl, double timeOffsetSeconds) throws Exception {
        return renderVideoCover(videoUrl, timeOffsetSeconds, null);
    }

    /**
     * 封面生成 + 可选成片归档：title 非空时走 uploadFinal 存入 final/（标题-封面.jpg）。
     */
    public String renderVideoCover(String videoUrl, double timeOffsetSeconds, String title) throws Exception {
        Path workDir = Files.createTempDirectory("video-cover-");
        try {
            Path video = workDir.resolve("source.mp4");
            downloadImageTo(videoUrl, video);

            Path cover = workDir.resolve("cover.jpg");
            executeCommand(List.of(
                    ffmpegPath, "-y",
                    "-ss", String.format("%.3f", Math.max(0, timeOffsetSeconds)),
                    "-i", video.toAbsolutePath().toString(),
                    "-vframes", "1", "-q:v", "2",
                    cover.toAbsolutePath().toString()
            ), workDir, "ffmpeg video cover");

            String fileId;
            try (var is = Files.newInputStream(cover)) {
                if (title != null && !title.isBlank()) {
                    fileId = storageService.uploadFinal(is, title, "封面", ".jpg", "image/jpeg");
                } else {
                    String fileName = "video_cover_" + System.currentTimeMillis() + ".jpg";
                    fileId = storageService.upload(is, fileName, "image/jpeg");
                }
            }
            String coverUrl = storageService.getAccessUrl(fileId);
            log.info("Video cover rendered at {}s: {}", String.format("%.1f", timeOffsetSeconds), coverUrl);
            return coverUrl;
        } finally {
            cleanupDir(workDir);
        }
    }

    // ==================== Storage ====================

    /** 存储结果：访问地址 + 文件大小 */
    record StoreResult(String url, long fileSizeBytes) {}

    /** 包私有测试 seam：直调 storeVideo 验证 finalTitle 路由 */
    StoreResult storeVideoForTest(Path videoFile, ComposeOptions options) throws IOException {
        return storeVideo(videoFile, options);
    }

    private StoreResult storeVideo(Path videoFile, ComposeOptions options) throws IOException {
        String title = options == null ? null : options.getFinalTitle();
        try (InputStream is = Files.newInputStream(videoFile)) {
            String fileId;
            if (title != null && !title.isBlank()) {
                // 成片归档：final/友好命名（标题-视频.mp4），重名追加时间戳
                fileId = storageService.uploadFinal(is, title, "视频", ".mp4", "video/mp4");
            } else {
                fileId = storageService.upload(is, "video_" + System.currentTimeMillis() + ".mp4", "video/mp4");
            }
            String url = storageService.getAccessUrl(fileId);
            long size = Files.size(videoFile);
            log.info("Video stored: fileId={}, url={}, size={} bytes", fileId, url, size);
            return new StoreResult(url, size);
        }
    }

    // ==================== Compose Options ====================

    /**
     * 合成参数：输出分辨率/比例、画质、预览水印、BGM
     * 比例裁剪由滤镜 scale+crop 完成（支持 16:9 / 9:16 / 1:1）
     */
    // ==================== SFX Mix（Phase 4 切片 4） ====================

    /** 音效轨规格：文件路径 + 视频时间轴起始秒 */
    public record SfxSpec(Path path, double startSeconds) {}

    /**
     * 包私有测试 seam：SFX 混音滤镜。
     * 输入索引从 audioInputCount 起（0=视频既有音轨）；每轨 volume=0.3 + adelay 定位；
     * amix normalize=0 防人声减半；无既有音轨且单轨时首轨作基直接映射。
     */
    static String buildSfxFilter(List<SfxSpec> tracks, int audioInputCount, boolean hasAudio) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<String> labels = new ArrayList<>();
        if (hasAudio) {
            labels.add("[0:a]");
        }
        for (int i = 0; i < tracks.size(); i++) {
            long ms = Math.round(tracks.get(i).startSeconds() * 1000);
            String label = "sfx" + i;
            sb.append("[").append(audioInputCount + i).append(":a]volume=0.3,adelay=")
                    .append(ms).append("|").append(ms).append("[").append(label).append("];");
            labels.add("[" + label + "]");
        }
        if (!hasAudio && tracks.size() == 1) {
            return sb.substring(0, sb.length() - 1);
        }
        // 无既有音轨时首输入是延迟后的首条 SFX，duration=first 会截掉后续音效，
        // 改用 longest（外层 -t totalDuration 兜底）；有既有音轨时首输入为全长音轨，first 正确（审查修复）
        sb.append(String.join("", labels))
                .append("amix=inputs=").append(labels.size())
                .append(":duration=").append(hasAudio ? "first" : "longest")
                .append(":normalize=0[aout]");
        return sb.toString();
    }

    /** 音效分层混音（BGM 之后）：SFX 30% 音量，adelay 按镜头起始定位，保视频流不重编码 */
    private Path mixSfxIntoVideo(Path video, List<SfxSpec> tracks, int totalDuration,
                                 boolean hasAudio, Path workDir) throws Exception {
        Path output = workDir.resolve("video_with_sfx.mp4");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(video.toAbsolutePath().toString());
        for (SfxSpec t : tracks) {
            cmd.add("-i");
            cmd.add(t.path().toAbsolutePath().toString());
        }
        cmd.add("-filter_complex");
        cmd.add(buildSfxFilter(tracks, 1, hasAudio));
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-map");
        cmd.add(hasAudio || tracks.size() > 1 ? "[aout]" : "[sfx0]");
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-t");
        cmd.add(String.valueOf(totalDuration));
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());
        executeCommand(cmd, workDir, "ffmpeg sfx mix");
        log.info("SFX mixed: {} track(s), totalDuration={}s, hasAudio={}", tracks.size(), totalDuration, hasAudio);
        return output;
    }

    // ==================== Enhance Pass（Phase 4 切片 4：超分/补帧） ====================

    /**
     * 包私有测试 seam：增强命令序列（抽帧→realesrgan→可选 rife→重编码保音轨）。
     * rife 默认帧数翻倍，补帧输出帧率=2×原 fps（30→60）保持时长不变；hasAudio=false 时不映射音轨。
     */
    static List<List<String>> buildEnhanceCommands(String ffmpeg, String realesrgan, String rgModel,
                                                   String rife, String rifeModel, Path src, Path workDir,
                                                   int fps, boolean hasAudio) {
        boolean interpolate = rife != null && !rife.isBlank();
        Path frames = workDir.resolve("frames");
        Path up = workDir.resolve("up");
        Path interp = workDir.resolve("interp");
        List<List<String>> cmds = new ArrayList<>();
        cmds.add(List.of(ffmpeg, "-y", "-i", src.toAbsolutePath().toString(), "-q:v", "2",
                frames.resolve("f_%05d.png").toAbsolutePath().toString()));
        cmds.add(List.of(realesrgan, "-i", frames.toAbsolutePath().toString(),
                "-o", up.toAbsolutePath().toString(), "-s", "2", "-n", rgModel));
        if (interpolate) {
            cmds.add(List.of(rife, "-i", up.toAbsolutePath().toString(),
                    "-o", interp.toAbsolutePath().toString(), "-m", rifeModel));
        }
        Path finalFrames = interpolate ? interp : up;
        // rife 帧数翻倍：输出帧率必须同步翻倍，否则成品变速/被 -shortest 截断（审查修复）
        int outFps = interpolate ? fps * 2 : fps;
        List<String> reencode = new ArrayList<>(List.of(ffmpeg, "-y",
                "-framerate", String.valueOf(outFps),
                "-i", finalFrames.resolve("f_%05d.png").toAbsolutePath().toString()));
        if (hasAudio) {
            reencode.addAll(List.of("-i", src.toAbsolutePath().toString(), "-map", "0:v", "-map", "1:a?"));
        } else {
            reencode.addAll(List.of("-map", "0:v"));
        }
        reencode.addAll(List.of("-c:v", "libx264", "-crf", "23", "-pix_fmt", "yuv420p"));
        if (hasAudio) {
            reencode.addAll(List.of("-c:a", "aac", "-shortest"));
        }
        reencode.add(workDir.resolve("enhanced.mp4").toAbsolutePath().toString());
        cmds.add(reencode);
        return cmds;
    }

    /** 增强 pass：未配置对应工具静默跳过；任一命令失败由调用方 catch 后保留原视频 */
    private Path enhanceVideo(Path src, ComposeOptions options, Path workDir, boolean hasAudio) throws Exception {
        boolean doUpscale = options.isUpscale() && realesrganPath != null && !realesrganPath.isBlank();
        boolean doInterpolate = options.isInterpolate() && rifePath != null && !rifePath.isBlank();
        if (!doUpscale) {
            if (doInterpolate) {
                log.warn("补帧依赖超分抽帧链，未配置 video.realesrgan-path，跳过增强");
            }
            return null;
        }
        Files.createDirectories(workDir.resolve("frames"));
        List<List<String>> cmds = buildEnhanceCommands(ffmpegPath, realesrganPath, realesrganModel,
                doInterpolate ? rifePath : "", rifeModel, src, workDir, FPS, hasAudio);
        for (List<String> cmd : cmds) {
            executeCommand(cmd, workDir, "enhance (" + Path.of(cmd.get(0)).getFileName() + ")");
        }
        Path enhanced = workDir.resolve("enhanced.mp4");
        log.info("Enhance pass done: upscale={}, interpolate={}", true, doInterpolate);
        return enhanced;
    }

    public static class ComposeOptions {
        private int width = VIDEO_WIDTH;
        private int height = VIDEO_HEIGHT;
        private int crf = 23;
        private String watermark;
        private Path bgmPath;
        private String subtitleAss;
        /** 按有效时长重排时间轴的 ASS 生成回调（入参=逐镜有效秒数）；仅在有效时长≠声明时长时调用 */
        private java.util.function.Function<List<Integer>, String> subtitleAssFactory;
        private Path logoPath;
        private List<SfxSpec> sfxTracks;
        private boolean upscale;
        private boolean interpolate;
        private List<String> attributions;
        private String finalTitle;

        public ComposeOptions width(int width) { this.width = width; return this; }
        public ComposeOptions height(int height) { this.height = height; return this; }
        public ComposeOptions crf(int crf) { this.crf = crf; return this; }
        public ComposeOptions watermark(String watermark) { this.watermark = watermark; return this; }
        public ComposeOptions bgmPath(Path bgmPath) { this.bgmPath = bgmPath; return this; }
        public ComposeOptions subtitleAss(String subtitleAss) { this.subtitleAss = subtitleAss; return this; }
        public ComposeOptions subtitleAssFactory(
                java.util.function.Function<List<Integer>, String> subtitleAssFactory) {
            this.subtitleAssFactory = subtitleAssFactory; return this; }
        public ComposeOptions logoPath(Path logoPath) { this.logoPath = logoPath; return this; }
        public ComposeOptions sfxTracks(List<SfxSpec> sfxTracks) { this.sfxTracks = sfxTracks; return this; }
        public ComposeOptions upscale(boolean upscale) { this.upscale = upscale; return this; }
        public ComposeOptions interpolate(boolean interpolate) { this.interpolate = interpolate; return this; }
        public ComposeOptions attributions(List<String> attributions) { this.attributions = attributions; return this; }
        public ComposeOptions finalTitle(String finalTitle) { this.finalTitle = finalTitle; return this; }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getCrf() { return crf; }
        public String getWatermark() { return watermark; }
        public Path getBgmPath() { return bgmPath; }
        public String getSubtitleAss() { return subtitleAss; }
        public java.util.function.Function<List<Integer>, String> getSubtitleAssFactory() { return subtitleAssFactory; }
        public Path getLogoPath() { return logoPath; }
        public List<SfxSpec> getSfxTracks() { return sfxTracks; }
        public boolean isUpscale() { return upscale; }
        public boolean isInterpolate() { return interpolate; }
        public List<String> getAttributions() { return attributions; }
        public String getFinalTitle() { return finalTitle; }
    }

    // ==================== Utilities ====================

    private void executeCommand(List<String> command, Path workDir, String taskName) throws Exception {
        log.info("Executing {}: {}", taskName, String.join(" ", command));

        // 输出重定向到文件：避免阻塞读 stdout 导致 waitFor 超时永不生效（release 审查 M8）
        Path outputFile = Files.createTempFile(workDir, "cmd-", ".log");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputFile.toFile());
        Process process = pb.start();
        try {
            boolean finished = process.waitFor(timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException(taskName + " timed out (" + timeout() + ")");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String output = Files.readString(outputFile);
                log.error("{} failed (exit {}):\n{}", taskName, exitCode, output);
                throw new RuntimeException(taskName + " failed (exit " + exitCode + ")");
            }
        } finally {
            // Windows 下 destroyForcibly 后句柄可能延迟释放，删除失败不掩盖主异常（临时目录后续统一清理）
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                log.debug("Cannot delete command output file {}: {}", outputFile, e.getMessage());
            }
        }

        log.info("{} completed successfully", taskName);
    }

    /** 命令超时（默认取配置分钟数；包级字段便于测试注入短超时） */
    private volatile Duration commandTimeout;

    private Duration timeout() {
        Duration t = commandTimeout;
        return t != null ? t : Duration.ofMinutes(composeTimeoutMinutes);
    }

    private void cleanupDir(Path dir) {
        if (dir == null) return;
        // try-with-resources 关闭 Files.walk 流，防 Windows 句柄泄漏（release 审查 Minor#3）
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.debug("Cannot delete {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Cleanup failed for {}: {}", dir, e.getMessage());
        }
    }
}
