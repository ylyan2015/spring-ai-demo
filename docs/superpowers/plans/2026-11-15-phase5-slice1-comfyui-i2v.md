# Phase 5 切片 1：本地 ComfyUI 图生视频（ComfyUiVideoGenerator）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `comfyui` 视频生成器，把本地 ComfyUI（Wan2.2 TI2V-5B）接入现有 i2v 流水线，实现零成本、本地存储闭环的图生视频；超时/轮询/fps 全部可配置。

**Architecture:** 复刻 `DashScopeWanVideoGenerator` idiom 新建 `ComfyUiVideoGenerator implements VideoGenerator`，工厂 List 注入自动收录（零工厂/前端改动）。生成器**模板无关**：加载用户可替换的工作流 JSON 模板，做 `{{FIRST_FRAME}}/{{PROMPT}}/{{FRAMES}}/{{WIDTH}}/{{HEIGHT}}/{{FPS}}/{{SEED}}` 占位符替换后经 ComfyUI HTTP API（`/upload/image` → `/prompt` → `/history/{id}` → `/view`）执行。本地存储首帧经 `resolveMediaUrl` 新增 comfyui 直通分支返回相对 URL，生成器以同源 `http://127.0.0.1:{server.port}` 取字节（复用 VideoComposeService idiom），绕开公网 URL 约束。

**Tech Stack:** Java 17 / Spring Boot 3.4 / java.net.http.HttpClient / ComfyUI HTTP API / Wan2.2 TI2V-5B（Apache 2.0）/ JUnit5 + Mockito。

**Spec:** `docs/local-generation-feasibility.md` §5.2（设计草图；本计划对其首帧取数方式做了落地细化，见 Task 2）。前置：Phase 4 切片 2 的 VideoGenerator 工厂链路已存在。用户并行搭建 ComfyUI 环境（Phase 0），真实联调以 `-Dcomfyui=true` 冒烟 gated。

## Global Constraints

- **默认零变化**：`video.generator: none` 不变；ComfyUI 未启动/工作流未配置时 `isAvailable()=false`，不阻断干净启动。
- **超时/轮询可配置**（用户显式要求）：`video.comfyui.timeout-seconds` 默认 1800、`poll-interval-ms` 默认 3000、`fps` 默认 24。
- **不改 `VideoGenerationRequest`/`GeneratedVideo` record 签名**（跨切片契约）。
- 模板无关：不硬编码节点 ID/class_type；参考模板 `docs/comfyui/wan22_i2v_5b.template.json` 仅为起点，Phase 0 由用户从 ComfyUI 导出校准。
- 帧数对齐 Wan 潜空间约束 4n+1：`frames = (duration*fps/4)*4 + 1`。
- 分辨率映射：720P→1280×720、480P→832×480、1080P→1920×1080（8G 显存不推荐，注释警示）、未知→720P。
- 错误不静默：提交非 200 / history error / 完成无视频输出 / 超时 均抛带指引 RuntimeException。
- 同源取字节仅接受相对路径（`/` 开头）与 http/data URI，禁任意主机拼接（SSRF 防护 idiom 同 agent.tools.self-base-url）。
- 联网/本地渲染冒烟 `-Dcomfyui=true` 默认跳过；暂存不提交。
- 文档同步：README.md/en、API_TEST.md/en、feasibility 文档 §5.2 细化回写。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `video/generator/ComfyUiVideoGenerator.java` | ComfyUI 协议 i2v 实现（模板替换+上传+提交+轮询+转存） |
| Create | `docs/comfyui/wan22_i2v_5b.template.json` | 占位符工作流参考模板（Wan2.2 5B 原生节点） |
| Modify | `config/VideoGeneratorConfig.java` | +@Bean ComfyUiVideoGenerator（@Value 全配置） |
| Modify | `service/VideoGenerationService.java:388-400` | resolveMediaUrl +local×comfyui 直通分支 |
| Modify | `resources/application.yml` | +video.comfyui 配置块 |
| Create | `video/generator/ComfyUiVideoGeneratorTest.java` | 协议/装配/错误/超时/可用性 单测 |
| Create | `service/ResolveMediaUrlLocalComfyUiTest.java` | 直通分支+回归 单测 |
| Create | `video/generator/ComfyUiRegistrationTest.java` | 全上下文注册+默认 none 单测 |
| Create | `service/ComfyUiRealSmokeTest.java` | gated 真实冒烟（-Dcomfyui=true） |

