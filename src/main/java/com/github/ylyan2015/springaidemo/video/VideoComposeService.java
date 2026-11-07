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
    private static final double XFADE_DURATION = 0.8;

    private final StorageService storageService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${server.port:8080}")
    private int serverPort;

    public VideoComposeService(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Compose a video from shot data: download images → ffmpeg → optional TTS → final video
     */
    public Map<String, Object> compose(List<Map<String, Object>> shots) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("video-compose-");
            log.info("Video compose work dir: {}", workDir);

            List<Path> imageFiles = downloadImages(shots, workDir);
            if (imageFiles.size() != shots.size()) {
                throw new RuntimeException("Failed to download all shot images ("
                        + imageFiles.size() + "/" + shots.size() + ")");
            }

            List<Integer> durations = shots.stream()
                    .map(s -> ((Number) s.get("durationSeconds")).intValue())
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

            Path videoOnly = composeVideoWithFfmpeg(imageFiles, durations, workDir);

            Path finalVideo;
            if (audioFiles != null && !audioFiles.isEmpty()) {
                Path mergedAudio = concatAudioWithSilence(audioFiles, durations, workDir);
                finalVideo = mergeVideoAndAudio(videoOnly, mergedAudio, workDir);
            } else {
                finalVideo = videoOnly;
            }

            String videoUrl = storeVideo(finalVideo);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("videoUrl", videoUrl);
            result.put("width", VIDEO_WIDTH);
            result.put("height", VIDEO_HEIGHT);
            result.put("totalDuration", durations.stream().mapToInt(Integer::intValue).sum());
            result.put("hasAudio", ttsAvailable);
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
                String fullUrl = resolveImageUrl(imageUrl);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.error("Download shot {} failed: HTTP {}", i, response.statusCode());
                    continue;
                }

                try (InputStream is = response.body()) {
                    Files.copy(is, imgFile, StandardCopyOption.REPLACE_EXISTING);
                }
                paths.add(imgFile);
                log.info("Downloaded shot {} image: {}", i, imgFile.getFileName());
            } catch (Exception e) {
                log.error("Failed to download shot {} image: {}", i, e.getMessage());
            }
        }
        return paths;
    }

    private String resolveImageUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://localhost:" + serverPort + url;
    }

    // ==================== FFmpeg Video Composition ====================

    private Path composeVideoWithFfmpeg(List<Path> images, List<Integer> durations, Path workDir)
            throws Exception {
        Path output = workDir.resolve("output_video.mp4");

        if (images.size() == 1) {
            buildSingleImageVideo(images.get(0), durations.get(0), output);
            return output;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");

        for (Path img : images) {
            cmd.add("-loop");
            cmd.add("1");
            cmd.add("-i");
            cmd.add(img.toAbsolutePath().toString());
        }

        StringBuilder filter = new StringBuilder();

        for (int i = 0; i < images.size(); i++) {
            int totalFrames = durations.get(i) * FPS;
            double zoomEnd = 1.0 + 0.06 * (i % 2 == 0 ? 1 : -1);
            String zoomExpr = i % 2 == 0
                    ? String.format("min(1+0.06*t/%d,1.06)", durations.get(i))
                    : String.format("max(1.06-0.06*t/%d,1.0)", durations.get(i));

            filter.append(String.format(
                    "[%d:v]zoompan=z='%s':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=%dx%d:fps=%d,"
                            + "setpts=PTS-STARTPTS,format=yuva420p[v%d];",
                    i, zoomExpr, VIDEO_WIDTH, VIDEO_HEIGHT, FPS, i));
        }

        double offset = 0;
        for (int i = 0; i < images.size() - 1; i++) {
            offset += durations.get(i);
            double xfadeOffset = offset - (i + 1) * XFADE_DURATION;

            int srcA = (i == 0) ? 0 : -1;
            String labelA = (i == 0) ? "[v0]" : "[xf" + (i - 1) + "]";
            String labelB = "[v" + (i + 1) + "]";
            String labelOut = (i < images.size() - 2) ? "[xf" + i + "]" : "[vout]";

            filter.append(String.format(
                    "%s%s%sxfade=transition=fade:duration=%.1f:offset=%.2f%s;",
                    labelA, labelB, labelOut, XFADE_DURATION, Math.max(0, xfadeOffset),
                    (i == images.size() - 2) ? "" : ""));
        }

        String filterStr = filter.toString();
        if (filterStr.endsWith(";")) {
            filterStr = filterStr.substring(0, filterStr.length() - 1);
        }

        cmd.add("-filter_complex");
        cmd.add(filterStr);

        cmd.add("-map");
        cmd.add("[vout]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-crf");
        cmd.add("23");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());

        executeCommand(cmd, workDir, "ffmpeg video compose");
        return output;
    }

    private void buildSingleImageVideo(Path image, int duration, Path output) throws Exception {
        int totalFrames = duration * FPS;
        executeCommand(List.of(
                "ffmpeg", "-y",
                "-loop", "1",
                "-i", image.toAbsolutePath().toString(),
                "-vf", String.format(
                        "zoompan=z='min(1+0.06*t/%d,1.06)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=%dx%d:fps=%d,"
                                + "setpts=PTS-STARTPTS,format=yuva420p",
                        duration, VIDEO_WIDTH, VIDEO_HEIGHT, FPS),
                "-t", String.valueOf(duration),
                "-c:v", "libx264", "-profile:v", "high", "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                output.toAbsolutePath().toString()
        ), image.getParent(), "ffmpeg single image video");
    }

    // ==================== TTS Audio ====================

    private boolean isEdgeTtsAvailable() {
        try {
            Process p = new ProcessBuilder("python", "-m", "edge_tts", "--version")
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            if (code == 0) return true;
        } catch (Exception ignored) {}

        try {
            Process p = new ProcessBuilder("edge-tts", "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception ignored) {}

        log.warn("edge-tts not available. Install with: pip install edge-tts");
        return false;
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
                String safeText = text.replace("\"", "\\\"").replace("'", "\\'");
                List<String> cmd = List.of(
                        "python", "-m", "edge_tts",
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
        cmd.add("ffmpeg");
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
                "ffmpeg", "-y",
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

    // ==================== Storage ====================

    private String storeVideo(Path videoFile) throws IOException {
        String fileName = "video_" + System.currentTimeMillis() + ".mp4";
        try (InputStream is = Files.newInputStream(videoFile)) {
            String fileId = storageService.upload(is, fileName, "video/mp4");
            String url = storageService.getAccessUrl(fileId);
            log.info("Video stored: fileId={}, url={}", fileId, url);
            return url;
        }
    }

    // ==================== Utilities ====================

    private void executeCommand(List<String> command, Path workDir, String taskName) throws Exception {
        log.info("Executing {}: {}", taskName, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(taskName + " timed out (5 min)");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("{} failed (exit {}):\n{}", taskName, exitCode, output);
            throw new RuntimeException(taskName + " failed (exit " + exitCode + ")");
        }

        log.info("{} completed successfully", taskName);
    }

    private void cleanupDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
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
