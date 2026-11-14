package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ImageGenerationProgress;
import com.github.ylyan2015.springaidemo.video.VisualStyle;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AI 图像生成服务
 * 集成云端 DashScope 和本地 SD/FLUX 生成器，
 * 支持多风格、并行批量生成、失败重试和 SSE 进度推送
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final ImageGeneratorFactory generatorFactory;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final ImageQualityService qualityService;

    /** 复用 Spring 自动配置的 ObjectMapper（已注册 JavaTimeModule，可正确序列化 LocalDateTime） */
    private final ObjectMapper objectMapper;

    /** 并行生成数（避免云端 API 限流） */
    @Value("${image.generation.parallelism:3}")
    private int parallelism;

    /** 失败重试次数 */
    @Value("${image.generation.retry-attempts:1}")
    private int retryAttempts;

    /** 重试间隔（毫秒） */
    @Value("${image.generation.retry-delay-ms:2000}")
    private long retryDelayMs;

    /** 默认图片尺寸 */
    @Value("${image.generation.default-size:1280x720}")
    private String defaultSize;

    /** 所有图片生成总超时（秒） */
    @Value("${image.generation.total-timeout-seconds:300}")
    private int totalTimeoutSeconds;

    /** 图像生成工作线程池（parallelism 配置注入后初始化） */
    private ExecutorService generationExecutor;

    /** 批量生成协调线程池（每批次占用一个线程等待生成任务，避免与工作线程池互相争抢） */
    private ExecutorService coordinatorExecutor;

    public ImageGenerationService(ImageGeneratorFactory generatorFactory,
                                  StoryScriptRepository scriptRepository,
                                  StoryShotRepository shotRepository,
                                  UserRepository userRepository,
                                  ObjectMapper objectMapper,
                                  ImageQualityService qualityService) {
        this.generatorFactory = generatorFactory;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.qualityService = qualityService;
    }

    /**
     * 初始化线程池（配置值注入完成后调用）
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        this.generationExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        this.coordinatorExecutor = Executors.newCachedThreadPool();
        log.info("Image generation thread pool initialized: parallelism={}", parallelism);
    }

    @PreDestroy
    public void shutdown() {
        generationExecutor.shutdown();
        coordinatorExecutor.shutdown();
    }

    // ==================== 单镜头生成 ====================

    /**
     * 为单个镜头生成图片（含重试机制），生成成功后更新镜头状态
     *
     * @param scriptId 脚本ID
     * @param shotId   镜头ID
     * @param style    视觉风格（可选，会作为提示词前缀应用）
     * @return 更新后的镜头
     */
    public StoryShot generateForShot(Long scriptId, Long shotId, String style) {
        Long userId = getCurrentUserId();
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));

        VisualStyle visualStyle = VisualStyle.fromName(style);
        String prompt = composePrompt(shot, visualStyle);
        String negativePrompt = visualStyle.getNegativePrompt();

        log.info("Generating image for shot {} of script {}: style='{}'",
                shotId, scriptId, visualStyle.getDisplayName());

        shot.setImageStatus("GENERATING");
        shotRepository.save(shot);

        try {
            QualityGenResult result = generateWithQualityCheck(prompt, negativePrompt);
            GeneratedImage image = result.image();

            shot.setImageUrl(image.getImageUrl());
            shot.setImageFileId(image.getFileId());
            shot.setImageStatus("READY");
            shot.setQualityScore(result.score() != null ? result.score().getOverallScore() : null);
            StoryShot saved = shotRepository.save(shot);

            script.setUpdatedAt(LocalDateTime.now());
            scriptRepository.save(script);

            log.info("Shot {} image generated: url={}", shotId, image.getImageUrl());
            return saved;

        } catch (Exception e) {
            log.error("Image generation failed for shot {}: {}", shotId, e.getMessage());
            shot.setImageStatus("FAILED");
            shotRepository.save(shot);
            throw new RuntimeException("镜头图片生成失败: " + e.getMessage(), e);
        }
    }

    // ==================== 批量生成（SSE 进度推送） ====================

    /**
     * 为脚本所有镜头并行生成图片，通过 SSE 推送实时进度
     *
     * @param scriptId 脚本ID
     * @param style    视觉风格（可选）
     * @return SSE 事件流，每个事件为 ImageGenerationProgress 的 JSON
     */
    public Flux<ServerSentEvent<String>> generateForScriptStream(Long scriptId, String style) {
        // 在请求线程中捕获用户和安全上下文（避免异步线程丢失）
        Long userId = getCurrentUserId();
        SecurityContext securityContext = SecurityContextHolder.getContext();

        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);

        if (shots.isEmpty()) {
            return Flux.error(new RuntimeException("脚本没有任何镜头: " + scriptId));
        }

        VisualStyle visualStyle = VisualStyle.fromName(style);
        ImageGenerator generator = generatorFactory.getActiveGenerator();

        log.info("Batch image generation started: scriptId={}, {} shots, style='{}', generator='{}'",
                scriptId, shots.size(), visualStyle.getDisplayName(), generator.getName());

        return Flux.create(sink -> {
            // 协调任务提交到独立的协调线程池，阻塞等待所有生成任务完成
            //（不占用工作线程池，避免批量任务与生成任务互相等待）
            CompletableFuture.runAsync(() -> {
                try {
                    runBatchGeneration(scriptId, script, shots, visualStyle, securityContext,
                            progress -> {
                                try {
                                    sink.next(ServerSentEvent.<String>builder()
                                            .event(progress.getType())
                                            .data(objectMapper.writeValueAsString(progress))
                                            .build());
                                } catch (Exception e) {
                                    log.error("Failed to serialize progress event: {}", e.getMessage());
                                }
                            });
                    sink.complete();
                } catch (Exception e) {
                    log.error("Batch generation error: {}", e.getMessage(), e);
                    try {
                        ImageGenerationProgress error = ImageGenerationProgress.of("ERROR", e.getMessage());
                        error.setScriptId(scriptId);
                        sink.next(ServerSentEvent.<String>builder()
                                .event("ERROR")
                                .data(objectMapper.writeValueAsString(error))
                                .build());
                    } catch (Exception ignored) {
                        // 序列化失败时忽略
                    }
                    sink.complete();
                }
            }, coordinatorExecutor);
        });
    }

    /**
     * 执行批量并行生成（内部方法，由 SSE 流或同步调用触发）
     */
    private void runBatchGeneration(Long scriptId, StoryScript script, List<StoryShot> shots,
                                    VisualStyle visualStyle, SecurityContext securityContext,
                                    Consumer<ImageGenerationProgress> progressCallback) {
        int total = shots.size();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // 推送开始事件
        ImageGenerationProgress start = ImageGenerationProgress.of("START",
                String.format("开始为 %d 个镜头生成图片（风格：%s，生成器：%s）",
                        total, visualStyle.getDisplayName(), generatorFactory.getActiveGenerator().getName()));
        start.setScriptId(scriptId);
        start.setTotalShots(total);
        progressCallback.accept(start);

        // 标记所有镜头为生成中
        for (StoryShot shot : shots) {
            shot.setImageStatus("GENERATING");
            shotRepository.save(shot);
        }

        // 并行提交所有生成任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (StoryShot shot : shots) {
            futures.add(CompletableFuture.runAsync(() -> {
                // 将请求线程的安全上下文传播到工作线程
                //（DashScopeImageGenerator 内部调用 ImageService，依赖当前用户身份）
                SecurityContextHolder.setContext(securityContext);
                try {
                    generateShotWithProgress(shot, scriptId, visualStyle, total,
                            completed, failed, progressCallback);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, generationExecutor));
        }

        // 等待所有任务完成（带总超时）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(totalTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Batch generation timeout or interrupted: {}", e.getMessage());
        }

        // 更新脚本修改时间
        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        // 推送完成事件
        int successCount = completed.get();
        int failedCount = failed.get();
        ImageGenerationProgress complete = ImageGenerationProgress.of("COMPLETE",
                String.format("批量生成完成：%d 成功，%d 失败", successCount, failedCount));
        complete.setScriptId(scriptId);
        complete.setTotalShots(total);
        complete.setCompletedShots(successCount);
        complete.setFailedShots(failedCount);
        progressCallback.accept(complete);

        log.info("Batch image generation finished: scriptId={}, success={}, failed={}",
                scriptId, successCount, failedCount);
    }

    /**
     * 单个镜头的生成任务（批量生成时由工作线程执行，调用前已传播安全上下文）
     */
    private void generateShotWithProgress(StoryShot shot, Long scriptId, VisualStyle visualStyle,
                                          int total, AtomicInteger completed, AtomicInteger failed,
                                          Consumer<ImageGenerationProgress> progressCallback) {
        ImageGenerationProgress shotStart = new ImageGenerationProgress();
        shotStart.setType("SHOT_START");
        shotStart.setScriptId(scriptId);
        shotStart.setShotId(shot.getId());
        shotStart.setCurrentShotIndex(shot.getShotOrder());
        shotStart.setTotalShots(total);
        shotStart.setMessage("正在生成第 " + shot.getShotOrder() + "/" + total + " 张图片...");
        progressCallback.accept(shotStart);

        try {
            String prompt = composePrompt(shot, visualStyle);
            QualityGenResult result = generateWithQualityCheck(prompt, visualStyle.getNegativePrompt());
            GeneratedImage image = result.image();

            shot.setImageUrl(image.getImageUrl());
            shot.setImageFileId(image.getFileId());
            shot.setImageStatus("READY");
            shot.setQualityScore(result.score() != null ? result.score().getOverallScore() : null);
            shotRepository.save(shot);

            ImageGenerationProgress done = new ImageGenerationProgress();
            done.setType("SHOT_COMPLETE");
            done.setScriptId(scriptId);
            done.setShotId(shot.getId());
            done.setCurrentShotIndex(shot.getShotOrder());
            done.setTotalShots(total);
            done.setCompletedShots(completed.incrementAndGet());
            done.setFailedShots(failed.get());
            done.setImageUrl(image.getImageUrl());
            done.setQualityScore(result.score() != null ? result.score().getOverallScore() : null);
            done.setMessage("第 " + shot.getShotOrder() + "/" + total + " 张图片生成完成");
            progressCallback.accept(done);

            log.info("Shot {}/{} generated: url={}", shot.getShotOrder(), total, image.getImageUrl());

        } catch (Exception e) {
            log.error("Shot {} generation failed: {}", shot.getId(), e.getMessage());

            shot.setImageStatus("FAILED");
            shotRepository.save(shot);

            ImageGenerationProgress failEvent = new ImageGenerationProgress();
            failEvent.setType("SHOT_FAILED");
            failEvent.setScriptId(scriptId);
            failEvent.setShotId(shot.getId());
            failEvent.setCurrentShotIndex(shot.getShotOrder());
            failEvent.setTotalShots(total);
            failEvent.setCompletedShots(completed.get());
            failEvent.setFailedShots(failed.incrementAndGet());
            failEvent.setMessage("第 " + shot.getShotOrder() + " 张图片生成失败: " + e.getMessage());
            progressCallback.accept(failEvent);
        }
    }

    // ==================== 生成器管理 ====================

    /**
     * 获取所有可用的图像生成器信息
     */
    public List<Map<String, Object>> listGenerators() {
        List<Map<String, Object>> items = new ArrayList<>();
        generatorFactory.getAllGenerators().forEach((type, generator) -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("type", generator.getType());
            item.put("name", generator.getName());
            item.put("available", generator.isAvailable());
            item.put("active", generator.getType().equals(generatorFactory.getActiveType()));
            items.add(item);
        });
        return items;
    }

    /**
     * 运行时切换图像生成器
     */
    public void switchGenerator(String type) {
        generatorFactory.switchGenerator(type);
    }

    // ==================== 内部方法 ====================

    /** 生成结果 + 质量评分（模块 J；未启用评估时 score 为 null） */
    private record QualityGenResult(GeneratedImage image, ImageQualityScore score) {}

    /**
     * 带质量评估的生成（模块 J）：先按原有失败重试机制生成，
     * 启用评估后低分自动重新生成（最多 image.quality.max-retries 次），
     * 重试时将评分反馈并入负面提示词；始终保留得分最高的一张。
     * 评估异常不阻塞生成，保留当前图片。
     */
    private QualityGenResult generateWithQualityCheck(String prompt, String negativePrompt) {
        GeneratedImage current = generateWithRetry(prompt, negativePrompt);
        if (!qualityService.isEnabled()) {
            return new QualityGenResult(current, null);
        }

        GeneratedImage bestImage = current;
        ImageQualityScore bestScore = null;
        String negative = negativePrompt;
        int maxRetries = Math.max(0, qualityService.getMaxRetries());

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ImageQualityScore score;
            try {
                score = qualityService.evaluate(current.getImageUrl(), prompt);
            } catch (Exception e) {
                log.warn("Quality evaluation failed, keeping current image: {}", e.getMessage());
                break;
            }
            if (score == null) {
                break;
            }
            if (bestScore == null || score.getOverallScore() > bestScore.getOverallScore()) {
                bestScore = score;
                bestImage = current;
            }
            if (!qualityService.shouldRegenerate(score) || attempt == maxRetries) {
                break;
            }

            log.info("Image score {} below threshold {}, regenerating (retry {}/{})",
                    score.getOverallScore(), qualityService.getThreshold(), attempt + 1, maxRetries);
            String feedback = score.getFeedback();
            if (feedback != null && !feedback.isBlank()) {
                negative = (negative == null || negative.isBlank()) ? feedback : negative + ", " + feedback;
            }
            try {
                current = generateWithRetry(prompt, negative);
            } catch (Exception e) {
                log.warn("Quality retry generation failed, keeping best image: {}", e.getMessage());
                break;
            }
        }
        return new QualityGenResult(bestImage, bestScore);
    }

    /**
     * 带重试的图片生成
     * 单张失败时自动重试 retryAttempts 次，重试间隔 retryDelayMs
     */
    private GeneratedImage generateWithRetry(String prompt, String negativePrompt) {
        ImageGenerator generator = generatorFactory.getActiveGenerator();
        int maxAttempts = retryAttempts + 1;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return generator.generate(prompt, negativePrompt,
                        parseWidth(defaultSize), parseHeight(defaultSize));
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("Image generation attempt {}/{} failed, retrying in {}ms: {}",
                            attempt, maxAttempts, retryDelayMs, e.getMessage());
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("生成被中断", ie);
                    }
                }
            }
        }
        throw new RuntimeException("图片生成失败（已重试 " + retryAttempts + " 次）: "
                + (lastError != null ? lastError.getMessage() : "未知错误"), lastError);
    }

    /**
     * 组合最终提示词：风格前缀 + 镜头提示词（或场景描述）
     */
    private String composePrompt(StoryShot shot, VisualStyle visualStyle) {
        String base = shot.getImagePrompt();
        if (base == null || base.isBlank()) {
            base = shot.getSceneDescription();
        }
        if (base == null || base.isBlank()) {
            throw new RuntimeException("镜头 " + shot.getShotOrder() + " 没有可用的提示词或场景描述");
        }
        return visualStyle.getPromptPrefix() + ", " + base;
    }

    /**
     * 解析尺寸字符串中的宽度
     */
    private int parseWidth(String size) {
        try {
            return Integer.parseInt(size.split("x")[0]);
        } catch (Exception e) {
            return 1280;
        }
    }

    /**
     * 解析尺寸字符串中的高度
     */
    private int parseHeight(String size) {
        try {
            return Integer.parseInt(size.split("x")[1]);
        } catch (Exception e) {
            return 720;
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
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }
}
