package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateRequest;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateResponse;
import com.github.ylyan2015.springaidemo.entity.ImageRecord;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.exception.ImageGenerationException;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import com.github.ylyan2015.springaidemo.repository.ImageRecordRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 图像生成服务
 * 通过 DashScope HTTP API 调用通义万相模型，支持多存储方式
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    /** DashScope 通义万相 API 地址（wan2.6 新版同步协议端点；旧版 text2image 端点不接受 wan2.6，会报 url error） */
    private static final String DASHSCOPE_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /** wan2.6-t2i 官方推荐分辨率（总像素须在 [1280*1280, 1440*1440]、宽高比 [1:4, 4:1] 内） */
    private static final int[][] WAN26_RECOMMENDED_SIZES = {
            {1280, 1280},  // 1:1
            {1104, 1472},  // 3:4
            {1472, 1104},  // 4:3
            {960, 1696},   // 9:16
            {1696, 960}    // 16:9
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StorageService storageService;
    private final ImageRecordRepository imageRecordRepository;
    private final UserRepository userRepository;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    /** HTTP 客户端，用于调用 API 和下载图片 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ImageService(StorageService storageService,
                        ImageRecordRepository imageRecordRepository,
                        UserRepository userRepository) {
        this.storageService = storageService;
        this.imageRecordRepository = imageRecordRepository;
        this.userRepository = userRepository;
    }

    /**
     * 生成图像
     * 调用 DashScope 通义万相 API，下载图片后转存到配置的存储服务
     *
     * @param request 图像生成请求
     * @return 图像生成响应
     */
    // 注意：不加 @Transactional——方法内含 60s 级外部 API/下载，长事务会占死连接池
    // （release 审查 M9）；末尾单次 repository.save 自带独立事务
    public ImageGenerateResponse generateImage(ImageGenerateRequest request) {
        Long userId = getCurrentUserId();

        // 1. 参数校验
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new ImageGenerationException("提示词不能为空");
        }
        if (dashscopeApiKey == null || dashscopeApiKey.isEmpty()) {
            throw new ImageGenerationException("DashScope API Key 未配置，请在 application.yml 中设置 spring.ai.dashscope.api-key");
        }

        // 2. 解析尺寸参数
        int width = 1024;
        int height = 1024;
        if (request.getSize() != null && request.getSize().contains("x")) {
            String[] parts = request.getSize().split("x");
            try {
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.warn("图像尺寸格式无效: {}, 使用默认 1024x1024", request.getSize());
            }
        }
        // wan2.6 尺寸约束：总像素 [1280*1280, 1440*1440]，不满足时映射到最接近的官方推荐分辨率
        int[] mapped = mapToWan26Size(width, height);
        width = mapped[0];
        height = mapped[1];
        String size = width + "*" + height;

        // 3. 构建 API 请求体
        int n = request.getN() != null ? Math.min(Math.max(request.getN(), 1), 4) : 1;
        ObjectNode requestBody = buildDashScopeRequest(request.getPrompt(), size, n);

        // 4. 调用 DashScope API
        log.info("🎨 图像生成请求: prompt='{}', size={}, n={}", request.getPrompt(), size, n);
        JsonNode responseJson = callDashScopeApi(requestBody);

        // 5. 解析响应，提取图片 URL
        List<String> imageUrls = parseImageUrls(responseJson);
        if (imageUrls.isEmpty()) {
            throw new ImageGenerationException("AI 未返回任何图片");
        }

        String imageUrl = imageUrls.get(0);

        // 6. 生成文件名
        String ext = "png";
        if (imageUrl != null) {
            String urlLower = imageUrl.toLowerCase();
            if (urlLower.contains(".jpg") || urlLower.contains(".jpeg")) {
                ext = "jpg";
            } else if (urlLower.contains(".webp")) {
                ext = "webp";
            }
        }
        String fileName = "ai_image_" + System.currentTimeMillis() + "." + ext;

        // 7. 下载并转存到存储服务
        try {
            String fileId = downloadAndStore(imageUrl, fileName, "image/" + ext);

            // 获取文件大小（try-with-resources 防 read 异常时句柄泄漏，release 审查 Minor#6）
            long fileSize = 0;
            try (InputStream sizeStream = storageService.download(fileId)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = sizeStream.read(buf)) != -1) {
                    fileSize += read;
                }
            } catch (Exception e) {
                log.warn("无法获取文件大小: {}", e.getMessage());
            }

            // 8. 保存记录到数据库
            String accessUrl = storageService.getAccessUrl(fileId);
            ImageRecord record = new ImageRecord(
                    userId, request.getPrompt(), accessUrl, fileId, fileName,
                    storageService.getStorageType(), fileSize, width, height
            );
            if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
                record.setSessionId(request.getSessionId());
            }
            imageRecordRepository.save(record);

            log.info("✓ 图像已保存，fileId={}, storage={}, url={}", fileId, storageService.getStorageType(), accessUrl);

            // 9. 构建响应
            ImageGenerateResponse response = new ImageGenerateResponse();
            response.setImageUrl(accessUrl);
            response.setFileId(fileId);
            response.setFileName(fileName);
            response.setStorageType(storageService.getStorageType());
            response.setPrompt(request.getPrompt());
            response.setFileSize(fileSize);
            response.setWidth(width);
            response.setHeight(height);
            response.setCreateTime(record.getCreateTime());
            response.setSessionId(request.getSessionId());

            return response;

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ 图片转存失败: {}", e.getMessage());
            throw new StorageException("图片转存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 DashScope API 请求体（wan2.6 新版协议：input.messages + parameters.size 用 宽*高）
     */
    private ObjectNode buildDashScopeRequest(String prompt, String size, int n) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "wan2.6-t2i");

        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ObjectNode textContent = message.putArray("content").addObject();
        textContent.put("text", prompt);

        ObjectNode parameters = body.putObject("parameters");
        parameters.put("size", size);
        parameters.put("n", n);

        return body;
    }

    /**
     * 将请求尺寸映射到 wan2.6-t2i 支持的分辨率：
     * 已满足总像素与宽高比约束时按原比例缩放到约束范围内，否则取宽高比最接近的官方推荐分辨率
     */
    private int[] mapToWan26Size(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new int[]{1280, 1280};
        }
        long pixels = (long) width * height;
        double ratio = (double) width / height;
        if (pixels >= 1280L * 1280 && pixels <= 1440L * 1440 && ratio >= 0.25 && ratio <= 4.0) {
            return new int[]{width, height};
        }
        if (ratio >= 0.25 && ratio <= 4.0) {
            // 比例合法但总像素越界：等比缩放到约束区间（放大向上取整保证不低于下限，缩小向下取整保证不超上限）
            boolean scaleUp = pixels < 1280L * 1280;
            double scale = scaleUp
                    ? Math.sqrt((1280L * 1280) / (double) pixels)
                    : Math.sqrt((1440L * 1440) / (double) pixels);
            int w = scaleUp ? (int) Math.ceil(width * scale) : (int) Math.floor(width * scale);
            int h = scaleUp ? (int) Math.ceil(height * scale) : (int) Math.floor(height * scale);
            return new int[]{w, h};
        }
        // 比例超限或非法：取宽高比最接近的推荐分辨率
        int[] best = WAN26_RECOMMENDED_SIZES[0];
        double bestDiff = Double.MAX_VALUE;
        for (int[] candidate : WAN26_RECOMMENDED_SIZES) {
            double diff = Math.abs(Math.log(ratio / ((double) candidate[0] / candidate[1])));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return new int[]{best[0], best[1]};
    }

    /**
     * 调用 DashScope API
     */
    private JsonNode callDashScopeApi(ObjectNode requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DASHSCOPE_API_URL))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = String.format("DashScope API 返回错误 [%d]: %s",
                        response.statusCode(), response.body());
                log.error("❌ {}", errorMsg);
                throw new ImageGenerationException(errorMsg);
            }

            return objectMapper.readTree(response.body());
        } catch (ImageGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ DashScope API 调用失败: {}", e.getMessage());
            throw new ImageGenerationException("DashScope API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 DashScope 响应中提取图片 URL
     */
    private List<String> parseImageUrls(JsonNode responseJson) {
        List<String> urls = new ArrayList<>();

        // wan2.6 新版协议响应格式: { "output": { "choices": [ { "message": { "content": [ { "image": "..." } ] } } ] } }
        JsonNode output = responseJson.get("output");
        if (output != null) {
            JsonNode choices = output.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode content = choice.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode item : content) {
                            JsonNode imageNode = item.get("image");
                            if (imageNode != null && !imageNode.isNull()) {
                                urls.add(imageNode.asText());
                            }
                        }
                    }
                }
            }

            // 兼容旧版协议响应格式: { "output": { "results": [ { "url": "..." } ] } }
            JsonNode results = output.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode result : results) {
                    JsonNode urlNode = result.get("url");
                    if (urlNode != null && !urlNode.isNull()) {
                        urls.add(urlNode.asText());
                    }
                    // 也支持 base64 格式
                    JsonNode b64Node = result.get("b64_json");
                    if (b64Node != null && !b64Node.isNull()) {
                        urls.add(b64Node.asText());
                    }
                }
            }
        }

        log.info("DashScope 返回 {} 张图片", urls.size());
        return urls;
    }

    /**
     * 获取当前用户的图片生成历史
     */
    public List<ImageRecord> getUserImageRecords() {
        Long userId = getCurrentUserId();
        return imageRecordRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }

    /**
     * 获取用户在某个会话中的图片生成记录
     */
    public List<ImageRecord> getUserImageRecordsBySession(String sessionId) {
        Long userId = getCurrentUserId();
        return imageRecordRepository.findByUserIdAndSessionIdOrderByCreateTimeDesc(userId, sessionId);
    }

    /**
     * 下载 AI 生成的图片并上传到存储服务
     */
    private String downloadAndStore(String imageUrl, String fileName, String contentType) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new StorageException("图片 URL 为空");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new StorageException("下载图片失败，HTTP " + response.statusCode());
            }

            return storageService.upload(response.body(), fileName, contentType);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("图片下载转存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new ImageGenerationException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ImageGenerationException("用户不存在"));
        return user.getId();
    }
}
