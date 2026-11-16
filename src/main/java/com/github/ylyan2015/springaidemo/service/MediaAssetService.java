package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 素材库服务（Phase 5 Track B Task 1）
 * 上传/入库裁剪 + ffprobe 探测时长；存储一律走 StorageService；归属经服务层 getCurrentUserId 校验
 */
@Service
public class MediaAssetService {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);

    private final MediaAssetRepository assetRepository;
    private final StorageService storageService;
    private final VideoComposeService videoComposeService;
    private final UserRepository userRepository;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /** Task 6：批量入库扫描目录（空=禁用；仅读配置目录，不接受用户路径防遍历） */
    @Value("${video.commentary.import-dir:}")
    private String importDir;

    /** 文件名约定：ep(\d+)_(标签)_(名称).(mp4|png) */
    private static final Pattern ASSET_NAME = Pattern.compile("^ep(\\d+)_([^_]+)_(.+)\\.(mp4|png)$");

    public MediaAssetService(MediaAssetRepository assetRepository, StorageService storageService,
                             VideoComposeService videoComposeService, UserRepository userRepository) {
        this.assetRepository = assetRepository;
        this.storageService = storageService;
        this.videoComposeService = videoComposeService;
        this.userRepository = userRepository;
    }

    /** multipart 上传入库（视频探测时长，可选裁剪；图片跳过探测） */
    public MediaAsset upload(MultipartFile file, String name, String tags, Double trimStart, Double trimEnd) {
        Long userId = getCurrentUserId();
        String contentType = file.getContentType();
        boolean isVideo = contentType != null && contentType.startsWith("video/");
        Path src = null;
        try {
            // 临时文件后缀仅取扩展名并 sanitise（审查 Nit#1：客户端原始文件名含 :/*? 等非法字符会致 InvalidPathException）
            String orig = file.getOriginalFilename();
            String ext = orig != null && orig.lastIndexOf('.') >= 0
                    ? orig.substring(orig.lastIndexOf('.')).replaceAll("[^a-zA-Z0-9.]", "") : "";
            src = Files.createTempFile("asset-", ext);
            file.transferTo(src);
            return ingest(src, name, tags, contentType, isVideo, trimStart, trimEnd, userId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("素材上传失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(src);
        }
    }

    /** 本地路径入库（Task 6 文件夹批量扫描复用；不删除源文件） */
    public MediaAsset ingestPath(Path p, String name, String tags) {
        Long userId = getCurrentUserId();
        String contentType = contentTypeForName(p.getFileName().toString());
        boolean isVideo = contentType.startsWith("video/");
        try {
            return ingest(p, name, tags, contentType, isVideo, null, null, userId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("素材入库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 文件夹批量入库（Task 6）：仅读配置目录（不接受用户路径防遍历）；
     * 约定命名 epNN_标签_名称.(mp4|png) 入库，非法命名跳过；目录未配置/不存在返回空。
     */
    public List<MediaAsset> scanImportDir() {
        if (importDir == null || importDir.isBlank()) {
            return List.of();
        }
        Path dir = Path.of(importDir);
        if (!Files.isDirectory(dir)) {
            log.warn("素材扫描目录不存在: {}", importDir);
            return List.of();
        }
        List<MediaAsset> ingested = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
            for (Path f : sorted) {
                String[] parsed = parseAssetFileName(f.getFileName().toString());
                if (parsed == null) {
                    log.info("跳过非约定命名素材: {}", f.getFileName());
                    continue;
                }
                try {
                    ingested.add(ingestPath(f, parsed[0], parsed[1]));
                } catch (Exception e) {
                    log.warn("素材入库失败 {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("素材扫描失败: " + e.getMessage(), e);
        }
        log.info("素材扫描入库 {} 个（目录 {}）", ingested.size(), importDir);
        return ingested;
    }

    /** 文件名约定解析 seam：ep(\d+)_(标签)_(名称).(mp4|png) → [name, tags="epNN,标签"]；非法返回 null */
    static String[] parseAssetFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = ASSET_NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return new String[]{m.group(3), "ep" + m.group(1) + "," + m.group(2)};
    }

    public List<MediaAsset> list() {
        return assetRepository.findByUserIdOrderByIdDesc(getCurrentUserId());
    }

    public void delete(Long id) {
        Long userId = getCurrentUserId();
        MediaAsset a = assetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + id));
        if (!a.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权删除该素材: " + id);
        }
        assetRepository.delete(a);
        log.info("MediaAsset deleted: id={}, user={}", id, userId);
    }

    // ==================== 包私有测试 seam ====================

    /** 入库裁剪：ffmpeg -ss start -to end -i src（输入级绝对时间轴）→ 无声 H.264 */
    void trimVideo(Path src, double start, double end, Path dst) {
        Process p = null;
        try {
            p = new ProcessBuilder(ffmpegPath, "-y", "-ss", String.valueOf(start), "-to", String.valueOf(end),
                    "-i", src.toAbsolutePath().toString(), "-c:v", "libx264", "-crf", "18", "-an",
                    dst.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("素材裁剪超时(120s): " + src.getFileName());
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("素材裁剪失败 exit=" + p.exitValue());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("素材裁剪失败: " + e.getMessage(), e);
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** 时长探测委托 VideoComposeService.probeDurationSeconds */
    int probe(Path video) {
        return videoComposeService.probeDurationSeconds(video);
    }

    // ==================== 内部方法 ====================

    private MediaAsset ingest(Path src, String name, String tags, String contentType, boolean isVideo,
                              Double trimStart, Double trimEnd, Long userId) throws Exception {
        Integer duration = null;
        Path toUpload = src;
        Path trimmed = null;
        try {
            if (isVideo) {
                duration = probe(src);
                if (trimStart != null || trimEnd != null) {
                    // 审查 Minor#1：非法裁剪区间 fail-fast，不得静默入库全片（下游按 durationSeconds 对齐时间轴）
                    if (trimStart == null || trimEnd == null || trimEnd <= trimStart) {
                        throw new IllegalArgumentException("裁剪参数无效：需同时提供 trimStart/trimEnd 且 trimEnd > trimStart");
                    }
                    trimmed = Files.createTempFile("asset-trim-", ".mp4");
                    trimVideo(src, trimStart, trimEnd, trimmed);
                    duration = probe(trimmed);
                    toUpload = trimmed;
                }
            }
            String fileId;
            try (InputStream in = Files.newInputStream(toUpload)) {
                fileId = storageService.upload(in, src.getFileName().toString(), contentType);
            }
            MediaAsset a = new MediaAsset();
            a.setUserId(userId);
            a.setName(name == null || name.isBlank() ? src.getFileName().toString() : name);
            a.setKind(isVideo ? "VIDEO" : "IMAGE");
            a.setFileId(fileId);
            a.setUrl(storageService.getAccessUrl(fileId));
            a.setDurationSeconds(duration);
            a.setTags(tags);
            MediaAsset saved = assetRepository.save(a);
            log.info("MediaAsset ingested: id={}, kind={}, duration={}, user={}",
                    saved.getId(), saved.getKind(), duration, userId);
            return saved;
        } finally {
            deleteQuietly(trimmed);
        }
    }

    static String contentTypeForName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".mkv")) {
            return "video/mp4";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new RuntimeException("用户未登录");
        }
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
        return user.getId();
    }
}