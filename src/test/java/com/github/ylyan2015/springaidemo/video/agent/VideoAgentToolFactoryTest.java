package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.BgmService;
import com.github.ylyan2015.springaidemo.service.ImageGenerationService;
import com.github.ylyan2015.springaidemo.service.ImagePromptOptimizerService;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoExportService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 工具工厂单元测试（Phase 3 切片1）
 * 覆盖：6 工具注册/元数据、委托正确性、异常转 JSON 失败结果
 */
class VideoAgentToolFactoryTest {

    private StoryScriptService storyScriptService;
    private VideoTemplateService videoTemplateService;
    private ImagePromptOptimizerService promptOptimizerService;
    private ImageGenerationService imageGenerationService;
    private ImageQualityService qualityService;
    private VideoExportService videoExportService;
    private BgmService bgmService;
    private com.github.ylyan2015.springaidemo.service.StoryCharacterService storyCharacterService;
    private com.github.ylyan2015.springaidemo.service.VideoGenerationService videoGenerationService;
    private com.github.ylyan2015.springaidemo.service.ShotCandidateService shotCandidateService;
    private VideoAgentToolFactory factory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        storyScriptService = mock(StoryScriptService.class);
        videoTemplateService = mock(VideoTemplateService.class);
        promptOptimizerService = mock(ImagePromptOptimizerService.class);
        imageGenerationService = mock(ImageGenerationService.class);
        qualityService = mock(ImageQualityService.class);
        videoExportService = mock(VideoExportService.class);
        bgmService = mock(BgmService.class);
        storyCharacterService = mock(com.github.ylyan2015.springaidemo.service.StoryCharacterService.class);
        videoGenerationService = mock(com.github.ylyan2015.springaidemo.service.VideoGenerationService.class);
        shotCandidateService = mock(com.github.ylyan2015.springaidemo.service.ShotCandidateService.class);
        factory = new VideoAgentToolFactory(storyScriptService, videoTemplateService,
                promptOptimizerService, imageGenerationService, qualityService,
                videoExportService, bgmService, storyCharacterService, videoGenerationService,
                shotCandidateService);
        ReflectionTestUtils.setField(factory, "selfBaseUrl", "http://localhost:8080");
    }

    private ToolCallback tool(String name) {
        return factory.createTools().stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("tool missing: " + name));
    }

    private JsonNode call(String name, String inputJson) throws Exception {
        return objectMapper.readTree(tool(name).call(inputJson));
    }

    @Test
    void registersNineToolsWithUniqueNames() {
        List<ToolCallback> tools = factory.createTools();
        assertEquals(9, tools.size());
        assertEquals(9, tools.stream().map(t -> t.getToolDefinition().name()).distinct().count());
    }

    @Test
    void inpaintImageDelegates() throws Exception {
        com.github.ylyan2015.springaidemo.entity.ShotCandidate c =
                new com.github.ylyan2015.springaidemo.entity.ShotCandidate();
        c.setId(8L);
        c.setUrl("/images/inpainted.png");
        when(shotCandidateService.inpaint(eq(5L), any(double[].class), eq("fix hands")))
                .thenReturn(c);
        JsonNode out = call("inpaintImage",
                "{\"shotId\":5,\"x\":0.1,\"y\":0.2,\"w\":0.5,\"h\":0.4,\"instruction\":\"fix hands\"}");
        assertTrue(out.get("success").asBoolean());
        assertEquals("/images/inpainted.png", out.get("url").asText());
    }

    @Test
    void generateShotVideoDelegates() throws Exception {
        StoryShot shot = new StoryShot();
        shot.setId(5L);
        shot.setVideoStatus("READY");
        when(videoGenerationService.generateForShot(1L, 5L)).thenReturn(shot);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", 5L);
        map.put("videoStatus", "READY");
        when(storyScriptService.shotToMap(shot)).thenReturn(map);
        JsonNode out = call("generateShotVideo", "{\"scriptId\":1,\"shotId\":5}");
        assertTrue(out.get("success").asBoolean());
        assertEquals("READY", out.get("videoStatus").asText());
    }

    @Test
    void generateCharactersDelegates() throws Exception {
        when(storyCharacterService.listByScript(1L)).thenReturn(List.of(Map.of("name", "林晚")));
        JsonNode out = call("generateCharacters", "{\"scriptId\":1}");
        assertTrue(out.get("success").asBoolean());
        assertEquals("林晚", out.get("characters").get(0).get("name").asText());
    }

    @Test
    void toolDefinitionsHaveDescriptionsAndInputSchemas() {
        for (ToolCallback t : factory.createTools()) {
            assertFalse(t.getToolDefinition().description().isBlank(), t.getToolDefinition().name());
            assertTrue(t.getToolDefinition().inputSchema().contains("properties"), t.getToolDefinition().name());
        }
        assertTrue(tool("generateStoryboard").getToolDefinition().inputSchema().contains("idea"));
    }

    @Test
    void generateStoryboardDelegatesToScriptService() throws Exception {
        StoryScript script = new StoryScript();
        script.setId(9L);
        when(storyScriptService.generateAndSaveScript("猫咪日常")).thenReturn(script);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", 9L);
        when(storyScriptService.scriptToMap(script)).thenReturn(map);

        JsonNode out = call("generateStoryboard", "{\"idea\":\"猫咪日常\"}");

        assertTrue(out.get("success").asBoolean());
        assertEquals(9, out.get("id").asInt());
        verify(videoTemplateService, never()).applyTemplate(anyLong(), anyString());
    }

    @Test
    void generateStoryboardWithTemplateUsesApplyTemplate() throws Exception {
        StoryScript script = new StoryScript();
        when(videoTemplateService.applyTemplate(3L, "产品发布")).thenReturn(script);
        when(storyScriptService.scriptToMap(script)).thenReturn(new LinkedHashMap<>());

        JsonNode out = call("generateStoryboard", "{\"idea\":\"产品发布\",\"templateId\":3}");

        assertTrue(out.get("success").asBoolean());
        verify(videoTemplateService).applyTemplate(3L, "产品发布");
        verify(storyScriptService, never()).generateAndSaveScript(anyString());
    }

    @Test
    void generateStoryboardErrorReturnsFailureJson() throws Exception {
        when(storyScriptService.generateAndSaveScript(anyString()))
                .thenThrow(new RuntimeException("用户未登录"));
        JsonNode out = call("generateStoryboard", "{\"idea\":\"x\"}");
        assertFalse(out.get("success").asBoolean());
        assertTrue(out.get("message").asText().contains("用户未登录"));
    }

    @Test
    void enhancePromptDelegates() throws Exception {
        when(promptOptimizerService.enhancePrompt("清晨咖啡", "CINEMATIC_REALISM"))
                .thenReturn(Map.of("enhancedPrompt", "cinematic coffee shot"));
        JsonNode out = call("enhancePrompt",
                "{\"sceneDescription\":\"清晨咖啡\",\"style\":\"CINEMATIC_REALISM\"}");
        assertTrue(out.get("success").asBoolean());
        assertEquals("cinematic coffee shot", out.get("enhancedPrompt").asText());
    }

    @Test
    void generateImageDelegatesAndReturnsShot() throws Exception {
        StoryShot shot = new StoryShot();
        shot.setId(5L);
        when(imageGenerationService.generateForShot(1L, 5L, null)).thenReturn(shot);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", 5L);
        map.put("imageStatus", "READY");
        when(storyScriptService.shotToMap(shot)).thenReturn(map);

        JsonNode out = call("generateImage", "{\"scriptId\":1,\"shotId\":5}");

        assertTrue(out.get("success").asBoolean());
        assertEquals("READY", out.get("imageStatus").asText());
    }

    @Test
    void evaluateImageReturnsScores() throws Exception {
        ImageQualityScore score = new ImageQualityScore();
        score.setOverallScore(8.0);
        score.setClarityScore(8.0);
        score.setRelevanceScore(8.0);
        score.setAestheticScore(8.0);
        score.setFeedback("良好");
        score.setLlmEvaluated(true);
        when(qualityService.evaluate("http://localhost:8080/images/1.png", "a cat")).thenReturn(score);

        JsonNode out = call("evaluateImage", "{\"imageUrl\":\"/images/1.png\",\"prompt\":\"a cat\"}");

        assertTrue(out.get("success").asBoolean());
        assertEquals(8.0, out.get("overallScore").asDouble(), 0.01);
        assertTrue(out.get("llmEvaluated").asBoolean());
    }

    @Test
    void evaluateImageDownloadFailureReturnsFailure() throws Exception {
        when(qualityService.evaluate(anyString(), anyString())).thenReturn(null);
        JsonNode out = call("evaluateImage", "{\"imageUrl\":\"/images/y.png\",\"prompt\":\"p\"}");
        assertFalse(out.get("success").asBoolean());
    }

    @Test
    void evaluateImageRejectsExternalUrls() throws Exception {
        // m2（SSRF 防护）：任意绝对 URL/协议均拒绝，仅允许本服务存储相对路径
        for (String url : new String[]{"http://169.254.169.254/latest/meta-data",
                "http://127.0.0.1:9000/internal", "file:///etc/passwd", "//evil.com/x.png"}) {
            JsonNode out = call("evaluateImage", "{\"imageUrl\":\"" + url + "\",\"prompt\":\"p\"}");
            assertFalse(out.get("success").asBoolean(), "应拒绝: " + url);
        }
        verify(qualityService, never()).evaluate(anyString(), anyString());
    }

    @Test
    void exportVideoPreviewDelegates() throws Exception {
        when(videoExportService.exportPreview(1L, "16:9", null, false, "zh"))
                .thenReturn(Map.of("video", Map.of("videoUrl", "/images/v.mp4")));
        JsonNode out = call("exportVideo", "{\"scriptId\":1,\"preview\":true}");
        assertTrue(out.get("success").asBoolean());
        verify(videoExportService).exportPreview(1L, "16:9", null, false, "zh");
        verify(videoExportService, never()).exportHd(anyLong(), anyString(), anyString(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void exportVideoHdDelegatesWithDefaults() throws Exception {
        when(videoExportService.exportHd(1L, "1080p", "9:16", 2L, true, false, "en"))
                .thenReturn(new LinkedHashMap<>());
        JsonNode out = call("exportVideo",
                "{\"scriptId\":1,\"resolution\":\"1080p\",\"aspectRatio\":\"9:16\",\"bgmId\":2,\"subtitle\":true,\"subtitleLanguage\":\"en\"}");
        assertTrue(out.get("success").asBoolean());
        verify(videoExportService).exportHd(1L, "1080p", "9:16", 2L, true, false, "en");
    }

    @Test
    void recommendBgmDelegatesWithCurrentUser() throws Exception {
        when(bgmService.requireCurrentUserId()).thenReturn(7L);
        when(bgmService.recommendBgm("温馨猫咪", 7L)).thenReturn(Map.of("category", "calm"));
        JsonNode out = call("recommendBgm", "{\"theme\":\"温馨猫咪\"}");
        assertTrue(out.get("success").asBoolean());
        assertEquals("calm", out.get("category").asText());
    }

    @Test
    void invalidInputJsonReturnsFailureInsteadOfThrowing() throws Exception {
        JsonNode out = call("generateStoryboard", "{\"templateId\":3}"); // 缺 idea
        assertFalse(out.get("success").asBoolean());
    }
}