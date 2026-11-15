package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedVideo;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerationRequest;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGeneratorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图生视频服务（Phase 4 切片 2）
 * 按 shot 并行（工作池 video.workers，协调池独立）；SecurityContext 全工作线程传播；
 * 失败重试 ≤ video.retry-attempts（按秒计费成本红线）；首尾帧连续性三模式；
 * 产物存储转存 + ffprobe 实测时长回写 + 末帧抽取。
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    /** 中低运动幅度模板（教程共识：i2v 基础操作、单镜 3-5s、中低幅度） */
    private static final String MOTION_SUFFIX = ", subtle motion, slow camera, no large deformation";

    private final VideoGeneratorFactory videoGeneratorFactory;
    private final ImageGeneratorFactory imageGeneratorFactory;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final VideoComposeService composeService;

    @Value("${video.workers:2}")
    private int workers;

    @Value("${video.retry-attempts:1}")
    private int retryAttempts;

    @Value("${video.wan.resolution:720P}")
    private String resolution;

    @Value("${storage.type:local}")
    private String storageType;

    private ExecutorService generationExecutor;
    private ExecutorService coordinatorExecutor;

    public VideoGenerationService(VideoGeneratorFactory videoGeneratorFactory,
                                  ImageGeneratorFactory imageGeneratorFactory,
                                  StoryScriptRepository scriptRepository,
                                  StoryShotRepository shotRepository,
                                  UserRepository userRepository,
                                  ObjectMapper objectMapper,
                                  StorageService storageService,
                                  VideoComposeService composeService) {
        this.videoGeneratorFactory = videoGeneratorFactory;
        this.imageGeneratorFactory = imageGeneratorFactory;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.composeService = composeService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.generationExecutor = Executors.newFixedThreadPool(Math.max(1, workers));
        this.coordinatorExecutor = Executors.newCachedThreadPool();
        log.info("Video generation thread pool initialized: workers={}", workers);
    }

    @PreDestroy
    public void shutdown() {
        generationExecutor.shutdown();
        coordinatorExecutor.shutdown();
    }

    // ==================== 单镜头生成 ====================

    public StoryShot generateForShot(Long scriptId, Long shotId) {
        StoryScript script = requireOwnedScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        return doGenerate(script, shot, null);
    }

    /**
     * @param firstFrameOverride PREV_LAST_FRAME 模式下上一镜末帧 URL（已解析），null 用本镜定妆图
     */
    private StoryShot doGenerate(StoryScript script, StoryShot shot, String firstFrameOverride) {
        VideoGenerator gen = videoGeneratorFactory.getActiveGenerator();
        if (gen == null || "none".equals(gen.getType())) {
            throw new RuntimeException("video.generator=none：未配置图生视频");
        }
        shot.setVideoStatus("GENERATING");
        shotRepository.save(shot);
        try {
            String firstFrame = firstFrameOverride != null ? firstFrameOverride
                    : prevLastFrameOrNull(script, shot);
            if (firstFrame == null) {
                firstFrame = resolveMediaUrl(shot.getImageUrl(), shot.getImageFileId());
            }
            String lastFrame = resolveLastFrame(script, shot);
            String base = shot.getImagePrompt() != null && !shot.getImagePrompt().isBlank()
                    ? shot.getImagePrompt() : shot.getSceneDescription();
            String prompt = (base == null ? "" : base) + MOTION_SUFFIX;
            int duration = Math.max(2, Math.min(15,
                    shot.getDurationSeconds() == null ? 5 : shot.getDurationSeconds()));

            GeneratedVideo video = generateWithRetry(gen,
                    new VideoGenerationRequest(prompt, firstFrame, lastFrame, duration, resolution));
            finalizeVideo(shot, video.getVideoUrl(), video.getFileId());
            return shotRepository.save(shot);
        } catch (Exception e) {
            log.error("Shot {} i2v failed: {}", shot.getId(), e.getMessage());
            shot.setVideoStatus("FAILED");
            shotRepository.save(shot);
            throw new RuntimeException("镜头视频生成失败: " + e.getMessage(), e);
        }
    }

    private GeneratedVideo generateWithRetry(VideoGenerator gen, VideoGenerationRequest req) {
        int maxAttempts = retryAttempts + 1;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return gen.generate(req);
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts) {
                    log.warn("i2v attempt {}/{} failed, retrying: {}", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("生成被中断", ie);
                    }
                }
            }
        }
        throw new RuntimeException("i2v 失败（已重试 " + retryAttempts + " 次）: "
                + (last != null ? last.getMessage() : "未知错误"), last);
    }

    /**
     * 回写主资产：videoUrl/fileId → probe 实测时长 → 末帧抽取转存 → READY。
     * public 原始参数签名（切片 3 候选择优消费，不依赖候选实体）。
     */
    public void applyVideoCandidate(StoryShot shot, String videoUrl, String videoFileId, int durationSeconds) {
        finalizeVideo(shot, videoUrl, videoFileId);
        shotRepository.save(shot);
    }

    private void finalizeVideo(StoryShot shot, String videoUrl, String videoFileId) {
        shot.setVideoUrl(videoUrl);
        shot.setVideoFileId(videoFileId);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("i2v-finalize-");
            Path local = workDir.resolve("clip.mp4");
            try (InputStream in = storageService.download(videoFileId)) {
                Files.copy(in, local);
            }
            int probed = Math.max(2, composeService.probeDurationSeconds(local));
            shot.setDurationSeconds(probed);
            Path lastFrame = composeService.extractLastFrame(local, workDir);
            String lfId;
            try (InputStream lfIn = Files.newInputStream(lastFrame)) {
                lfId = storageService.upload(lfIn,
                        "last_frame_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
            }
            shot.setLastFrameFileId(lfId);
            shot.setLastFrameUrl(storageService.getAccessUrl(lfId));
        } catch (Exception e) {
            log.warn("Shot {} finalize (probe/lastframe) degraded: {}", shot.getId(), e.getMessage());
        } finally {
            if (workDir != null) {
                try (var walk = Files.walk(workDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
        shot.setVideoStatus("READY");
    }

    // ==================== 首尾帧连续性 ====================

    /** PREV_LAST_FRAME：上一镜末帧作为本镜首帧（规格 §5.3）；上一镜未 READY 返回 null 回退本镜图 */
    private String prevLastFrameOrNull(StoryScript script, StoryShot shot) {
        String mode = script.getContinuityMode() == null ? "NONE" : script.getContinuityMode();
        if (!"PREV_LAST_FRAME".equals(mode)) {
            return null;
        }
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(script.getId());
        StoryShot prev = null;
        for (StoryShot s : shots) {
            if (s.getId().equals(shot.getId())) {
                break;
            }
            prev = s;
        }
        if (prev != null && prev.getLastFrameFileId() != null) {
            return resolveMediaUrl(prev.getLastFrameUrl(), prev.getLastFrameFileId());
        }
        return null;
    }

    private String resolveLastFrame(StoryScript script, StoryShot shot) {
        String mode = script.getContinuityMode() == null ? "NONE" : script.getContinuityMode();
        if ("GENERATED_LAST_FRAME".equals(mode)) {
            ImageGenerator wan27 = imageGeneratorFactory.getAllGenerators().get("wan27");
            if (wan27 == null || !wan27.isAvailable() || !wan27.supportsReferences()) {
                log.warn("wan27 不可用，GENERATED_LAST_FRAME 回退 NONE");
                return null;
            }
            try {
                String firstFrame = resolveMediaUrl(shot.getImageUrl(), shot.getImageFileId());
                List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(script.getId());
                StoryShot next = null;
                boolean found = false;
                for (StoryShot s : shots) {
                    if (found) {
                        next = s;
                        break;
                    }
                    if (s.getId().equals(shot.getId())) {
                        found = true;
                    }
                }
                String nextDesc = next != null && next.getSceneDescription() != null
                        ? next.getSceneDescription() : "continue the scene";
                if (next == null) {
                    // 末镜无下一镜衔接，不生成尾帧
                    return null;
                }
                GeneratedImage img = wan27.generateWithReferences(
                        "extend the last frame of this image toward the next scene: " + nextDesc
                                + ", keep character identity and style",
                        null, 1280, 720, List.of(firstFrame));
                return img.getImageUrl();
            } catch (Exception e) {
                log.warn("GENERATED_LAST_FRAME 生成失败，回退 NONE: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ==================== 批量生成 ====================

    /** 同步批量（编排/测试消费）；PREV_LAST_FRAME 顺序执行，其余并行 */
    public List<StoryShot> generateForScriptBlocking(Long scriptId) {
        StoryScript script = requireOwnedScript(scriptId);
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        String mode = script.getContinuityMode() == null ? "NONE" : script.getContinuityMode();
        List<StoryShot> results = new ArrayList<>();
        if ("PREV_LAST_FRAME".equals(mode)) {
            String prevLastFrame = null;
            for (StoryShot shot : shots) {
                StoryShot done = doGenerate(script, shot, prevLastFrame);
                // finalize 降级（无尾帧）时重置链，下一镜回退本镜定妆图
                prevLastFrame = done.getLastFrameFileId() != null
                        ? resolveMediaUrl(done.getLastFrameUrl(), done.getLastFrameFileId())
                        : null;
                results.add(done);
            }
            return results;
        }
        SecurityContext ctx = SecurityContextHolder.getContext();
        List<CompletableFuture<StoryShot>> futures = new ArrayList<>();
        for (StoryShot shot : shots) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(ctx);
                try {
                    return doGenerate(script, shot, null);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, generationExecutor));
        }
        for (CompletableFuture<StoryShot> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                log.warn("Batch i2v shot failed: {}", e.getMessage());
            }
        }
        return results;
    }

    /** SSE 批量进度（GET 端点消费；协调池与工作池分离） */
    public Flux<ServerSentEvent<String>> generateForScriptStream(Long scriptId) {
        requireOwnedScript(scriptId);
        SecurityContext ctx = SecurityContextHolder.getContext();
        return Flux.create(sink -> CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(ctx);
            try {
                sink.next(event("VIDEO_BATCH_START", Map.of("scriptId", scriptId)));
                List<StoryShot> done = generateForScriptBlocking(scriptId);
                for (StoryShot s : done) {
                    sink.next(event("SHOT_VIDEO_COMPLETE", Map.of("shotId", s.getId(),
                            "videoStatus", s.getVideoStatus() == null ? "FAILED" : s.getVideoStatus())));
                }
                sink.next(event("VIDEO_BATCH_COMPLETE", Map.of("scriptId", scriptId, "count", done.size())));
                sink.complete();
            } catch (Exception e) {
                sink.next(event("ERROR", Map.of("message", String.valueOf(e.getMessage()))));
                sink.complete();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }, coordinatorExecutor));
    }

    private ServerSentEvent<String> event(String type, Map<String, Object> data) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put("type", type);
            return ServerSentEvent.<String>builder(objectMapper.writeValueAsString(payload)).build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder("{\"type\":\"ERROR\"}").build();
        }
    }

    // ==================== 生成器管理 ====================

    public List<Map<String, Object>> listGenerators() {
        List<Map<String, Object>> items = new ArrayList<>();
        videoGeneratorFactory.getAllGenerators().forEach((type, g) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", g.getType());
            item.put("name", g.getName());
            item.put("available", g.isAvailable());
            item.put("active", g.getType().equals(videoGeneratorFactory.getActiveType()));
            items.add(item);
        });
        return items;
    }

    public void switchGenerator(String type) {
        videoGeneratorFactory.switchGenerator(type);
    }

    // ==================== URL 回退链 ====================

    /**
     * 首帧/末帧 URL 回退链：http 直用 → 非 local presigned/公网 → local 抛 oss/minio 指引异常
     * （i2v 协议要求公网 URL；本地存储无公网地址时干净失败并指引）。public 供切片 3 消费。
     */
    public String resolveMediaUrl(String url, String fileId) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            return url;
        }
        if (fileId == null || fileId.isBlank()) {
            throw new RuntimeException("镜头无可用首帧图（imageUrl/fileId 均空）");
        }
        if (!"local".equals(storageType)) {
            return storageService.getAccessUrl(fileId);
        }
        throw new RuntimeException("本地存储无法提供公网首帧 URL：请切换 storage 为 oss/minio，"
                + "或确认协议支持 base64（video.wan.base64-enabled）");
    }

    // ==================== 私有辅助 ====================

    private StoryScript requireOwnedScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }
}