---

### Task 1：ComfyUiVideoGenerator 协议核心（TDD）

**Files:** Create `video/generator/ComfyUiVideoGenerator.java`；Test `video/generator/ComfyUiVideoGeneratorTest.java`

**Interfaces:**
- Consumes: `VideoGenerator`/`VideoGenerationRequest`/`GeneratedVideo`（存量 record，签名不变）；`StorageService.upload/download/getAccessUrl`。
- Produces: type=`comfyui`；ctor `(StorageService, ObjectMapper, HttpClient, String baseUrl, String workflowPath, int serverPort, int fps, int timeoutSeconds, long pollIntervalMs)`；包私有 seam `ObjectNode buildWorkflow(VideoGenerationRequest, String uploadedName)`、`int computeFrames(int durationSeconds)`、`String[] parseVideoOutput(JsonNode historyEntry)`（返回 {filename,subfolder,type}，无视频 null）、`static int[] resolutionSize(String)`。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComfyUiVideoGeneratorTest {

    @TempDir Path temp;
    private final ObjectMapper om = new ObjectMapper();

    private String writeTemplate() throws Exception {
        String tpl = """
            {"4":{"class_type":"LoadImage","inputs":{"image":"{{FIRST_FRAME}}"}},
             "5":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "7":{"class_type":"Wan22ImageToVideoLatent","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}},"length":{{FRAMES}}}},
             "8":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "10":{"class_type":"VHS_VideoCombine","inputs":{"frame_rate":{{FPS}}}}}
            """;
        Path p = temp.resolve("wf.json");
        Files.writeString(p, tpl);
        return p.toString();
    }

    private ComfyUiVideoGenerator newGen(HttpClient http, StorageService storage, String wf,
                                         int timeout, long poll) {
        return new ComfyUiVideoGenerator(storage, om, http, "http://127.0.0.1:8188", wf, 8080, 24, timeout, poll);
    }

    @Test void framesAlignTo4nPlus1() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        assertEquals(121, g.computeFrames(5));   // 5*24=120 → 121
        assertEquals(33, new ComfyUiVideoGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://x", writeTemplate(), 8080, 16, 60, 1).computeFrames(2)); // 2*16=32 → 33
    }

    @Test void resolutionMapping() {
        assertArrayEquals(new int[]{1280, 720}, ComfyUiVideoGenerator.resolutionSize("720P"));
        assertArrayEquals(new int[]{832, 480}, ComfyUiVideoGenerator.resolutionSize("480P"));
        assertArrayEquals(new int[]{1280, 720}, ComfyUiVideoGenerator.resolutionSize("unknown"));
    }

    @Test void workflowTokensSubstitutedAndPromptEscaped() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        String prompt = "girl \"waves\"\nsmiles";
        var wf = g.buildWorkflow(new VideoGenerationRequest(prompt, "http://a/1.png", null, 5, "720P"), "up1.png");
        assertEquals("up1.png", wf.at("/4/inputs/image").asText());
        assertEquals(prompt, wf.at("/5/inputs/text").asText());
        assertEquals(121, wf.at("/7/inputs/length").asInt());
        assertEquals(1280, wf.at("/7/inputs/width").asInt());
        assertEquals(720, wf.at("/7/inputs/height").asInt());
        assertEquals(24, wf.at("/10/inputs/frame_rate").asInt());
        assertTrue(wf.at("/8/inputs/seed").isNumber());
    }

    @Test @SuppressWarnings("unchecked")
    void happyPathUploadPromptHistoryViewTransfer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"up1.png\",\"subfolder\":\"\",\"type\":\"input\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                + "\"outputs\":{\"10\":{\"gifs\":[{\"filename\":\"out.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(first.body()).thenReturn(new byte[]{9});
        HttpResponse<byte[]> view = mock(HttpResponse.class);
        when(view.statusCode()).thenReturn(200); when(view.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(first, view);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf1");
        when(storage.getAccessUrl("cf1")).thenReturn("/images/cf1.mp4");

        GeneratedVideo v = newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"));
        assertEquals("/images/cf1.mp4", v.getVideoUrl());
        assertEquals("cf1", v.getFileId());
        assertEquals("comfyui", v.getGeneratorType());
        assertEquals(5, v.getDurationSeconds());
        verify(storage).upload(any(), startsWith("comfyui_video_"), eq("video/mp4"));
    }

    @Test @SuppressWarnings("unchecked")
    void relativeFirstFrameFetchesFromSelfServer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"up1.png\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},\"outputs\":{\"10\":{\"videos\":[{\"filename\":\"o.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf2");
        when(storage.getAccessUrl("cf2")).thenReturn("/images/cf2.mp4");

        newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "/images/a.png", null, 5, "720P"));
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals("http://127.0.0.1:8080/images/a.png", cap.getAllValues().get(0).uri().toString());
    }

    @Test @SuppressWarnings("unchecked")
    void historyErrorThrows() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}",
                "{\"p1\":{\"status\":{\"status_str\":\"error\",\"completed\":true},\"outputs\":{}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        ComfyUiVideoGenerator g = newGen(http, mock(StorageService.class), writeTemplate(), 60, 1);
        assertThrows(RuntimeException.class, () ->
                g.generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P")));
    }

    @Test @SuppressWarnings("unchecked")
    void timeoutThrows() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        ComfyUiVideoGenerator g = newGen(http, mock(StorageService.class), writeTemplate(), 0, 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                g.generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P")));
        assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
    }

    @Test void unavailableWhenWorkflowMissing() {
        HttpClient http = mock(HttpClient.class);
        ComfyUiVideoGenerator g = new ComfyUiVideoGenerator(mock(StorageService.class), om, http,
                "http://127.0.0.1:8188", "", 8080, 24, 60, 1);
        assertFalse(g.isAvailable());
        verifyNoInteractions(http);
    }

    @Test @SuppressWarnings("unchecked")
    void availabilityProbesSystemStats() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok);
        assertTrue(newGen(http, mock(StorageService.class), writeTemplate(), 60, 1).isAvailable());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("refused"));
        assertFalse(newGen(http, mock(StorageService.class), writeTemplate(), 60, 1).isAvailable());
    }
}
```

- [ ] **Step 2：跑红** `mvn test -Dtest=ComfyUiVideoGeneratorTest`（编译失败=红）。
- [ ] **Step 3：实现** `ComfyUiVideoGenerator`：
  - `generate`：duration clamp 2-15；`fetchFirstFrame(url)`：http(s)→httpClient GET ofByteArray；`/` 开头→`http://127.0.0.1:`+serverPort+url（仅相对路径，禁其他）；`data:`→Base64 解码逗号后；否则抛。非 200 抛带 URL。
  - `uploadFirstFrame(bytes)`：手工 multipart（boundary 固定串，field `image` filename `comfyui_first_{ts}.png` + `overwrite=true`），POST `{base}/upload/image`，解析 `name`；非 200 抛带"请确认 ComfyUI 已启动"指引。
  - `buildWorkflow`：`Files.readString(Path.of(workflowPath))` 每次读取（允许热校准）；占位符替换，字符串值用 `om.writeValueAsString(v)` 去首尾引号做 JSON  escape；`{{FRAMES}}`=computeFrames、`{{WIDTH}}/{{HEIGHT}}`=resolutionSize(req.resolution)、`{{FPS}}`=fps、`{{SEED}}`=ThreadLocalRandom.nextLong(0, Long.MAX_VALUE)；readTree 返回 ObjectNode。
  - 提交 POST `{base}/prompt` body `{"prompt":wf,"client_id":"spring-ai-demo"}` → `prompt_id`；非 200/无 id 抛带 body 截断 500。
  - 轮询 GET `{base}/history/{id}`：entry 缺失→sleep；`parseVideoOutput` 非 null→返回；`status.status_str=="error"`→抛；`status.completed==true` 且无视频→抛"完成但无视频输出"；deadline=now+timeoutSeconds*1000，间隔 pollIntervalMs；超 deadline 抛"ComfyUI 渲染超时（{timeout}s）"。
  - `parseVideoOutput`：遍历 `entry.outputs` 各节点，依次查 `gifs`/`videos`/`images` 数组，首项 filename 以 .mp4/.webm/.mkv 结尾→{filename,subfolder,type}；优先 mp4（先扫一轮 mp4 再扫其他）。
  - 下载 GET `{base}/view?filename={enc}&subfolder={enc}&type={enc}` → bytes；转存 `comfyui_video_{ts}.mp4`；返回 `GeneratedVideo(accessUrl, fileId, "comfyui", duration)`。
  - `isAvailable`：workflowPath 空或文件不存在→false（不触网）；否则 GET `{base}/system_stats`（2s 超时）200→true，异常→false。
  - `getName`="本地 ComfyUI (Wan2.2 i2v)"。
