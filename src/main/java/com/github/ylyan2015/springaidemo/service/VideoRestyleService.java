package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.entity.RestyleTask;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.RestyleTaskRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 视频转绘服务（Phase 4 切片 5：实拍视频 → AI 风格转绘，独立输出模式）
 * 协议（2026-11 spike 实测核对）：wan2.7-videoedit 异步提交 POST {base}/services/aigc/video-generation/video-synthesis
 * + X-DashScope-Async: enable；input.media[{type:video,url}] + prompt；轮询 GET {base}/tasks/{id} →
 * output.task_status SUCCEEDED + output.video_url（24h 临时链接，下载转存）。
 * 视频输入仅接受公网 URL/oss 临时 URL/asset_id，不支持 base64 → local 存储 FAILED 带 oss/minio 指引。
 * SecurityContext 传播 idiom 同切片 2 编排；专用单线程池，失败仅任务 FAILED 不影响其他功能。
 */
@Service
public class VideoRestyleService {

    private static final Logger log = LoggerFactory.getLogger(VideoRestyleService.class);
    private static final String SUBMIT_PATH = "/services/aigc/video-generation/video-synthesis";
    private static final String DEFAULT_STYLE = "anime style, cel shading, clean lines";

    private final RestyleTaskRepository taskRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final long pollIntervalMs;

    /** 专用单线程池：转绘为分钟级长任务，与请求线程/编排池隔离 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "restyle-worker");
        t.setDaemon(true);
        return t;
    });

    /** 异步 Future 表：awaitAndGetForTest 等待终态（测试/冒烟 seam 消费） */
    private final Map<Long, CompletableFuture<RestyleTask>> futures = new ConcurrentHashMap<>();

