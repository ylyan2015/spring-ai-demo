package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.exception.ImageGenerationException;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 通用 OpenAI 兼容图像生成器（POST {base-url}/images/generations）
 * 一个实现覆盖四家提供者：智谱 CogView / 硅基流动 / OpenAI / 火山方舟 Seedream，
 * 由 ImageGeneratorConfig 以不同配置注册为四个 Bean，工厂自动收录。
 *
 * 协议差异处理：
 * 1. OpenAI images 协议无 negative_prompt 参数 → 负面词拼接进 prompt 文本
 * 2. 各提供者尺寸枚举不同 → ImageSizeMapper 按宽高比就近映射（任意尺寸提供者透传）
 * 3. 响应双格式：data[0].url（智谱/硅基流动/方舟，临时链接需下载转存）或
 *    data[0].b64_json（OpenAI gpt-image-1，解码转存）
 *
 * 转存模式对齐 LocalSdImageGenerator：StorageService.upload + getAccessUrl，不写 ImageRecord。
 */
public class OpenAiCompatibleImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleImageGenerator.class);

    private final String type;
    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final List<String> fixedSizes;
    private final StorageService storageService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleImageGenerator(String type, String name, String baseUrl, String apiKey,
                                          String model, List<String> fixedSizes,
                                          StorageService storageService, HttpClient httpClient) {
        this.type = type;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.fixedSizes = fixedSizes;
        this.storageService = storageService;
        this.httpClient = httpClient;
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ImageGenerationException(name + " API Key 未配置，请设置对应环境变量后重启");
        }
        log.info("Generating image via {} ({}): size={}x{}", name, model, width, height);

        // 尺寸映射单次计算：请求体与返回元数据共用，保证 GeneratedImage 宽高为实际生成尺寸
        // （审查 minor 修复：对齐 DashScope 路径“返回实际尺寸”的 DTO 语义）
        String size = ImageSizeMapper.map(width, height, fixedSizes);
        ObjectNode body = buildRequestBody(prompt, negativePrompt, size);
        JsonNode response = callImagesApi(body);

        JsonNode first = response.path("data").path(0);
        byte[] imageBytes;
        String ext = "png";
        if (first.hasNonNull("url")) {
            String imageUrl = first.get("url").asText();
            imageBytes = download(imageUrl);
            String lower = imageUrl.toLowerCase();
            if (lower.contains(".jpg") || lower.contains(".jpeg")) {
                ext = "jpg";
            } else if (lower.contains(".webp")) {
                ext = "webp";
            }
        } else if (first.hasNonNull("b64_json")) {
            try {
                imageBytes = Base64.getDecoder().decode(first.get("b64_json").asText());
            } catch (IllegalArgumentException e) {
                throw new ImageGenerationException(name + " 图片 base64 解码失败", e);
            }
        } else {
            throw new ImageGenerationException(name + " 未返回任何图片（data[0] 无 url/b64_json）");
        }

        String fileName = type + "_image_" + System.currentTimeMillis() + "." + ext;
        String fileId = storageService.upload(new ByteArrayInputStream(imageBytes), fileName, "image/" + ext);
        String accessUrl = storageService.getAccessUrl(fileId);

        log.info("{} image generated: fileId={}, url={}", name, fileId, accessUrl);
        String[] wh = size.split("x");
        return new GeneratedImage(accessUrl, fileId, type, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
    }

    /**
     * 构建 images/generations 请求体：负面词拼接进 prompt（协议无 negative 参数）；size 为已映射尺寸
     */
    ObjectNode buildRequestBody(String prompt, String negativePrompt, String size) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException(name + " prompt 不能为空");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        String finalPrompt = prompt;
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            finalPrompt = prompt + "\n\nAvoid the following: " + negativePrompt;
        }
        body.put("prompt", finalPrompt);
        body.put("size", size);
        body.put("n", 1);
        return body;
    }

    private JsonNode callImagesApi(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/images/generations"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String errorBody = response.body() != null && response.body().length() > 300
                        ? response.body().substring(0, 300) : response.body();
                throw new ImageGenerationException(String.format(
                        "%s API 返回错误 [%d]: %s", name, response.statusCode(), errorBody));
            }
            return objectMapper.readTree(response.body());
        } catch (ImageGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageGenerationException(name + " API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载提供者返回的临时图片 URL（智谱临时链接 30 天有效，必须转存）
     */
    private byte[] download(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ImageGenerationException(name + " 图片下载失败 [" + response.statusCode() + "]");
            }
            return response.body();
        } catch (ImageGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageGenerationException(name + " 图片下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}