- [ ] **Step 4：跑绿** `mvn test -Dtest=ComfyUiVideoGeneratorTest`。

---

### Task 2：本地存储首帧直通（resolveMediaUrl comfyui 分支）

**Files:** Modify `service/VideoGenerationService.java:388-400`；Test `service/ResolveMediaUrlLocalComfyUiTest.java`

**Interfaces:**
- Consumes: Task 1 生成器（type=comfyui）；存量 `videoGeneratorFactory` 字段（已注入）。
- Produces: `resolveMediaUrl` 回退链扩展为 http 直用 → 非 local getAccessUrl → **local 且 active=comfyui → getAccessUrl（相对路径，生成器同源取字节）** → 抛 oss/minio 指引（存量行为不变）。

- [ ] **Step 1：写失败测试**（ctor 八参顺序镜像 VideoGenerationServiceTest:60-61）

```java
package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.generator.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResolveMediaUrlLocalComfyUiTest {

    private VideoGeneratorFactory videoFactory;
    private VideoGenerator gen;
    private StorageService storage;
    private VideoGenerationService service;

    @BeforeEach void setUp() {
        videoFactory = mock(VideoGeneratorFactory.class);
        gen = mock(VideoGenerator.class);
        when(videoFactory.getActiveGenerator()).thenReturn(gen);
        storage = mock(StorageService.class);
        service = new VideoGenerationService(videoFactory, mock(ImageGeneratorFactory.class),
                mock(StoryScriptRepository.class), mock(StoryShotRepository.class),
                mock(UserRepository.class), new ObjectMapper(), storage, mock(VideoComposeService.class));
        ReflectionTestUtils.setField(service, "storageType", "local");
        when(storage.getAccessUrl("img1")).thenReturn("/images/a.png");
    }

    @Test void localComfyUiPassesRelativeUrl() {
        when(gen.getType()).thenReturn("comfyui");
        assertEquals("/images/a.png", service.resolveMediaUrl(null, "img1"));
    }

    @Test void localDashscopeStillThrows() {
        when(gen.getType()).thenReturn("dashscope-wan");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveMediaUrl(null, "img1"));
        assertTrue(ex.getMessage().contains("oss/minio"));
    }

    @Test void localNullActiveStillThrows() {
        when(videoFactory.getActiveGenerator()).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.resolveMediaUrl(null, "img1"));
    }

    @Test void httpUrlUnchangedUnderLocal() {
        assertEquals("http://x/a.png", service.resolveMediaUrl("http://x/a.png", null));
    }
}
```

