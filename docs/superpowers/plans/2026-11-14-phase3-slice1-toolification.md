# Phase 3 切片1：核心服务 Tool 化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 用 Spring AI 1.0.0 的 `FunctionToolCallback` 将短视频工作室 6 项核心能力包装为可被 LLM Agent 调用的 Tools，并提供工具清单内省端点，为切片2（导演 Agent ReAct）打基础。

**Architecture:** 新建 `video/agent/` 包：`AgentToolRequests`（6 个输入 record，`@ToolParam` 描述）+ `VideoAgentToolFactory`（构建 `List<ToolCallback>`，每个工具委托现有服务、异常转为 `{success:false,message}` JSON 供 LLM 感知）+ `VideoAgentController`（`GET /api/video/agent/tools` 内省）。工具在请求线程内同步执行，SecurityContext 天然可用（异步传播是切片2 的事）。

**Tech Stack:** Spring Boot 3.4.1 + Java 17 + Spring AI 1.0.0（`org.springframework.ai.tool.*`）+ JUnit 5 + Mockito

**Spec:** `VIDEO_STUDIO_PLAN.md` 第四章 Phase 3（4.2 Agent Tool 定义表）

## Global Constraints

- Spring AI 1.0.0 GA **没有** `FunctionCallback`（旧 M 系列 API），必须用 `org.springframework.ai.tool.function.FunctionToolCallback`（已用 javap 核实 builder 签名）
- 分层约定：构造器注入 + final 依赖、SLF4J 日志、统一响应 `{success, message, data}`、Javadoc
- 测试先行（TDD）：每个任务先写失败测试再实现；SecurityContext 测试用三参 `UsernamePasswordAuthenticationToken`（本切片工厂测试全 mock 服务，无需安全上下文）
- 中英文文档严格同步：README.md↔README-en.md、API_TEST.md↔API_TEST-en.md
- 不改动现有服务签名；不引入 Agent 对话端点（切片2 范围）
- 改动不提交（用户自行提交）

## 已核实的依赖签名

```java
// StoryScriptService
StoryScript generateAndSaveScript(String idea);
Map<String,Object> scriptToMap(StoryScript script);
Map<String,Object> shotToMap(StoryShot shot);
// VideoTemplateService
StoryScript applyTemplate(Long templateId, String idea);
// ImagePromptOptimizerService
Map<String,String> enhancePrompt(String sceneDescription, String style);
// ImageGenerationService
StoryShot generateForShot(Long scriptId, Long shotId, String style);
// ImageQualityService（evaluate 不受 enabled 开关限制）
ImageQualityScore evaluate(String imageUrl, String prompt); // 下载失败返回 null
// VideoExportService
Map<String,Object> exportPreview(Long scriptId, String aspectRatio, Long bgmId, boolean subtitle, String subtitleLanguage);
Map<String,Object> exportHd(Long scriptId, String resolution, String aspectRatio, Long bgmId, boolean subtitle, boolean withLogo, String subtitleLanguage);
// BgmService
Map<String,Object> recommendBgm(String theme, Long userId);
Long requireCurrentUserId();
```

## File Structure

- Create `src/main/java/com/github/ylyan2015/springaidemo/video/agent/AgentToolRequests.java` — 6 个工具输入 record（嵌套 public record，Jackson 可反序列化）
- Create `src/main/java/com/github/ylyan2015/springaidemo/video/agent/VideoAgentToolFactory.java` — 构建 6 个 ToolCallback
- Create `src/main/java/com/github/ylyan2015/springaidemo/controller/VideoAgentController.java` — GET /api/video/agent/tools
- Create `src/test/java/com/github/ylyan2015/springaidemo/video/agent/VideoAgentToolFactoryTest.java` — 13 测试
- Create `src/test/java/com/github/ylyan2015/springaidemo/controller/VideoAgentControllerTest.java` — 2 测试
- Modify `README.md` / `README-en.md` — 特性 + 项目结构 + API 概览
- Modify `API_TEST.md` / `API_TEST-en.md` — 新增 7.11 Agent 工具章节
- Modify `VIDEO_STUDIO_PLAN.md` — Phase 3 切片1 状态与实现落点

---

### Task 1: 工具输入 DTO + ToolCallback 工厂

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/AgentToolRequests.java`
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/agent/VideoAgentToolFactory.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/agent/VideoAgentToolFactoryTest.java`

