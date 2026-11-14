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
import java.util.stream.Stream;

@Service
public class VideoComposeService {

    private static final Logger log = LoggerFactory.getLogger(VideoComposeService.class);

    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int FPS = 30;

    /** xfade 转场时长（秒）：字幕时间轴等模块需引用此常量保持与合成时长严格对齐 */
    public static final double XFADE_DURATION = 0.8;

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

    /**
     * 运行 edge-tts 的 Python 解释器命令。Windows 上 PATH 里的裸 python 常被微软商店的
     * "应用执行别名" 桩挡住（退出码 49），此时必须填绝对路径。
     */
    @Value("${video.python-command:python}")
    private String pythonCommand;

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

            List<Path> imageFiles = downloadImages(shots, workDir);
            if (imageFiles.size() != shots.size()) {
                throw new RuntimeException("Failed to download all shot images ("
                        + imageFiles.size() + "/" + shots.size() + ")");
            }

            List<Integer> durations = shots.stream()
                    // 缺省/非法时长兜底 5s，防 NPE→500（release 审查 Minor#11）
                    .map(s -> s.get("durationSeconds") instanceof Number n ? n.intValue() : 5)
                    .toList();
            List<String> narrations = shots.stream()
                    .map(s -> (String) s.getOrDefault("narration", ""))
                    .toList();

            boolean hasNarration = narrations.stream().anyMatch(n -> n != null && !n.isBlank());
            boolean ttsAvailable = hasNarration && isEdgeTtsAvailable();

            List<Path> audioFiles = null;
            if (ttsAvailable) {
                audioFiles = generateTtsAudio(narrations, durations, workDir);
            }

            // xfade 转场会吃掉相邻镜头的重叠时长，BGM 淡出与展示时长必须按实际视频时长计算，
            // 否则结尾淡出会被 -shortest 截掉，BGM 以全音量戛然而止
            double exactDuration = imageFiles.size() == 1
                    ? durations.get(0)
                    : durations.stream().mapToInt(Integer::intValue).sum()
                        - (imageFiles.size() - 1) * XFADE_DURATION;
            int totalDuration = (int) Math.round(exactDuration);

            Path videoOnly = composeVideoWithFfmpeg(imageFiles, durations, workDir, options, exactDuration);

            Path finalVideo;
            boolean hasNarrationTrack = false;
            if (audioFiles != null && !audioFiles.isEmpty()) {
                Path mergedAudio = concatAudioWithSilence(audioFiles, durations, workDir);
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

            // 模块 I：可选品牌 Logo 角标叠加（右下角，独立重编码 pass，不影响主合成滤镜链）
            boolean hasLogo = false;
            if (options.getLogoPath() != null && Files.exists(options.getLogoPath())) {
                finalVideo = overlayLogoIntoVideo(finalVideo, options.getLogoPath(), options, workDir);
                hasLogo = true;
            } else if (options.getLogoPath() != null) {
                log.warn("Logo file not found, skipping logo overlay: {}", options.getLogoPath());
            }

            // 模块 F：可选字幕硬烧录（调用方预先生成 ASS 文本传入）
            boolean hasSubtitle = false;
            if (options.getSubtitleAss() != null && !options.getSubtitleAss().isBlank()) {
                Path assFile = workDir.resolve("subtitle.ass");
                Files.writeString(assFile, options.getSubtitleAss(), StandardCharsets.UTF_8);
                finalVideo = burnSubtitlesIntoVideo(finalVideo, assFile, options, workDir);
                hasSubtitle = true;
            }

            StoreResult stored = storeVideo(finalVideo);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("videoUrl", stored.url());
            result.put("fileSizeBytes", stored.fileSizeBytes());
            result.put("width", options.getWidth());
            result.put("height", options.getHeight());
            result.put("totalDuration", totalDuration);
            result.put("hasNarration", hasNarrationTrack);
            result.put("hasAudio", hasNarrationTrack || hasBgm); // 最终视频是否含音轨
            result.put("hasBgm", hasBgm);
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

    // ==================== Image Download ====================

    private List<Path> downloadImages(List<Map<String, Object>> shots, Path workDir) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < shots.size(); i++) {
            String imageUrl = (String) shots.get(i).get("imageUrl");
            if (imageUrl == null || imageUrl.isBlank()) {
                log.warn("Shot {} has no image URL, skipping", i);
                continue;
            }
            try {
                Path imgFile = workDir.resolve("shot_" + i + ".png");
                downloadImageTo(imageUrl, imgFile);
                paths.add(imgFile);
                log.info("Downloaded shot {} image: {}", i, imgFile.getFileName());
            } catch (Exception e) {
                log.error("Failed to download shot {} image: {}", i, e.getMessage());
            }
        }
        return paths;
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