    @Autowired
    public VideoRestyleService(RestyleTaskRepository taskRepository,
                               UserRepository userRepository,
                               StorageService storageService,
                               ObjectMapper objectMapper,
                               @Value("${video.wan.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
                               @Value("${spring.ai.dashscope.api-key:}") String apiKey,
                               @Value("${video.restyle.model:wan2.7-videoedit}") String model,
                               @Value("${video.restyle.timeout-seconds:600}") int timeoutSeconds,
                               @Value("${video.restyle.poll-interval-ms:5000}") long pollIntervalMs) {
        this(taskRepository, userRepository, storageService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                baseUrl, apiKey, model, timeoutSeconds, pollIntervalMs);
    }

    public VideoRestyleService(RestyleTaskRepository taskRepository,
                               UserRepository userRepository,
                               StorageService storageService,
                               ObjectMapper objectMapper,
                               HttpClient httpClient,
                               String baseUrl, String apiKey, String model,
                               int timeoutSeconds, long pollIntervalMs) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.pollIntervalMs = pollIntervalMs;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 提交转绘：同步上传源视频 + 建任务，异步执行提交/轮询/转存。
     * contentType 必须 video/*；空 Key 抛异常（干净启动安全）。
     */
    public Map<String, Object> submit(InputStream input, String fileName, String contentType, String stylePrompt) {
        Long userId = getCurrentUserId();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw new RuntimeException("仅支持视频文件（video/*，mp4/mov，2-10 秒）");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("视频转绘需配置 DASHSCOPE_API_KEY");
        }
        String prompt = stylePrompt == null || stylePrompt.isBlank() ? DEFAULT_STYLE : stylePrompt.trim();

        String sourceFileId = storageService.upload(input, fileName, contentType);
        RestyleTask task = new RestyleTask();
        task.setUserId(userId);
        task.setSourceFileId(sourceFileId);
        task.setSourceVideoUrl(storageService.getAccessUrl(sourceFileId));
        task.setStylePrompt(prompt);
        task.setStatus("RUNNING");
        RestyleTask saved = taskRepository.save(task);

        // SecurityContext 提交捕获、工作线程传播（idiom 同切片 2 编排）
        SecurityContext ctx = SecurityContextHolder.getContext();
        CompletableFuture<RestyleTask> future = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(ctx);
            try {
                runRestyle(saved);
            } catch (Exception e) {
                log.error("Restyle task {} failed: {}", saved.getId(), e.getMessage(), e);
                saved.setStatus("FAILED");
                saved.setError(truncate(e.getMessage(), 900));
                taskRepository.save(saved);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return saved;
        }, executor);
        // 清理已终态历史条目防内存无限增长（审查修复）；不用 whenComplete 立即移除，
        // 避免任务先于 awaitAndGetForTest 终态时 seam 查不到 future
        futures.entrySet().removeIf(e -> e.getValue().isDone());
        futures.put(saved.getId(), future);
        log.info("Restyle task {} submitted (user {}, model {})", saved.getId(), userId, model);
        return taskToMap(saved);
    }

    /** 任务状态查询（归属校验） */
    public Map<String, Object> taskStatus(Long id) {
        Long userId = getCurrentUserId();
        RestyleTask task = taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("转绘任务不存在或无权访问: " + id));
        return taskToMap(task);
    }

    /** 我的转绘任务列表 */
    public List<Map<String, Object>> listMine() {
        Long userId = getCurrentUserId();
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::taskToMap)
                .toList();
    }

    /** 包私有测试/冒烟 seam：等待异步终态并返回实体（不依赖登录态与未 stub 查询） */
    RestyleTask awaitAndGetForTest(long id) {
        CompletableFuture<RestyleTask> future = futures.get(id);
        if (future == null) {
            throw new IllegalArgumentException("无此转绘任务的异步记录: " + id);
        }
        try {
            return future.get(timeoutSeconds + 60L, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("等待转绘任务终态失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    private void runRestyle(RestyleTask task) throws Exception {
        String sourceUrl = resolveSourceUrl(task);
        String body = objectMapper.writeValueAsString(buildSubmitBody(sourceUrl, task.getStylePrompt()));
        HttpRequest submit = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + SUBMIT_PATH))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> submitResp = httpClient.send(submit, HttpResponse.BodyHandlers.ofString());
        if (submitResp.statusCode() != 200) {
            throw new RuntimeException("转绘提交失败 HTTP " + submitResp.statusCode() + ": " + submitResp.body());
        }
        String taskId = objectMapper.readTree(submitResp.body()).path("output").path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            throw new RuntimeException("转绘响应无 task_id: " + submitResp.body());
        }
        String videoUrl = pollUntilDone(taskId);

        // 供应商临时 URL 24h 过期：立即下载转存永久存储
        HttpRequest download = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> dl = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        if (dl.statusCode() != 200) {
            throw new RuntimeException("转绘结果下载失败 HTTP " + dl.statusCode());
        }
        String resultFileId = storageService.upload(new ByteArrayInputStream(dl.body()),
                "restyle_out_" + task.getId() + ".mp4", "video/mp4");
        task.setResultFileId(resultFileId);
        task.setResultUrl(storageService.getAccessUrl(resultFileId));
        task.setStatus("SUCCEEDED");
        taskRepository.save(task);
        log.info("Restyle task {} succeeded: {}", task.getId(), task.getResultUrl());
    }

    /** 源视频 URL 回退链：公网 URL 直用→非 local 存储 accessUrl→local FAILED（协议不支持视频 base64） */
    private String resolveSourceUrl(RestyleTask task) {
        String url = task.getSourceVideoUrl();
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            return url;
        }
        if (!"local".equals(storageService.getStorageType())) {
            String accessUrl = storageService.getAccessUrl(task.getSourceFileId());
            if (accessUrl != null && accessUrl.startsWith("http")) {
                return accessUrl;
            }
        }
        throw new RuntimeException("本地存储无法提供公网视频 URL：请切换 storage 为 oss/minio"
                + "（wan2.7-videoedit 视频输入不支持 base64）");
    }

    /** 包私有测试 seam：转绘协议装配（纯指令编辑：单视频 + 风格 prompt） */
    ObjectNode buildSubmitBody(String sourceUrl, String stylePrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        input.put("prompt", stylePrompt);
        input.putArray("media").addObject().put("type", "video").put("url", sourceUrl);
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("resolution", "720P");      // 成本基线（计费=输入+输出时长）
        parameters.put("prompt_extend", false);    // 风格 prompt 已明确，关闭改写省时
        parameters.put("watermark", false);
        parameters.put("audio_setting", "origin"); // 保留原声
        return root;
    }

    private String pollUntilDone(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest poll = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/tasks/" + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(poll, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("转绘轮询失败 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode output = objectMapper.readTree(resp.body()).path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                String url = output.path("video_url").asText(null);
                if (url == null || url.isBlank()) {
                    throw new RuntimeException("转绘成功但无 video_url: " + resp.body());
                }
                return url;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                throw new RuntimeException("转绘任务失败: " + resp.body());
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("转绘任务超时（" + timeoutSeconds + "s）: " + taskId);
    }

    private Map<String, Object> taskToMap(RestyleTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("sourceFileId", task.getSourceFileId());
        map.put("sourceVideoUrl", task.getSourceVideoUrl());
        map.put("stylePrompt", task.getStylePrompt());
        map.put("provider", task.getProvider());
        map.put("status", task.getStatus());
        map.put("resultUrl", task.getResultUrl());
        map.put("resultFileId", task.getResultFileId());
        map.put("error", task.getError());
        map.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        map.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        return map;
    }

    /** baseUrl 归一：兼容带/不带 /api/v1 后缀（测试与配置两种写法） */
    private static String normalizeBaseUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b.endsWith("/api/v1") ? b : b + "/api/v1";
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
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