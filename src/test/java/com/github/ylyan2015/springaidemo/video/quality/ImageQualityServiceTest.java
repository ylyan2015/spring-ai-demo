package com.github.ylyan2015.springaidemo.video.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 图片质量评估服务单元测试（模块 J）
 * 覆盖：LLM 评分解析、规则降级、阈值判断、多模态调用与失败回退
 */
class ImageQualityServiceTest {

    private ImageQualityService service;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new ImageQualityService(new ObjectMapper()));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "threshold", 6.0);
        ReflectionTestUtils.setField(service, "maxRetries", 2);
        chatClient = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
    }

    @AfterEach
    void tearDown() {
        // 清理可能启动的本地图片服务器
        if (imageServer != null) {
            imageServer.stop(0);
            imageServer = null;
        }
    }

    private HttpServer imageServer;

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private void mockLlm(String content) {
        doReturn(chatClient).when(service).chatClient();
        when(chatClient.prompt().system(anyString()).messages(any(org.springframework.ai.chat.messages.Message.class))
                .call().content()).thenReturn(content);
    }

    // ==================== LLM 评分解析 ====================

    @Test
    void parseScoreComputesOverallAsAverage() {
        ImageQualityScore score = ImageQualityService.parseScore(
                "{\"clarity\":8,\"relevance\":7,\"aesthetic\":9,\"feedback\":\"构图良好\"}");
        assertNotNull(score);
        assertEquals(8.0, score.getOverallScore(), 0.01);
        assertEquals(8.0, score.getClarityScore(), 0.01);
        assertEquals("构图良好", score.getFeedback());
        assertTrue(score.isLlmEvaluated());
    }

    @Test
    void parseScoreStripsMarkdownFences() {
        ImageQualityScore score = ImageQualityService.parseScore(
                "```json\n{\"clarity\":6,\"relevance\":6,\"aesthetic\":6,\"feedback\":\"ok\"}\n```");
        assertNotNull(score);
        assertEquals(6.0, score.getOverallScore(), 0.01);
    }

    @Test
    void parseScoreInvalidReturnsNull() {
        assertNull(ImageQualityService.parseScore("抱歉，我无法评估"));
        assertNull(ImageQualityService.parseScore(null));
        assertNull(ImageQualityService.parseScore("{\"clarity\":\"abc\"}"));
    }

    // ==================== 规则降级 ====================

    @Test
    void ruleBasedScoreHighResolutionGetsHighClarity() throws Exception {
        ImageQualityScore score = ImageQualityService.ruleBasedScore(pngBytes(1280, 720));
        assertNotNull(score);
        assertTrue(score.getClarityScore() >= 7.0);
        assertFalse(score.isLlmEvaluated());
    }

    @Test
    void ruleBasedScoreSmallImageGetsLowerClarity() throws Exception {
        ImageQualityScore score = ImageQualityService.ruleBasedScore(pngBytes(320, 240));
        assertNotNull(score);
        assertTrue(score.getClarityScore() < 7.0);
    }

    @Test
    void ruleBasedScoreCorruptBytesStillReturnsScore() {
        ImageQualityScore score = ImageQualityService.ruleBasedScore(new byte[]{1, 2, 3});
        assertNotNull(score);
        assertFalse(score.isLlmEvaluated());
    }

    // ==================== 阈值判断 ====================

    @Test
    void shouldRegenerateBelowThreshold() {
        ImageQualityScore low = ImageQualityService.parseScore(
                "{\"clarity\":4,\"relevance\":4,\"aesthetic\":4,\"feedback\":\"模糊\"}");
        assertTrue(service.shouldRegenerate(low));
        ImageQualityScore high = ImageQualityService.parseScore(
                "{\"clarity\":8,\"relevance\":8,\"aesthetic\":8,\"feedback\":\"好\"}");
        assertFalse(service.shouldRegenerate(high));
        assertFalse(service.shouldRegenerate(null));
    }

    // ==================== 评估入口 ====================

    @Test
    void evaluateWithoutApiKeyUsesRuleFallback() throws Exception {
        ImageQualityScore score = service.evaluateBytes(pngBytes(1280, 720), "a cat");
        assertNotNull(score);
        assertFalse(score.isLlmEvaluated());
        Mockito.verify(service, Mockito.never()).chatClient();
    }

    @Test
    void evaluateWithLlmReturnsParsedScore() throws Exception {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        mockLlm("{\"clarity\":9,\"relevance\":8,\"aesthetic\":7,\"feedback\":\"清晰且贴合提示词\"}");
        ImageQualityScore score = service.evaluateBytes(pngBytes(1280, 720), "a cat");
        assertNotNull(score);
        assertTrue(score.isLlmEvaluated());
        assertEquals(8.0, score.getOverallScore(), 0.01);
    }

    @Test
    void evaluateLlmFailureFallsBackToRules() throws Exception {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        doReturn(chatClient).when(service).chatClient();
        when(chatClient.prompt().system(anyString()).messages(any(org.springframework.ai.chat.messages.Message.class))
                .call().content()).thenThrow(new RuntimeException("multimodal unavailable"));
        ImageQualityScore score = service.evaluateBytes(pngBytes(1280, 720), "a cat");
        assertNotNull(score);
        assertFalse(score.isLlmEvaluated());
    }

    @Test
    void evaluateDownloadsImageFromUrl() throws Exception {
        byte[] png = pngBytes(1280, 720);
        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext("/img.png", ex -> {
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        imageServer.start();
        String url = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/img.png";

        ImageQualityScore score = service.evaluate(url, "a cat");
        assertNotNull(score);
        assertFalse(score.isLlmEvaluated());
        assertTrue(score.getClarityScore() >= 7.0);
    }

    @Test
    void evaluateUnreachableUrlReturnsNull() {
        assertNull(service.evaluate("http://127.0.0.1:1/none.png", "a cat"));
    }

    @Test
    void disabledServiceReportsNotEnabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertFalse(service.isEnabled());
    }
}