- [ ] **Step 2：跑红** `mvn test -Dtest=ResolveMediaUrlLocalComfyUiTest`。
- [ ] **Step 3：实现** resolveMediaUrl local 分支前插入：

```java
VideoGenerator active = videoGeneratorFactory.getActiveGenerator();
if (active != null && "comfyui".equals(active.getType())) {
    return storageService.getAccessUrl(fileId); // 相对路径，ComfyUiVideoGenerator 同源取字节
}
```

同步更新方法 javadoc 回退链描述。
- [ ] **Step 4：跑绿 + 存量回归** `mvn test -Dtest=ResolveMediaUrlLocalComfyUiTest,VideoGenerationServiceTest,CandidateGenerationServiceTest`（local 抛异常存量用例必须仍绿）。

---

### Task 3：Bean 注册 + 配置 + 参考工作流模板

**Files:** Modify `config/VideoGeneratorConfig.java`、`resources/application.yml`；Create `docs/comfyui/wan22_i2v_5b.template.json`；Test `video/generator/ComfyUiRegistrationTest.java`

**Interfaces:**
- Consumes: Task 1 ctor。
- Produces: `@Bean ComfyUiVideoGenerator`；yml `video.comfyui` 块；type=comfyui 进入 `/api/video/videos/generators` 下拉（listGenerators 自动收录，前端零改动）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.SpringAiDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = SpringAiDemoApplication.class)
class ComfyUiRegistrationTest {

