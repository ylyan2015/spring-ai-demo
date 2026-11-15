package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DashScope 万相图生视频生成器（Phase 4 切片 2）
 * 协议（2026-11 核对）：POST {base}/services/aigc/video-generation/video-synthesis + X-DashScope-Async: enable；
 * wan2.7-i2v：input.media[{type:first_frame,url},{type:last_frame,url}]；wan2.6-i2v：input.img_url（仅首帧，尾帧 warn 忽略）；
 * parameters{resolution,duration}；轮询 GET {base}/tasks/{id} → output.task_status SUCCEEDED + output.video_url；
 * 产物 mp4 下载转存（供应商临时 URL 不持久化）。
 */
public class DashScopeWanVideoGenerator implements VideoGenerator {

    private static final Logger log = LoggerFactory.getLogger(DashScopeWanVideoGenerator.class);
    private static final String SUBMIT_PATH = "/services/aigc/video-generation/video-synthesis";

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String resolution;
    private final int timeoutSeconds;
    private final long pollIntervalMs;

    public DashScopeWanVideoGenerator(StorageService storageService, ObjectMapper objectMapper,
                                      HttpClient httpClient, String baseUrl, String apiKey,
                                      String model, String resolution, int timeoutSeconds,
                                      long pollIntervalMs) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.resolution = resolution;
        this.timeoutSeconds = timeoutSeconds;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public GeneratedVideo generate(VideoGenerationRequest request) {
        if (!isAvailable()) {
            throw new RuntimeException("dashscope-wan 不可用：需配置 DASHSCOPE_API_KEY");
        }
        int duration = Math.max(2, Math.min(15, request.durationSeconds()));
        try {
            String body = objectMapper.writeValueAsString(buildSubmitBody(request, duration));
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
                throw new RuntimeException("i2v 提交失败 HTTP " + submitResp.statusCode() + ": " + submitResp.body());
            }
            String taskId = objectMapper.readTree(submitResp.body()).path("output").path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new RuntimeException("i2v 响应无 task_id: " + submitResp.body());
            }
            String videoUrl = pollUntilDone(taskId);
            return transfer(videoUrl, duration);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("i2v 生成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "dashscope-wan";
    }

    @Override
    public String getName() {
        return "通义万相 i2v (" + model + ")";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ==================== 内部方法 ====================

    /** 包私有测试 seam：首尾帧/首帧协议装配 */
    ObjectNode buildSubmitBody(VideoGenerationRequest request, int duration) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        input.put("prompt", request.prompt());
        if (model.startsWith("wan2.6")) {
            input.put("img_url", request.firstFrameUrl());
            if (request.lastFrameUrl() != null && !request.lastFrameUrl().isBlank()) {
                log.warn("wan2.6-i2v 仅支持首帧，忽略尾帧: {}", request.lastFrameUrl());
            }
        } else {
            ArrayNode media = input.putArray("media");
            media.addObject().put("type", "first_frame").put("url", request.firstFrameUrl());
            if (request.lastFrameUrl() != null && !request.lastFrameUrl().isBlank()) {
                media.addObject().put("type", "last_frame").put("url", request.lastFrameUrl());
            }
        }
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("resolution", resolution);
        parameters.put("duration", duration);
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
                throw new RuntimeException("i2v 轮询失败 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode output = objectMapper.readTree(resp.body()).path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                String url = output.path("video_url").asText(null);
                if (url == null || url.isBlank()) {
                    throw new RuntimeException("i2v 成功但无 video_url: " + resp.body());
                }
                return url;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                throw new RuntimeException("i2v 任务失败: " + resp.body());
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("i2v 任务超时（" + timeoutSeconds + "s）: " + taskId);
    }

    private GeneratedVideo transfer(String videoUrl, int duration) throws Exception {
        HttpRequest download = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> dl = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        if (dl.statusCode() != 200) {
            throw new RuntimeException("i2v 结果下载失败 HTTP " + dl.statusCode());
        }
        String fileId = storageService.upload(new ByteArrayInputStream(dl.body()),
                "wan_video_" + System.currentTimeMillis() + ".mp4", "video/mp4");
        return new GeneratedVideo(storageService.getAccessUrl(fileId), fileId, getType(), duration);
    }
}