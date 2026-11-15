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
import java.util.List;

/**
 * 万相 2.7 图像生成器（Phase 4 切片 1：角色参考图一致性）
 * 协议（2026-11 核对，官方文档为准）：multimodal-generation 同步端点；
 * input.messages[0].content = [{text} + {image}×0-9]（URL 或 base64 data URI）；
 * parameters.size = "W*H"；响应 output.choices[0].message.content[*].image（兼容 output.results[0].url）。
 * 参考图 ≤9 超出截断取前 9；产物一律存储转存。
 */
public class Wan27ImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(Wan27ImageGenerator.class);
    private static final int MAX_REFERENCES = 9;
    private static final String GENERATION_PATH = "/services/aigc/multimodal-generation/generation";

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;

    public Wan27ImageGenerator(StorageService storageService, ObjectMapper objectMapper,
                               HttpClient httpClient, String baseUrl, String apiKey,
                               String model, int timeoutSeconds) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        return generateWithReferences(prompt, negativePrompt, width, height, List.of());
    }

    @Override
    public GeneratedImage generateWithReferences(String prompt, String negativePrompt,
                                                 int width, int height, List<String> referenceImageUrls) {
        if (!isAvailable()) {
            throw new RuntimeException("wan27 不可用：需配置 DASHSCOPE_API_KEY");
        }
        List<String> refs = referenceImageUrls == null ? List.of() : referenceImageUrls;
        if (refs.size() > MAX_REFERENCES) {
            log.warn("wan2.7-image supports at most {} reference images, truncating {} -> {}",
                    MAX_REFERENCES, refs.size(), MAX_REFERENCES);
            refs = refs.subList(0, MAX_REFERENCES);
        }
        try {
            String body = objectMapper.writeValueAsString(buildBody(prompt, width, height, refs));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + GENERATION_PATH))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("wan2.7-image HTTP " + response.statusCode() + ": " + response.body());
            }
            String imageUrl = parseImageUrl(objectMapper.readTree(response.body()));
            return transfer(imageUrl, width, height);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("wan2.7-image 生成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supportsReferences() {
        return true;
    }

    @Override
    public boolean supportsEdit() {
        return true;
    }

    /**
     * 框选局部重绘（Phase 4 切片 3，协议 2026-11 核对）：
     * 同 multimodal-generation 同步端点；content=[{image},{text 指令}]；
     * parameters.bbox_list=[[[x1,y1,x2,y2]]]（原图绝对像素，左上原点，单图≤2 框）。
     */
    @Override
    public GeneratedImage editWithMask(String instruction, String imageUrl, double[] rect, int imgW, int imgH) {
        if (!isAvailable()) {
            throw new RuntimeException("wan27 不可用：需配置 DASHSCOPE_API_KEY");
        }
        try {
            String body = objectMapper.writeValueAsString(buildEditBody(instruction, imageUrl, rect, imgW, imgH));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + GENERATION_PATH))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("wan2.7-image 重绘 HTTP " + response.statusCode() + ": " + response.body());
            }
            String resultUrl = parseImageUrl(objectMapper.readTree(response.body()));
            return transfer(resultUrl, imgW, imgH);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("wan2.7-image 重绘失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "wan27";
    }

    @Override
    public String getName() {
        return "通义万相 2.7 (" + model + ")";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ==================== 内部方法 ====================

    /** 包私有测试 seam：请求体装配（text + 0-9 image，size=W*H） */
    ObjectNode buildBody(String prompt, int width, int height, List<String> refs) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("text", prompt);
        for (String ref : refs) {
            content.addObject().put("image", ref);
        }
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("size", width + "*" + height);
        return root;
    }

    /** 包私有测试 seam：重绘请求体（image+text 指令，bbox_list 相对坐标→绝对像素） */
    ObjectNode buildEditBody(String instruction, String imageUrl, double[] rect, int imgW, int imgH) {
        int x1 = (int) Math.round(rect[0] * imgW);
        int y1 = (int) Math.round(rect[1] * imgH);
        int x2 = (int) Math.round((rect[0] + rect[2]) * imgW);
        int y2 = (int) Math.round((rect[1] + rect[3]) * imgH);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("image", imageUrl);
        content.addObject().put("text", instruction);
        ObjectNode parameters = root.putObject("parameters");
        ArrayNode bboxList = parameters.putArray("bbox_list");
        ArrayNode boxes = bboxList.addArray();
        ArrayNode box = boxes.addArray();
        box.add(x1);
        box.add(y1);
        box.add(x2);
        box.add(y2);
        parameters.put("size", imgW + "*" + imgH);
        parameters.put("n", 1);
        return root;
    }

    /** 响应解析：choices 形态优先，results 形态回退 */
    String parseImageUrl(JsonNode root) {
        JsonNode choices = root.path("output").path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    if (item.hasNonNull("image")) {
                        return item.get("image").asText();
                    }
                }
            }
        }
        JsonNode results = root.path("output").path("results");
        if (results.isArray() && !results.isEmpty() && results.get(0).hasNonNull("url")) {
            return results.get(0).get("url").asText();
        }
        throw new RuntimeException("wan2.7-image 响应无图片: " + root.toString());
    }

    /** 下载供应商临时 URL 并存储转存（UUID 前缀红线） */
    private GeneratedImage transfer(String imageUrl, int width, int height) throws Exception {
        HttpRequest download = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> dl = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        if (dl.statusCode() != 200) {
            throw new RuntimeException("wan2.7-image 结果下载失败 HTTP " + dl.statusCode());
        }
        String fileId = storageService.upload(new ByteArrayInputStream(dl.body()),
                "wan27_image_" + System.currentTimeMillis() + ".png", "image/png");
        return new GeneratedImage(storageService.getAccessUrl(fileId), fileId, getType(), width, height);
    }
}