    @Autowired VideoGeneratorFactory factory;

    @Test void comfyuiRegisteredAndDefaultNone() {
        assertTrue(factory.getAllGenerators().containsKey("comfyui"));
        assertEquals("none", factory.getActiveType()); // yml 默认不变
        assertFalse(factory.getAllGenerators().get("comfyui").isAvailable()); // ComfyUI 未启动
    }
}
```

- [ ] **Step 2：跑红**。
- [ ] **Step 3：实现**
  - `VideoGeneratorConfig` 增 Bean：`@Value video.comfyui.base-url` 默认 `http://127.0.0.1:8188`、`workflow-path` 默认 `docs/comfyui/wan22_i2v_5b.template.json`、`server.port:8080`、`fps:24`、`timeout-seconds:1800`、`poll-interval-ms:3000`；HttpClient connectTimeout 10s（镜像 dashscope Bean）。
  - yml `video:` 下增：

```yaml
  comfyui:                            # Phase 5 切片 1：本地 ComfyUI i2v（零成本路线，docs/local-generation-feasibility.md）
    base-url: ${COMFYUI_BASE_URL:http://127.0.0.1:8188}
    workflow-path: ${COMFYUI_WORKFLOW:docs/comfyui/wan22_i2v_5b.template.json}  # 占位符模板；Phase 0 从 ComfyUI 导出校准后覆盖
    fps: 24                           # Wan2.2 5B 原生 24fps；帧数自动对齐 4n+1
    timeout-seconds: 1800             # 本地渲染慢（8G 卡 720P×5s 分钟级），按需调
    poll-interval-ms: 3000
```

  - 参考模板（API 格式，节点名据 ComfyUI 官方 Wan2.2 教程；**steps/cfg/sampler/shift 等参数以官方 video_wan2_2_5B_ti2v 模板校准为准**，文档注明）：节点 1 UNETLoader(wan2.2_ti2v_5B_fp16.safetensors)、2 CLIPLoader(umt5_xxl_fp8_e4m3fn_scaled, type=wan)、3 VAELoader(wan2.2_vae)、4 LoadImage({{FIRST_FRAME}})、5 CLIPTextEncode({{PROMPT}})、6 负面 CLIPTextEncode、7 Wan22ImageToVideoLatent(width/height/length/batch_size=1/start_image=[4,0]/vae=[3,0]/positive=[5,0]/negative=[6,0])、8 KSampler(seed={{SEED}}, steps=20, cfg=1.0, uni_pc/simple, denoise=1.0, 接 7 的三输出)、9 VAEDecode、10 VHS_VideoCombine(frame_rate={{FPS}}, format=video/h264-mp4, filename_prefix=comfyui_i2v)。
- [ ] **Step 4：跑绿 + 全量** `mvn test`（干净启动：无 ComfyUI 时 comfyui available=false，none 激活）。

---

### Task 4：gated 冒烟 + 文档同步 + 暂存

**Files:** Create `service/ComfyUiRealSmokeTest.java`；Modify README.md/README-en.md/API_TEST.md/API_TEST-en.md、`docs/local-generation-feasibility.md` §5.2。

