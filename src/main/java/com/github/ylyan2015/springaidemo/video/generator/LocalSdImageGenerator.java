package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.exception.ImageGenerationException;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 本地图像生成器
 * 通过 HTTP API 调用本地 Stable Diffusion WebUI / OllamaDiffuser / FLUX 服务
 * 兼容 Stable Diffusion WebUI 的 /sdapi/v1/txt2img 接口协议
 * 原生支持负面提示词
 */
@Component
public class LocalSdImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(LocalSdImageGenerator.class);

    private final StorageService storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 本地 SD/FLUX 服务地址 */
    @Value("${image.local.base-url:http://127.0.0.1:7860}")
    private String baseUrl;

    /** 本地模型名称（仅用于记录） */
    @Value("${image.local.model:stable-diffusion}")
    private String modelName;

    /** 采样步数 */
    @Value("${image.local.steps:25}")
    private int steps;

    /** 提示词相关性 */
    @Value("${image.local.cfg-scale:7.0}")
    private double cfgScale;

    /** 本地生成超时（秒） */
    @Value("${image.local.timeout:120}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LocalSdImageGenerator(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        log.info("Generating image via local SD/FLUX ({}): size={}x{}", modelName, width, height);

        ObjectNode requestBody = buildTxt2ImgRequest(prompt, negativePrompt, width, height);
        JsonNode responseJson = callTxt2ImgApi(requestBody);

        // 解析响应中的 base64 图片
        JsonNode images = responseJson.get("images");
        if (images == null || !images.isArray() || images.isEmpty()) {
            throw new ImageGenerationException("本地 SD 服务未返回任何图片");
        }
        String base64Image = images.get(0).asText();

        // 解码 base64 并转存到存储服务
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        String fileName = "sd_image_" + System.currentTimeMillis() + ".png";

        String fileId = storageService.upload(new ByteArrayInputStream(imageBytes), fileName, "image/png");
        String accessUrl = storageService.getAccessUrl(fileId);

        log.info("Local SD image generated: fileId={}, url={}", fileId, accessUrl);
        return new GeneratedImage(accessUrl, fileId, getType(), width, height);
    }

    /**
     * 构建 Stable Diffusion WebUI txt2img 请求体
     */
    private ObjectNode buildTxt2ImgRequest(String prompt, String negativePrompt, int width, int height) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("negative_prompt", negativePrompt != null ? negativePrompt : "");
        body.put("width", width);
        body.put("height", height);
        body.put("steps", steps);
        body.put("cfg_scale", cfgScale);
        body.put("sampler_name", "DPM++ 2M");
        body.put("safety_checker", false);
        return body;
    }

    /**
     * 调用 txt2img API
     */
    private JsonNode callTxt2ImgApi(ObjectNode requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/sdapi/v1/txt2img"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ImageGenerationException(String.format(
                        "本地 SD API 返回错误 [%d]: %s", response.statusCode(), response.body()));
            }

            return objectMapper.readTree(response.body());
        } catch (ImageGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageGenerationException("本地 SD API 调用失败，请确认服务已启动 ("
                    + baseUrl + "): " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/sdapi/v1/sd-models"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public String getName() {
        return "本地 Stable Diffusion / FLUX (" + modelName + ")";
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
