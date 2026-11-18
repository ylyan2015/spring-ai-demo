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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** S1 Task 2：参考素材扫描目录（char_/scene_ 约定，独立于 Track B import-dir；空=禁用） */
    @Value("${video.assets.reference-import-dir:}")
    private String referenceImportDir;

    /** 参考素材文件名约定：(char|scene)_(名称)_(标签).(png|jpg|jpeg|webp)；大小写不敏感（Windows 截图常见 .PNG/Char_，M-1） */
    private static final Pattern REF_NAME =
            Pattern.compile("^(?i)(char|scene)_([^_]+)_(.+)\\.(png|jpg|jpeg|webp)$");

    /** category 合法值（Task 1 Minor#1 并入：入口规范化+白名单，非法值 fail-fast 防脏数据入库） */
    private static final Set<String> VALID_CATEGORIES = Set.of("CHARACTER", "SCENE", "INSERT");

    public MediaAssetService(MediaAssetRepository assetRepository, StorageService storageService,
                             VideoComposeService videoComposeService, UserRepository userRepository) {
        this.assetRepository = assetRepository;
        this.storageService = storageService;
        this.videoComposeService = videoComposeService;
        this.userRepository = userRepository;
    }

    /** multipart 上传入库（视频探测时长，可选裁剪；图片跳过探测）；S1：category 可选，null=INSERT 存量语义 */
    public MediaAsset upload(MultipartFile file, String name, String tags, Double trimStart, Double trimEnd) {
        return upload(file, name, tags, trimStart, trimEnd, null);
    }

    public MediaAsset upload(MultipartFile file, String name, String tags, Double trimStart, Double trimEnd,
                             String category) {
        String normalizedCategory = normalizeCategory(category);
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
            return ingest(src, name, tags, contentType, isVideo, trimStart, trimEnd, userId, normalizedCategory);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("素材上传失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(src);
        }
    }

    /** 本地路径入库（Task 6 文件夹批量扫描复用；不删除源文件）；缺省 INSERT */
    public MediaAsset ingestPath(Path p, String name, String tags) {
        return ingestPath(p, name, tags, null);
    }

    /** S1：参考素材扫描带 category（CHARACTER/SCENE）；入口规范化+白名单 */
    public MediaAsset ingestPath(Path p, String name, String tags, String category) {
        String normalizedCategory = normalizeCategory(category);
        Long userId = getCurrentUserId();
        String contentType = contentTypeForName(p.getFileName().toString());
        boolean isVideo = contentType.startsWith("video/");
        try {
            return ingest(p, name, tags, contentType, isVideo, null, null, userId, normalizedCategory);
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

    /** S1 Task 2 seam：char_林晚_正面全身.png → [CHARACTER, 林晚, 正面全身]；前缀/扩展名不符返回 null；多段标签转逗号（M-2，对齐库内 tags 惯例） */
    static String[] parseRefFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = REF_NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return new String[]{"char".equalsIgnoreCase(m.group(1)) ? "CHARACTER" : "SCENE",
                m.group(2), m.group(3).replace('_', ',')};
    }

    /** S1 Task 2 seam（Minor#1）：trim+大写规范化（Locale.ROOT 防土耳其语 i 误拒）；null/空白→null（INSERT 存量语义）；非白名单 fail-fast */
    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String upper = category.trim().toUpperCase(Locale.ROOT);
        if (!VALID_CATEGORIES.contains(upper)) {
            throw new IllegalArgumentException("不支持的素材 category: " + category + "（仅 CHARACTER/SCENE/INSERT）");
        }
        return upper;
    }

    /**
     * S1 Task 2：参考素材扫描入库（char_/scene_ 约定；镜像 scanImportDir idiom：仅读配置目录防遍历）。
     * 审查修复：I-1 按 category+name+tags 键幂等去重（面板反复触发不膨胀）；M-4 目录有效时登录校验 fail-fast（匿名不再静默空返回）。
     */
    public List<MediaAsset> scanReferenceDir() {
        if (referenceImportDir == null || referenceImportDir.isBlank()) {
            return List.of();
        }
        Path dir = Path.of(referenceImportDir);
        if (!Files.isDirectory(dir)) {
            log.warn("参考素材扫描目录不存在: {}", referenceImportDir);
            return List.of();
        }
        Long userId = getCurrentUserId();
        Set<String> existingKeys = assetRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(a -> a.effectiveCategory() + '\u0000' + a.getName() + '\u0000' + a.getTags())
                .collect(Collectors.toCollection(HashSet::new));
        List<MediaAsset> ingested = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String[] parsed = parseRefFileName(f.getFileName().toString());
                if (parsed == null) {
                    log.info("跳过非约定命名参考素材: {}", f.getFileName());
                    continue;
                }
                if (!existingKeys.add(parsed[0] + '\u0000' + parsed[1] + '\u0000' + parsed[2])) {
                    log.info("跳过已入库参考素材: {}", f.getFileName());
                    continue;
                }
                try {
                    ingested.add(ingestPath(f, parsed[1], parsed[2], parsed[0]));
                } catch (Exception e) {
                    log.warn("参考素材入库失败 {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("参考素材扫描失败: " + e.getMessage(), e);
        }
        log.info("参考素材扫描入库 {} 个（目录 {}）", ingested.size(), referenceImportDir);
        return ingested;
    }

    public List<MediaAsset> list() {
        return list(null);
    }

    /** S1：按 category 过滤（null/空白=不过滤；存量 null 行按 INSERT 语义匹配；非法值拒绝） */
    public List<MediaAsset> list(String category) {
        String normalized = normalizeCategory(category);
        List<MediaAsset> all = assetRepository.findByUserIdOrderByIdDesc(getCurrentUserId());
        if (normalized == null) {
            return all;
        }
        return all.stream().filter(a -> normalized.equals(a.effectiveCategory())).toList();
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
                              Double trimStart, Double trimEnd, Long userId, String category) throws Exception {
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
            a.setCategory(category);
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