- [ ] **Step 1：冒烟测试**（默认跳过；用户 Phase 0 就绪后 `-Dcomfyui=true` 执行）

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComfyUiRealSmokeTest {
    @Autowired ComfyUiVideoGenerator gen;
    @Autowired StorageService storage;
    @LocalServerPort int port;

    @Test void localI2vSmoke() throws Exception {
        assumeTrue(Boolean.getBoolean("comfyui"), "真实冒烟需 -Dcomfyui=true");
        assumeTrue(gen.isAvailable(), "需 ComfyUI 启动且工作流已校准");
        Path png = Files.createTempFile("comfyui-smoke-", ".png");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=duration=1:size=640x360:rate=24", "-frames:v", "1", png.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, p.waitFor());
        String fileId = storage.upload(Files.newInputStream(png), "smoke.png", "image/png");
        GeneratedVideo v = gen.generate(new VideoGenerationRequest(
                "slow gentle motion, subtle camera", storage.getAccessUrl(fileId), null, 2, "480P"));
        assertNotNull(v.getFileId());
        assertEquals("comfyui", v.getGeneratorType());
    }
}
```

（RANDOM_PORT 使相对 URL 同源取字节可达；stdout 必须 DISCARD 防管道死锁。）
- [ ] **Step 2：全量回归** `mvn test` 全绿并记录测试数。
- [ ] **Step 3：文档同步** README×2 特性 bullet + 配置节；API_TEST×2 增 comfyui 配置表与 generators 端点说明；feasibility §5.2 第 1 步回写为"resolveMediaUrl local×comfyui 直通 + 同源取字节"（替代 storage.download(fileId) 草图表述）。
- [ ] **Step 4：暂存不提交** `git add` 本切片全部新增/修改文件。

---

## 执行记录

（2026-11-15 实施，inline 执行；环境无通用实现子代理，仅 CodeReview/ComputerUse）

- **Task 1**：红=编译错误「找不到符号 ComfyUiVideoGenerator」；绿 9/9（帧数 4n+1、分辨率映射、占位符+prompt escape、happy path、相对首帧同源、history error、超时、工作流缺失不可用、system_stats 探测）。
- **Task 2**：红=仅 localComfyUiPassesRelativeUrl 报错（存量 3 用例绿）；绿 4/4 + 存量回归 VideoGenerationServiceTest/CandidateGenerationServiceTest 共 14/14。
- **Task 3**：红=注册断言失败；Bean+yml+参考模板落地后全量 313 绿（299 存量+14 新）。
- **自查修复**：参考模板数值占位符（WIDTH/HEIGHT/FRAMES/SEED/FPS）去引号——带引号替换后为字符串，ComfyUI INT 校验会拒；模板本身允许非严格 JSON（先替换后解析）。
- **Task 4**：ComfyUiRealSmokeTest 默认跳过（1 skipped）；全量 **314 绿 ×2 连续**（0 失败 0 错误 6 跳过）。中间一次全量出现 1 个 transient Error，随后两次全量未复现；假设：测试与并行运行实例共享 `jdbc:h2:file:./data/chatdb;AUTO_SERVER=TRUE` 的瞬时锁竞争（用户并行搭环境），非本切片代码缺陷（Error 非 Failure，排除断言类）。
- **文档**：README.md/en、API_TEST.md/en 增 Phase 5 切片 1 章节；feasibility §5.2 首帧取数回写为 resolveMediaUrl 直通+同源取字节。
- **真实冒烟待用户 Phase 0**：ComfyUI 启动+工作流校准后 `mvn test -Dtest=ComfyUiRealSmokeTest -Dcomfyui=true`；参考模板 steps/cfg/sampler 以官方 video_wan2_2_5B_ti2v 校准。
- **暂存不提交**（项目约定）。

## 审查修复记录（CodeReview 子代理，2026-11-15）

审查结论：0 Critical；红线逐条实证通过；测试诚实性经复跑与匹配器实证确认。发现 1 Major + 4 Minor + 1 Nit，全部修复：

- **Major#1**：RANDOM_PORT 注入 server.port=0 致冒烟同源取字节指向端口 0（启用即坏）→ 冒烟改 DEFINED_PORT(18080)，增非 gated 接线回归 selfPortWiredForRelativeFirstFrame。
- **Minor#2**：注册测试 assertFalse(isAvailable) 依赖外部环境（ComfyUI 启动后全量必挂）→ 删除该断言，可用性由 mock 单测覆盖。
- **Minor#3**：webm/mkv 输出硬编码 .mp4/video/mp4 → contentTypeFor 映射+扩展名随输出（webmOutputKeepsExtensionAndMime）。
- **Minor#4**：首帧文件名毫秒级+overwrite=true 并行同毫秒串帧 → 毫秒+UUID（firstFrameFileNameUnique）。
- **Minor#5**：workflow-path CWD 依赖 → 构造器 log 绝对路径 + README×2 打包部署设绝对路径说明。
- **Nit#6**：占位符二次替换窗口 → PROMPT 最后替换（promptLiteralTokenSurvives）。
- 修复后全量 **318 绿**（0 失败 0 错误 6 跳过）；重新暂存。