    private Path composeVideoWithFfmpeg(List<Path> images, List<Integer> durations, Path workDir,
                                        ComposeOptions options, double exactDuration)
            throws Exception {
        Path output = workDir.resolve("output_video.mp4");

        if (images.size() == 1) {
            buildSingleImageVideo(images.get(0), durations.get(0), output, options);
            return output;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        for (Path img : images) {
            cmd.add("-loop");
            cmd.add("1");
            cmd.add("-i");
            cmd.add(img.toAbsolutePath().toString());
        }

        StringBuilder filter = new StringBuilder();

        for (int i = 0; i < images.size(); i++) {
            // zoompan 的 z 表达式没有 t（秒）变量，只有 on（当前输出帧号）；
            // 跑满 1.06 倍需要 duration*FPS 帧
            String zoomExpr = i % 2 == 0
                    ? String.format("min(1+0.06*on/%d,1.06)", durations.get(i) * FPS)
                    : String.format("max(1.06-0.06*on/%d,1.0)", durations.get(i) * FPS);

            // 先按目标比例居中裁剪，再 zoompan 动效，避免非 16:9 比例下画面变形
            filter.append(String.format(
                    "[%d:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,"
                            + "zoompan=z='%s':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=%dx%d:fps=%d,"
                            + "setpts=PTS-STARTPTS,format=yuva420p[v%d];",
                    i, options.getWidth(), options.getHeight(), options.getWidth(), options.getHeight(),
                    zoomExpr, options.getWidth(), options.getHeight(), FPS, i));
        }

        double offset = 0;
        for (int i = 0; i < images.size() - 1; i++) {
            offset += durations.get(i);
            double xfadeOffset = offset - (i + 1) * XFADE_DURATION;

            String labelA = (i == 0) ? "[v0]" : "[xf" + (i - 1) + "]";
            String labelB = "[v" + (i + 1) + "]";
            String labelOut = (i < images.size() - 2) ? "[xf" + i + "]" : "[vout]";

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

    private void buildSingleImageVideo(Path image, int duration, Path output, ComposeOptions options)
            throws Exception {
        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,"
                        + "zoompan=z='min(1+0.06*on/%d,1.06)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=%dx%d:fps=%d,"
                        + "setpts=PTS-STARTPTS,format=yuva420p",
                options.getWidth(), options.getHeight(), options.getWidth(), options.getHeight(),
                duration * FPS, options.getWidth(), options.getHeight(), FPS);
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

    private List<Path> generateTtsAudio(List<String> narrations, List<Integer> durations, Path workDir) {
        List<Path> audioFiles = new ArrayList<>();
        Path ttsDir = workDir.resolve("tts");
        try {
            Files.createDirectories(ttsDir);
        } catch (IOException e) {
            log.error("Failed to create TTS dir: {}", e.getMessage());
            return audioFiles;
        }

        for (int i = 0; i < narrations.size(); i++) {
            String text = narrations.get(i);
            Path mp3File = ttsDir.resolve("narration_" + i + ".mp3");

            if (text == null || text.isBlank()) {
                audioFiles.add(null);
                continue;
            }

            try {
                List<String> cmd = List.of(
                        pythonCommand, "-m", "edge_tts",
                        "--voice", "zh-CN-YunxiNeural",
                        "--text", text,
                        "--write-media", mp3File.toAbsolutePath().toString()
                );
                executeCommand(cmd, workDir, "edge-tts shot " + i);
                audioFiles.add(mp3File);
                log.info("TTS generated for shot {}: {}", i, mp3File.getFileName());
            } catch (Exception e) {
                log.warn("TTS failed for shot {}: {}", i, e.getMessage());
                audioFiles.add(null);
            }
        }
        return audioFiles;
    }

    private Path concatAudioWithSilence(List<Path> audioFiles, List<Integer> durations, Path workDir)
            throws Exception {
        Path output = workDir.resolve("full_audio.mp3");

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        StringBuilder filter = new StringBuilder();
        StringBuilder concatInputs = new StringBuilder();

        for (int i = 0; i < audioFiles.size(); i++) {
            Path audioFile = audioFiles.get(i);
            int duration = durations.get(i);

            if (audioFile != null && Files.exists(audioFile)) {
                cmd.add("-i");
                cmd.add(audioFile.toAbsolutePath().toString());
                filter.append(String.format(
                        "[%d:a]apad=whole_dur=%d[a%d];", i, duration, i));
                concatInputs.append(String.format("[a%d]", i));
            } else {
                cmd.add("-f");
                cmd.add("lavfi");
                cmd.add("-i");
                cmd.add(String.format("anullsrc=r=24000:cl=mono:d=%d", duration));
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

            String fileName = "video_cover_" + System.currentTimeMillis() + ".jpg";
            String fileId;
            try (var is = Files.newInputStream(cover)) {
                fileId = storageService.upload(is, fileName, "image/jpeg");
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
    private record StoreResult(String url, long fileSizeBytes) {}

    private StoreResult storeVideo(Path videoFile) throws IOException {
        String fileName = "video_" + System.currentTimeMillis() + ".mp4";
        try (InputStream is = Files.newInputStream(videoFile)) {
            String fileId = storageService.upload(is, fileName, "video/mp4");
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
    public static class ComposeOptions {
        private int width = VIDEO_WIDTH;
        private int height = VIDEO_HEIGHT;
        private int crf = 23;
        private String watermark;
        private Path bgmPath;
        private String subtitleAss;
        private Path logoPath;

        public ComposeOptions width(int width) { this.width = width; return this; }
        public ComposeOptions height(int height) { this.height = height; return this; }
        public ComposeOptions crf(int crf) { this.crf = crf; return this; }
        public ComposeOptions watermark(String watermark) { this.watermark = watermark; return this; }
        public ComposeOptions bgmPath(Path bgmPath) { this.bgmPath = bgmPath; return this; }
        public ComposeOptions subtitleAss(String subtitleAss) { this.subtitleAss = subtitleAss; return this; }
        public ComposeOptions logoPath(Path logoPath) { this.logoPath = logoPath; return this; }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getCrf() { return crf; }
        public String getWatermark() { return watermark; }
        public Path getBgmPath() { return bgmPath; }
        public String getSubtitleAss() { return subtitleAss; }
        public Path getLogoPath() { return logoPath; }
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