**Interfaces:**
- Consumes: 上文「已核实的依赖签名」全部服务方法
- Produces: `VideoAgentToolFactory.createTools()` 返回 `List<ToolCallback>`（6 个，名称：generateStoryboard / enhancePrompt / generateImage / evaluateImage / exportVideo / recommendBgm）；工具输出为 JSON 字符串，成功含 `"success":true`，失败含 `"success":false,"message":"..."`

- [x] **Step 1: 写失败测试 VideoAgentToolFactoryTest**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.service.*;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        factory = new VideoAgentToolFactory(storyScriptService, videoTemplateService,
                promptOptimizerService, imageGenerationService, qualityService,
                videoExportService, bgmService);
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
    void registersSixToolsWithUniqueNames() {
        List<ToolCallback> tools = factory.createTools();
        assertEquals(6, tools.size());
        assertEquals(6, tools.stream().map(t -> t.getToolDefinition().name()).distinct().count());
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
        when(qualityService.evaluate("http://img/1.png", "a cat")).thenReturn(score);

        JsonNode out = call("evaluateImage", "{\"imageUrl\":\"http://img/1.png\",\"prompt\":\"a cat\"}");

        assertTrue(out.get("success").asBoolean());
        assertEquals(8.0, out.get("overallScore").asDouble(), 0.01);
        assertTrue(out.get("llmEvaluated").asBoolean());
    }

    @Test
    void evaluateImageDownloadFailureReturnsFailure() throws Exception {
        when(qualityService.evaluate(anyString(), anyString())).thenReturn(null);
        JsonNode out = call("evaluateImage", "{\"imageUrl\":\"http://x/y.png\",\"prompt\":\"p\"}");
        assertFalse(out.get("success").asBoolean());
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
```

- [x] **Step 2: 运行确认红灯**

Run: `mvn -q test -Dtest=VideoAgentToolFactoryTest`
Expected: 编译失败「找不到符号 VideoAgentToolFactory / AgentToolRequests」

- [x] **Step 3: 实现 AgentToolRequests**

```java
package com.github.ylyan2015.springaidemo.video.agent;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Agent 工具输入参数（Phase 3 切片1）
 * 每个 record 对应一个 ToolCallback 的 inputType，@ToolParam 描述会进入 JSON Schema 供 LLM 理解。
 * 可选参数以 null 表达（描述中注明），由工厂做空值兜底。
 */
public final class AgentToolRequests {

    private AgentToolRequests() {}

    /** 生成分镜脚本 */
    public record GenerateStoryboard(
            @ToolParam(description = "创意想法/主题描述，例如：清晨的城市咖啡师，治愈系短片") String idea,
            @ToolParam(description = "可选视频模板ID；提供时按模板风格生成脚本") Long templateId) {}

    /** 提示词优化 */
    public record EnhancePrompt(
            @ToolParam(description = "中文画面/场景描述") String sceneDescription,
            @ToolParam(description = "可选视觉风格名，如 CINEMATIC_REALISM / ANIME / WATERCOLOR") String style) {}

    /** 单镜头图片生成 */
    public record GenerateImage(
            @ToolParam(description = "脚本ID") Long scriptId,
            @ToolParam(description = "镜头ID") Long shotId,
            @ToolParam(description = "可选视觉风格名") String style) {}

    /** 图片质量评估 */
    public record EvaluateImage(
            @ToolParam(description = "图片访问URL") String imageUrl,
            @ToolParam(description = "生成该图片所用的提示词，用于相关性评估") String prompt) {}

    /** 视频合成导出 */
    public record ExportVideo(
            @ToolParam(description = "脚本ID，所有镜头图片须已生成") Long scriptId,
            @ToolParam(description = "true=480p快速预览（带水印），false=高清正式导出，默认 false") Boolean preview,
            @ToolParam(description = "高清分辨率 720p/1080p，默认 720p") String resolution,
            @ToolParam(description = "画面比例 16:9/9:16/1:1，默认 16:9") String aspectRatio,
            @ToolParam(description = "可选BGM的ID") Long bgmId,
            @ToolParam(description = "是否烧录字幕，默认 false") Boolean subtitle,
            @ToolParam(description = "字幕语言 zh/en/bilingual，默认 zh") String subtitleLanguage,
            @ToolParam(description = "是否叠加品牌Logo角标，默认 false") Boolean withLogo) {}

    /** BGM 智能推荐 */
    public record RecommendBgm(
            @ToolParam(description = "视频主题/情感描述，例如：温馨治愈的猫咪日常") String theme) {}
}
```

- [x] **Step 4: 实现 VideoAgentToolFactory**

```java
package com.github.ylyan2015.springaidemo.video.agent;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 短视频 Agent 工具工厂（Phase 3 切片1）
 * 用 FunctionToolCallback 将现有服务包装为 LLM 可调用工具：
 * 工具在请求线程内同步执行（SecurityContext 可用）；
 * 任何异常转为 {success:false, message} 结果，让 LLM 能感知失败并调整策略。
 * 切片2 将把 createTools() 注入导演 Agent 的 ChatClient.defaultTools(...)。
 */
@Component
public class VideoAgentToolFactory {

    private static final Logger log = LoggerFactory.getLogger(VideoAgentToolFactory.class);

    private final StoryScriptService storyScriptService;
    private final VideoTemplateService videoTemplateService;
    private final ImagePromptOptimizerService promptOptimizerService;
    private final ImageGenerationService imageGenerationService;
    private final ImageQualityService qualityService;
    private final VideoExportService videoExportService;
    private final BgmService bgmService;

    public VideoAgentToolFactory(StoryScriptService storyScriptService,
                                 VideoTemplateService videoTemplateService,
                                 ImagePromptOptimizerService promptOptimizerService,
                                 ImageGenerationService imageGenerationService,
                                 ImageQualityService qualityService,
                                 VideoExportService videoExportService,
                                 BgmService bgmService) {
        this.storyScriptService = storyScriptService;
        this.videoTemplateService = videoTemplateService;
        this.promptOptimizerService = promptOptimizerService;
        this.imageGenerationService = imageGenerationService;
        this.qualityService = qualityService;
        this.videoExportService = videoExportService;
        this.bgmService = bgmService;
    }

    /** 构建全部 6 个工具（每次调用新建，无状态） */
    public List<ToolCallback> createTools() {
        return List.of(
                FunctionToolCallback.builder("generateStoryboard", guarded(this::generateStoryboard))
                        .description("根据创意想法生成分镜脚本并保存，返回脚本ID与镜头列表；可选模板ID按模板风格生成")
                        .inputType(AgentToolRequests.GenerateStoryboard.class).build(),
                FunctionToolCallback.builder("enhancePrompt", guarded(this::enhancePrompt))
                        .description("将中文场景描述优化为高质量英文图像提示词，可选视觉风格")
                        .inputType(AgentToolRequests.EnhancePrompt.class).build(),
                FunctionToolCallback.builder("generateImage", guarded(this::generateImage))
                        .description("为指定脚本的单个镜头生成图片（含失败重试与可选质量评估），返回镜头状态与图片URL")
                        .inputType(AgentToolRequests.GenerateImage.class).build(),
                FunctionToolCallback.builder("evaluateImage", guarded(this::evaluateImage))
                        .description("评估图片质量：清晰度/相关性/美学三维0-10分与综合分、改进建议；未配多模态API时为规则评估")
                        .inputType(AgentToolRequests.EvaluateImage.class).build(),
                FunctionToolCallback.builder("exportVideo", guarded(this::exportVideo))
                        .description("将脚本合成为视频：preview=true出480p带水印预览，否则720p/1080p高清导出；可选BGM/字幕/Logo。耗时较长")
                        .inputType(AgentToolRequests.ExportVideo.class).build(),
                FunctionToolCallback.builder("recommendBgm", guarded(this::recommendBgm))
                        .description("按视频主题情感智能推荐背景音乐，返回推荐分类与曲目")
                        .inputType(AgentToolRequests.RecommendBgm.class).build());
    }

    /** 统一异常包装：工具失败返回 JSON 而非抛异常，便于 LLM 感知与重试决策 */
    private <I> Function<I, Map<String, Object>> guarded(Function<I, Map<String, Object>> fn) {
        return input -> {
            try {
                return fn.apply(input);
            } catch (Exception e) {
                log.warn("Agent tool execution failed: {}", e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return err;
            }
        };
    }

    private Map<String, Object> generateStoryboard(AgentToolRequests.GenerateStoryboard req) {
        if (req.idea() == null || req.idea().isBlank()) {
            throw new IllegalArgumentException("idea 不能为空");
        }
        StoryScript script = req.templateId() != null
                ? videoTemplateService.applyTemplate(req.templateId(), req.idea())
                : storyScriptService.generateAndSaveScript(req.idea());
        Map<String, Object> out = new LinkedHashMap<>(storyScriptService.scriptToMap(script));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> enhancePrompt(AgentToolRequests.EnhancePrompt req) {
        Map<String, Object> out = new LinkedHashMap<>(
                promptOptimizerService.enhancePrompt(req.sceneDescription(), req.style()));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> generateImage(AgentToolRequests.GenerateImage req) {
        StoryShot shot = imageGenerationService.generateForShot(req.scriptId(), req.shotId(), req.style());
        Map<String, Object> out = new LinkedHashMap<>(storyScriptService.shotToMap(shot));
        out.put("success", true);
        return out;
    }

    private Map<String, Object> evaluateImage(AgentToolRequests.EvaluateImage req) {
        ImageQualityScore score = qualityService.evaluate(req.imageUrl(), req.prompt());
        Map<String, Object> out = new LinkedHashMap<>();
        if (score == null) {
            out.put("success", false);
            out.put("message", "图片下载失败，无法评估");
            return out;
        }
        out.put("success", true);
        out.put("overallScore", score.getOverallScore());
        out.put("clarityScore", score.getClarityScore());
        out.put("relevanceScore", score.getRelevanceScore());
        out.put("aestheticScore", score.getAestheticScore());
        out.put("feedback", score.getFeedback());
        out.put("llmEvaluated", score.isLlmEvaluated());
        return out;
    }

    private Map<String, Object> exportVideo(AgentToolRequests.ExportVideo req) {
        boolean preview = Boolean.TRUE.equals(req.preview());
        String aspect = req.aspectRatio() != null ? req.aspectRatio() : "16:9";
        boolean subtitle = Boolean.TRUE.equals(req.subtitle());
        String lang = req.subtitleLanguage() != null ? req.subtitleLanguage() : "zh";
        Map<String, Object> out;
        if (preview) {
            out = videoExportService.exportPreview(req.scriptId(), aspect, req.bgmId(), subtitle, lang);
        } else {
            String resolution = req.resolution() != null ? req.resolution() : "720p";
            out = videoExportService.exportHd(req.scriptId(), resolution, aspect, req.bgmId(),
                    subtitle, Boolean.TRUE.equals(req.withLogo()), lang);
        }
        Map<String, Object> result = new LinkedHashMap<>(out);
        result.put("success", true);
        return result;
    }

    private Map<String, Object> recommendBgm(AgentToolRequests.RecommendBgm req) {
        Map<String, Object> out = new LinkedHashMap<>(
                bgmService.recommendBgm(req.theme(), bgmService.requireCurrentUserId()));
        out.put("success", true);
        return out;
    }
}
```

- [x] **Step 5: 运行确认绿灯**

Run: `mvn -q test -Dtest=VideoAgentToolFactoryTest`
Expected: Tests run: 13, Failures: 0, Errors: 0

- [x] **Step 6: 提交点（不执行 git commit，用户自行提交；仅标记任务完成）**

---

### Task 2: 工具清单内省端点

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/controller/VideoAgentController.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/controller/VideoAgentControllerTest.java`

**Interfaces:**
- Consumes: `VideoAgentToolFactory.createTools()`（Task 1）
- Produces: `GET /api/video/agent/tools` → `{success:true, data:[{name, description, inputSchema}]}`（只读内省，沿用 /api/video/** permitAll 策略匿名可读；执行时登录校验由服务层保障）

- [x] **Step 1: 写失败测试 VideoAgentControllerTest**

```java
package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.video.agent.VideoAgentToolFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 工具内省端点单元测试（Phase 3 切片1）
 */
class VideoAgentControllerTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataOf(ResponseEntity<Map<String, Object>> resp) {
        assertEquals(200, resp.getStatusCode().value());
        assertTrue((Boolean) resp.getBody().get("success"));
        return (List<Map<String, Object>>) resp.getBody().get("data");
    }

    @Test
    void listToolsReturnsNameDescriptionAndSchema() {
        VideoAgentToolFactory factory = mock(VideoAgentToolFactory.class);
        ToolCallback demo = FunctionToolCallback
                .builder("demoTool", (Map<String, Object> in) -> Map.of("ok", true))
                .description("演示工具")
                .inputType(Map.class)
                .build();
        when(factory.createTools()).thenReturn(List.of(demo));

        VideoAgentController controller = new VideoAgentController(factory);
        List<Map<String, Object>> items = dataOf(controller.listTools());

        assertEquals(1, items.size());
        assertEquals("demoTool", items.get(0).get("name"));
        assertEquals("演示工具", items.get(0).get("description"));
        assertNotNull(items.get(0).get("inputSchema"));
    }

    @Test
    void listToolsWithRealFactoryReturnsSixTools() {
        // 真实工厂 + 全 mock 服务：验证装配后 6 工具可被内省
        VideoAgentToolFactory factory = new VideoAgentToolFactory(
                mock(com.github.ylyan2015.springaidemo.service.StoryScriptService.class),
                mock(com.github.ylyan2015.springaidemo.service.VideoTemplateService.class),
                mock(com.github.ylyan2015.springaidemo.service.ImagePromptOptimizerService.class),
                mock(com.github.ylyan2015.springaidemo.service.ImageGenerationService.class),
                mock(com.github.ylyan2015.springaidemo.video.quality.ImageQualityService.class),
                mock(com.github.ylyan2015.springaidemo.service.VideoExportService.class),
                mock(com.github.ylyan2015.springaidemo.service.BgmService.class));
        VideoAgentController controller = new VideoAgentController(factory);
        assertEquals(6, dataOf(controller.listTools()).size());
    }
}
```

- [x] **Step 2: 运行确认红灯**

Run: `mvn -q test -Dtest=VideoAgentControllerTest`
Expected: 编译失败「找不到符号 VideoAgentController」

- [x] **Step 3: 实现 VideoAgentController**

```java
package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.video.agent.VideoAgentToolFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短视频 Agent 工具内省控制器（Phase 3 切片1）
 * 列出可供导演 Agent（切片2）调用的工具清单与输入 Schema。
 * 工具执行入口在切片2 提供；本端点只读，/api/video/** 沿用现有 permitAll 策略，
 * 工具实际执行时的登录校验由服务层 getCurrentUserId() 保障。
 */
@RestController
@RequestMapping("/api/video/agent")
public class VideoAgentController {

    private final List<ToolCallback> tools;

    public VideoAgentController(VideoAgentToolFactory toolFactory) {
        this.tools = toolFactory.createTools();
    }

    /**
     * 工具清单：名称 / 描述 / 输入 JSON Schema
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> listTools() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolCallback tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getToolDefinition().name());
            item.put("description", tool.getToolDefinition().description());
            item.put("inputSchema", tool.getToolDefinition().inputSchema());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }
}
```

- [x] **Step 4: 运行确认绿灯**

Run: `mvn -q test -Dtest=VideoAgentControllerTest`
Expected: Tests run: 2, Failures: 0, Errors: 0

---

### Task 3: 全量验证 + 启动冒烟

**Files:** 无新改动（验证任务）

- [x] **Step 1: 全量测试**

Run: `mvn -q test`
Expected: 全部通过（61 既有 + 15 新增 = 76，1 个 TTS 用例默认跳过）

- [x] **Step 2: 启动冒烟 + 端点验证**

Run: `mvn spring-boot:run`（后台）→ 等待 Started →
`Invoke-WebRequest http://localhost:8080/api/video/agent/tools`（实测匿名 200，符合 /api/video/** permitAll 现有策略）→ 停止进程

- [x] **Step 3: IDE 诊断无错误（GetProblems 等价检查：编译已通过即可）**

---

### Task 4: 中英文文档同步 + 计划状态

**Files:**
- Modify: `README.md` / `README-en.md`（特性列表 + 项目结构 + API 概览表）
- Modify: `API_TEST.md` / `API_TEST-en.md`（新增 7.11 Agent 工具章节 + 注意事项条目）
- Modify: `VIDEO_STUDIO_PLAN.md`（Phase 3 章节切片1 标记 ✅ + 实现落点 + 最后更新行）

- [x] **Step 1: README 双语** — 工作室特性加「🤖 Agent 工具化（Phase 3 切片1）」条目；项目结构 video/ 下加 `agent/`（AgentToolRequests/VideoAgentToolFactory）与 controller 加 VideoAgentController；API 概览表加 `GET /api/video/agent/tools`
- [x] **Step 2: API_TEST 双语** — 7.11 章节：端点说明 + curl 示例 + 6 工具表（名称/用途/关键入参）+ 响应示例；注明工具执行端点属切片2、工具同步执行于请求线程
- [x] **Step 3: VIDEO_STUDIO_PLAN.md** — 4.2 表后加「切片1 实现落点」说明（FunctionToolCallback 而非旧 FunctionCallback 的 API 演进注记）；最后更新行追加 Phase 3 切片1 完成
- [x] **Step 4: 复核四份文档中英一致性（章节/表格/参数逐条对照）**

---

## Self-Review 结论

1. **Spec 覆盖**：4.2 表中编剧（generateStoryboard/enhancePrompt）、美术（generateImage/evaluateImage）、音频（recommendBgm）、剪辑（exportVideo）核心 Tool 全覆盖；editShot/generateTts/mixAudio 等细粒度 Tool 属切片2 按需扩展（YAGNI）
2. **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码
3. **类型一致性**：createTools()、ToolCallback.getToolDefinition().name()/description()/inputSchema()、服务签名均已对照源码/javap 核实