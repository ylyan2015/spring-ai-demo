package com.github.ylyan2015.springaidemo.video.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 图片质量评估服务（模块 J）
 * 多模态 LLM 按清晰度/相关性/美学三维度打分（0-10），低分触发自动重试；
 * 未配置多模态 API 或调用失败时降级为规则评估（分辨率启发式），不阻塞生成流程。
 * 评估走独立的 OpenAI 兼容端点（默认 DashScope qwen-vl），不占用聊天模型切换状态。
 */
@Service
public class ImageQualityService {

    private static final Logger log = LoggerFactory.getLogger(ImageQualityService.class);

    private static final String EVALUATE_SYSTEM_PROMPT =
            "你是专业的图像质量评审专家。请从清晰度、与提示词的相关性、美学三个维度对图片打分"
                    + "（0-10，可保留一位小数），并给出简短中文改进建议。"
                    + "仅输出 JSON：{\"clarity\":数字,\"relevance\":数字,\"aesthetic\":数字,\"feedback\":\"建议\"}，"
                    + "不要输出任何其他内容。";

    private final ObjectMapper objectMapper;

    /** 是否启用质量评估（关闭时生成流程完全不变） */
    @Value("${image.quality.enabled:false}")
    private boolean enabled;

    /** OpenAI 兼容多模态端点 */
    @Value("${image.quality.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** 多模态 API Key（留空时降级为规则评估） */
    @Value("${image.quality.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    /** 多模态评估模型 */
    @Value("${image.quality.model:qwen-vl-max}")
    private String modelName;

    /** 及格阈值（0-10），低于该分自动重新生成 */
    @Value("${image.quality.threshold:6.0}")
    private double threshold;

    /** 低分图片最大重试次数 */
    @Value("${image.quality.max-retries:2}")
    private int maxRetries;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile ChatClient cachedClient;

    public ImageQualityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getThreshold() {
        return threshold;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /** 低于阈值需要重新生成（null 评分视为无法判断，不触发重试） */
    public boolean shouldRegenerate(ImageQualityScore score) {
        return score != null && score.getOverallScore() < threshold;
    }

    /**
     * 评估指定 URL 的图片质量；下载失败返回 null（调用方保留原图，不阻塞生成）
     */
    public ImageQualityScore evaluate(String imageUrl, String prompt) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                log.warn("Quality evaluation skipped: image download failed [{}] {}", response.statusCode(), imageUrl);
                return null;
            }
            return evaluateBytes(response.body(), prompt);
        } catch (Exception e) {
            log.warn("Quality evaluation skipped: cannot download image {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 评估图片字节：优先多模态 LLM，未配置/失败时降级规则评估
     */
    public ImageQualityScore evaluateBytes(byte[] imageBytes, String prompt) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                UserMessage message = UserMessage.builder()
                        .text("分镜提示词：" + prompt + "\n请评估这张图片的质量。")
                        .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes)))
                        .build();
                String content = chatClient().prompt()
                        .system(EVALUATE_SYSTEM_PROMPT)
                        .messages(message)
                        .call()
                        .content();
                ImageQualityScore score = parseScore(content);
                if (score != null) {
                    return score;
                }
                log.warn("Multimodal quality response unparseable, falling back to rules: {}", content);
            } catch (Exception e) {
                log.warn("Multimodal quality evaluation failed, falling back to rules: {}", e.getMessage());
            }
        }
        return ruleBasedScore(imageBytes);
    }

    /**
     * 解析 LLM 评分 JSON（容忍 markdown 代码围栏）；非法返回 null
     */
    static ImageQualityScore parseScore(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(content.substring(start, end + 1));
            JsonNode clarity = node.get("clarity");
            JsonNode relevance = node.get("relevance");
            JsonNode aesthetic = node.get("aesthetic");
            if (clarity == null || relevance == null || aesthetic == null
                    || !clarity.isNumber() || !relevance.isNumber() || !aesthetic.isNumber()) {
                return null;
            }
            ImageQualityScore score = new ImageQualityScore();
            score.setClarityScore(clamp(clarity.asDouble()));
            score.setRelevanceScore(clamp(relevance.asDouble()));
            score.setAestheticScore(clamp(aesthetic.asDouble()));
            score.setOverallScore(Math.round(
                    (score.getClarityScore() + score.getRelevanceScore() + score.getAestheticScore()) / 3 * 10) / 10.0);
            score.setFeedback(node.hasNonNull("feedback") ? node.get("feedback").asText() : "");
            score.setLlmEvaluated(true);
            return score;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 规则降级评估：按分辨率启发式打清晰度分，相关性/美学给中性基准分（规则无法判断语义）
     */
    static ImageQualityScore ruleBasedScore(byte[] imageBytes) {
        double clarity = 5.0;
        String detail = "无法解析图片尺寸";
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img != null) {
                int w = img.getWidth();
                int h = img.getHeight();
                int min = Math.min(w, h);
                int max = Math.max(w, h);
                if (min >= 720 && max >= 1280) {
                    clarity = 8.0;
                } else if (min >= 480) {
                    clarity = 6.5;
                }
                detail = w + "x" + h;
            }
        } catch (Exception ignored) {
            // 解析失败保留最低清晰度分
        }
        ImageQualityScore score = new ImageQualityScore();
        score.setClarityScore(clarity);
        score.setRelevanceScore(6.0);
        score.setAestheticScore(6.0);
        score.setOverallScore(Math.round((clarity + 6.0 + 6.0) / 3 * 10) / 10.0);
        score.setFeedback("规则评估（" + detail + "）：未启用多模态模型，仅按分辨率启发式打分");
        score.setLlmEvaluated(false);
        return score;
    }

    /**
     * 懒构建独立的 OpenAI 兼容 ChatClient（包级可见便于测试替换）
     */
    ChatClient chatClient() {
        ChatClient client = cachedClient;
        if (client == null) {
            synchronized (this) {
                if (cachedClient == null) {
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .build();
                    OpenAiChatModel chatModel = OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(modelName)
                                    .temperature(0.1)
                                    .build())
                            .build();
                    cachedClient = ChatClient.create(chatModel);
                }
                client = cachedClient;
            }
        }
        return client;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(10, value));
    }
}