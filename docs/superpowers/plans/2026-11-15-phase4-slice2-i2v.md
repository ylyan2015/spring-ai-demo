# Phase 4 切片 2：真图生视频与首尾帧 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入真 i2v（DashScope wan2.7-i2v 首帧/首尾帧，wan2.6-i2v 可选），镜头产物从"静图+Ken Burns"升级为真实动态片段，支持脚本级首尾帧连续性，合成管线混合真实片段与静图回退。

**Architecture:** 复刻 ImageGenerator 工厂模式新建 `VideoGenerator`/`VideoGeneratorFactory`（配置 `video.generator` 默认 none，空 Key 不可用）；`VideoGenerationService` 复用 ImageGenerationService 的异步/SSE/SecurityContext 模式（独立工作池）；i2v READY 时 ffprobe 回写实际时长 + FFmpeg 抽末帧；`VideoComposeService` 按 shot Map 的 `videoUrl` 键混合真实片段（xfade）与静图（Ken Burns 回退）；Agent 美术专家增 `generateShotVideo` 工具（导演 LLM 驱动编排，状态机不变）。

**Tech Stack:** Java 17 / Spring Boot 3.4 / java.net.http.HttpClient（DashScope 异步任务协议）/ FFmpeg≥4.3（xfade/抽帧）/ ffprobe / SSE。

**Spec:** `docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md` §5、§9、§12。前置：切片 1 已完成（角色卡/参考图/wan27）。

## Global Constraints

- 沿用切片 1 全部红线（分层/归属/空 Key 不可用/禁长事务/SecurityContext 全工作线程传播/协调池分离/SSE GET/暂存不提交/四份双语文档）。
- **默认零变化**：`video.generator: none` 时所有存量行为逐帧不变；NoneVideoGenerator.generate 抛带配置指引的 RuntimeException。
- shot Map 装配点：`StoryScriptService.shotToMap` + `ImageGenerationController.shotToMap` 增 video 四键；`VideoExportService.toComposeMap` **本切片必须增 `videoUrl`**（合成消费）；前端同步。
- 时长型滤镜参数一律用实际时长（xfade 陷阱）：i2v READY 回写 `durationSeconds`=ffprobe 实测取整，compose/字幕/BGM 沿用现有 actual-duration 计算。
- 成本基线：720P 默认、单镜 clamp 2-15s（默认 5）、中低运动幅度 prompt 模板、重试 ≤1、编排 N=1。
- 本地存储首帧 URL 问题：回退链 http 绝对 URL → 非 local 存储 getAccessUrl(fileId) → local base64 data URI（协议核对确认）→ 抛带指引异常置 FAILED（不静默）。
- 联网冒烟 `-Di2v=true` 默认跳过。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `video/generator/VideoGenerator.java` / `VideoGenerationRequest.java` / `GeneratedVideo.java` / `VideoGeneratorFactory.java` / `NoneVideoGenerator.java` / `DashScopeWanVideoGenerator.java` | i2v 接口/DTO/工厂/两实现 |
| Create | `video/VideoGenerationProgress.java` | SSE 进度 DTO |
| Create | `service/VideoGenerationService.java` | 单镜/批量/SSE/重试/时长回写/末帧/连续性 |
| Modify | `entity/StoryShot.java` | +videoUrl/videoFileId/videoStatus/lastFrameUrl/lastFrameFileId |
| Modify | `entity/StoryScript.java` | +continuityMode |
| Modify | `service/StoryScriptService.java` / `controller/ImageGenerationController.java` / `service/VideoExportService.java` | Map 装配点 |
| Modify | `video/VideoComposeService.java` | +probeDurationSeconds/extractLastFrame 公共方法；混合片段合成 |
| Modify | `controller/VideoGenerationController.java`（新建） | /api/video/videos |
| Modify | `video/agent/*` | generateShotVideo 工具（8 工具） |
| Modify | `resources/application.yml` / `templates/video-studio.html` | 配置/前端 |

---

### Task 1：VideoGenerator 接口 + 工厂 + none 回退

**Files:** Create 接口/DTO/工厂/None；Modify yml；Test `video/generator/VideoGeneratorFactoryTest.java`

**Interfaces:**
- Produces: `VideoGenerationRequest(String prompt, String firstFrameUrl, String lastFrameUrl, int durationSeconds, String resolution)` record；`GeneratedVideo(String videoUrl, String fileId, String generatorType, int durationSeconds, int width, int height)` record（spec §5.1 含 width/height；**另加 4 参便捷构造器**委托 canonical 补 0,0，本计划测试均用 4 参）+ 单行 getter；`VideoGenerator.generate(req)/getType/getName/isAvailable`；`VideoGeneratorFactory.getActiveGenerator()/switchGenerator/getActiveType/getAllGenerators`（配置 `video.generator:none`，缺失回退 none）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoGeneratorFactoryTest {

    @Test
    void noneAlwaysRegisteredAndFallback() {
        VideoGeneratorFactory factory = new VideoGeneratorFactory(List.of(new NoneVideoGenerator()));
        factory.init(); // configuredType 默认 none（@Value 未注入时为 null → 回退首键 none）
        assertEquals("none", factory.getActiveType());
        assertFalse(factory.getActiveGenerator().isAvailable());
        assertThrows(RuntimeException.class, () -> factory.getActiveGenerator()
                .generate(new VideoGenerationRequest("p", "http://f", null, 5, "720P")));
    }

    @Test
    void switchToUnknownThrows() {
        VideoGeneratorFactory factory = new VideoGeneratorFactory(List.of(new NoneVideoGenerator()));
        factory.init();
        assertThrows(IllegalArgumentException.class, () -> factory.switchGenerator("kling"));
    }
}
```

（工厂构造器内需对 configuredType null 兜底 "none"，与 ImageGeneratorFactory 的 @Value 默认值 idiom 一致：字段 `@Value("${video.generator:none}") private String configuredType;` 测试直接 new 时字段为 null，init 内 `String type = configuredType != null ? configuredType : "none";`。）

- [ ] **Step 2：跑红** `mvn test -Dtest=VideoGeneratorFactoryTest`。
- [ ] **Step 3：实现** 接口/record/DTO 按 Interfaces；工厂复刻 ImageGeneratorFactory（LinkedHashMap + volatile activeType + init/switch/getAll）；NoneVideoGenerator `@Component`，getName="未启用真图生视频"，generate 抛 `RuntimeException("真图生视频未启用：配置 video.generator=dashscope-wan 并注入 DASHSCOPE_API_KEY")`。yml `video:` 下增 `generator: none  # none|dashscope-wan（kling/seedance 预留未实现）`。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 2：DashScopeWanVideoGenerator（协议核对先行）

**Files:** Create `DashScopeWanVideoGenerator.java`；Modify `config/ImageGeneratorConfig.java`（或新建 `config/VideoGeneratorConfig.java`，**决策：新建 VideoGeneratorConfig**，图像配置不混装）；Test `DashScopeWanVideoGeneratorTest.java`

**Interfaces:**
- Consumes: Task 1 接口；StorageService；Spring ObjectMapper。
- Produces: type=`dashscope-wan`；ctor`(StorageService, ObjectMapper, HttpClient, String baseUrl, String apiKey, String model, String resolution, int timeoutSeconds, long pollIntervalMs)`；包私有 `buildSubmitBody(req)`/`parseTaskId(json)`/`parseVideoUrl(json)`/`isTerminal(json)` 供测试。

- [ ] **Step 1：协议核对** WebSearch `wan2.7 图生视频 API 首尾帧 media first_frame`，WebFetch https://help.aliyun.com/zh/model-studio/image-to-video-general-api-reference 与 https://www.alibabacloud.com/help/zh/model-studio/image-to-video-guide ，记录：① 提交端点（预期 `/api/v1/services/aigc/video-generation/video-synthesis` + `X-DashScope-Async: enable`）；② wan2.7 `input.media[{type:first_frame|last_frame,url}]` vs wan2.6 `input.img_url`；③ 轮询 `GET /api/v1/tasks/{id}` 的 `output.task_status` 与视频 URL 位置（`output.video_url` 或 `output.results[].url`）；④ **首帧/参考是否接受 base64 data URI**（决定 Global Constraints 回退链 local 分支）；⑤ parameters.resolution/duration 取值。与代码不符以文档为准调整并记录差异。
- [ ] **Step 2：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashScopeWanVideoGeneratorTest {

    private DashScopeWanVideoGenerator newGen(HttpClient http, StorageService storage, String model) {
        return new DashScopeWanVideoGenerator(storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com", "sk-test", model, "720P", 600, 10);
    }

    @Test
    void wan27BodyUsesFirstAndLastFrameMedia() {
        String body = newGen(mock(HttpClient.class), mock(StorageService.class), "wan2.7-i2v")
                .buildSubmitBody(new VideoGenerationRequest("slow push", "http://a/1.png", "http://a/2.png", 5, "720P"));
        assertTrue(body.contains("first_frame"));
        assertTrue(body.contains("last_frame"));
        assertTrue(body.contains("wan2.7-i2v"));
    }

    @Test
    void wan26BodyUsesImgUrlAndIgnoresLastFrame() {
        String body = newGen(mock(HttpClient.class), mock(StorageService.class), "wan2.6-i2v")
                .buildSubmitBody(new VideoGenerationRequest("p", "http://a/1.png", "http://a/2.png", 5, "720P"));
        assertTrue(body.contains("img_url"));
        assertFalse(body.contains("last_frame"));
    }

    @Test
    void promptAppendsSubtleMotionTemplate() {
        String body = newGen(mock(HttpClient.class), mock(StorageService.class), "wan2.7-i2v")
                .buildSubmitBody(new VideoGenerationRequest("girl turns", "http://a/1.png", null, 5, "720P"));
        assertTrue(body.contains("subtle motion, slow camera, no large deformation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitPollDownloadTransfer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"output\":{\"task_id\":\"t1\",\"task_status\":\"PENDING\"}}");
        HttpResponse<String> poll = mock(HttpResponse.class);
        when(poll.statusCode()).thenReturn(200);
        when(poll.body()).thenReturn("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"http://v/1.mp4\"}}");
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(submit, poll);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn((HttpResponse) dl);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("vf1");
        when(storage.getAccessUrl("vf1")).thenReturn("/images/vf1.mp4");
        GeneratedVideo v = newGen(http, storage, "wan2.7-i2v")
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"));
        assertEquals("/images/vf1.mp4", v.getVideoUrl());
        assertEquals("vf1", v.getFileId());
        assertEquals(5, v.getDurationSeconds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedTaskThrows() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"output\":{\"task_id\":\"t1\"}}");
        HttpResponse<String> poll = mock(HttpResponse.class);
        when(poll.statusCode()).thenReturn(200);
        when(poll.body()).thenReturn("{\"output\":{\"task_status\":\"FAILED\",\"message\":\"boom\"}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submit, poll);
        DashScopeWanVideoGenerator gen = newGen(http, mock(StorageService.class), "wan2.7-i2v");
        assertThrows(RuntimeException.class, () -> gen.generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P")));
    }

    @Test
    void emptyKeyUnavailable() {
        DashScopeWanVideoGenerator g = new DashScopeWanVideoGenerator(mock(StorageService.class), new ObjectMapper(),
                mock(HttpClient.class), "https://x", "", "wan2.7-i2v", "720P", 600, 10);
        assertFalse(g.isAvailable());
    }
}

- [ ] **Step 3：跑红**。
- [ ] **Step 4：实现** generate：duration clamp 2-15；buildSubmitBody：model 分支（wan2.7 media 数组 / wan2.6 img_url，lastFrame 非空且 wan2.6 时 warn 忽略）；prompt = req.prompt + ", subtle motion, slow camera, no large deformation"；parameters{resolution, duration}；提交非 200 抛异常带 body 截断；轮询间隔 pollIntervalMs、总超时 timeoutSeconds，FAILED/未知终态抛异常；parseVideoUrl 先 `output.video_url` 后 `output.results[0].url`；下载非 200 抛；转存 `wan_video_`+ts+`.mp4`；返回 GeneratedVideo(accessUrl, fileId, type, req.durationSeconds)。
- [ ] **Step 5：注册** 新建 `config/VideoGeneratorConfig`：`@Bean DashScopeWanVideoGenerator dashScopeWanVideoGenerator(StorageService, ObjectMapper, @Value spring.ai.dashscope.api-key / video.wan.base-url 默认 https://dashscope.aliyuncs.com / video.wan.model 默认 wan2.7-i2v / video.wan.resolution 默认 720P / video.wan.timeout-seconds 默认 600 / video.wan.poll-interval-ms 默认 5000)`，HttpClient connectTimeout 10s。yml 增 `video.wan` 块（model/resolution/timeout-seconds/poll-interval-ms，注释含 wan2.6-i2v 可选）。
- [ ] **Step 6：跑绿 + 全量**（干净启动：无 Key 时 dashscope-wan isAvailable=false，none 激活）。**Step 7：暂存**。

---

### Task 3：镜头 video 字段 + 装配点

**Files:** Modify StoryShot/StoryScript/StoryScriptService/ImageGenerationController/VideoExportService；Test `service/ShotVideoFieldsMapTest.java`

**Interfaces:**
- Produces: StoryShot `videoUrl/videoFileId/videoStatus(默认 NONE)/lastFrameUrl/lastFrameFileId`；StoryScript `continuityMode(默认 NONE)`；shotToMap 增五键；toComposeMap 增 `videoUrl`；`StoryScriptService.updateContinuityMode(Long,String)`（校验 NONE/PREV_LAST_FRAME/GENERATED_LAST_FRAME，非法抛异常）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShotVideoFieldsMapTest {

    @Test
    void composeMapCarriesVideoUrl() {
        StoryShot shot = new StoryShot();
        shot.setVideoUrl("/images/v.mp4");
        Map<String, Object> m = VideoExportService.toComposeMap(shot);
        assertEquals("/images/v.mp4", m.get("videoUrl"));
    }

    @Test
    void shotToMapCarriesVideoStatus() {
        StoryShot shot = new StoryShot();
        shot.setVideoStatus("READY");
        shot.setLastFrameUrl("/images/last.jpg");
        Map<String, Object> m = new StoryScriptService(null, null, null, null, null).shotToMap(shot);
        assertEquals("READY", m.get("videoStatus"));
        assertEquals("/images/last.jpg", m.get("lastFrameUrl"));
    }
}
```

（StoryScriptService 五参构造器传 null 仅测纯 Map 方法，不触库；若 NPE 风险改 mock 五参。）

- [ ] **Step 2：跑红**。**Step 3：实现** 实体增列（单行 getter/setter idiom）；三处装配点增键；updateContinuityMode + `@PutMapping("/{id}/continuity")` 控制器端点（StoryScriptController，body {mode}）。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 4：VideoGenerationService（异步/SSE/时长回写/末帧）

**Files:** Create `video/VideoGenerationProgress.java`、`service/VideoGenerationService.java`；Modify `video/VideoComposeService.java`（+2 公共方法）；Test `service/VideoGenerationServiceTest.java`

**Interfaces:**
- Consumes: Task 1-3；VideoComposeService 新增 `public int probeDurationSeconds(Path video)`（ffprobe `-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1`，解析 double 取整，失败抛异常）与 `public Path extractLastFrame(Path video, Path workDir)`（`ffmpeg -y -sseof -0.2 -i <video> -frames:v 1 -q:v 2 <workDir>/last.jpg`）。
- Produces: `generateForShot(Long scriptId, Long shotId)`；`generateForScriptBlocking(Long scriptId)`（同步批量，供编排/测试）；`generateForScriptStream(Long scriptId)` Flux SSE（事件 VIDEO_BATCH_START/SHOT_VIDEO_COMPLETE/SHOT_VIDEO_FAILED/VIDEO_BATCH_COMPLETE/ERROR）；`switchGenerator/listGenerators`；配置 `video.workers:2`、`video.retry-attempts:1`。构造器 8 参：`(VideoGeneratorFactory, ImageGeneratorFactory, StoryScriptRepository, StoryShotRepository, UserRepository, ObjectMapper, StorageService, VideoComposeService)`。**另两个 public 方法（切片 3 消费）**：`String resolveMediaUrl(String url, String fileId)`（回退链：http 直用→非 local getAccessUrl→local base64 data URI→抛含 "oss/minio" 指引异常）；`void applyVideoCandidate(StoryShot shot, String videoUrl, String videoFileId, int durationSeconds)`（写 videoUrl/videoFileId→probe 实测回写 durationSeconds→extractLastFrame+upload 回写 lastFrameUrl/FileId→videoStatus READY+save；**原始参数签名，不依赖切片 3 实体**）。

- [ ] **Step 1：写失败测试** `VideoGenerationServiceTest`（完整类）

```java
package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.generator.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoGenerationServiceTest {

    protected VideoGeneratorFactory factory;
    protected ImageGeneratorFactory imageFactory;
    protected StoryScriptRepository scriptRepo;
    protected StoryShotRepository shotRepo;
    protected StorageService storage;
    protected VideoComposeService composeService;
    protected VideoGenerator gen;
    protected StoryScript script;
    protected StoryShot shot;
    protected VideoGenerationService service;

    @BeforeEach
    void setUp() {
        factory = mock(VideoGeneratorFactory.class);
        imageFactory = mock(ImageGeneratorFactory.class);
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        composeService = mock(VideoComposeService.class);
        gen = mock(VideoGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        service = new VideoGenerationService(factory, imageFactory, scriptRepo, shotRepo,
                userRepo, new ObjectMapper(), storage, composeService);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        script = new StoryScript(); script.setId(7L); script.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
        shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageUrl("http://a/1.png");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void readyWritesBackProbedDurationAndLastFrame() {
        when(gen.generate(any())).thenReturn(new GeneratedVideo("http://v/1.mp4", "vf1", "dashscope-wan", 5));
        when(composeService.probeDurationSeconds(any())).thenReturn(4);
        when(composeService.extractLastFrame(any(), any())).thenReturn(Path.of("last.jpg"));
        when(storage.upload(any(), anyString(), anyString())).thenReturn("lf1");
        when(storage.getAccessUrl("lf1")).thenReturn("/images/lf1.jpg");
        service.generateForShot(7L, 3L);
        ArgumentCaptor<StoryShot> cap = ArgumentCaptor.forClass(StoryShot.class);
        verify(shotRepo, atLeastOnce()).save(cap.capture());
        StoryShot saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertEquals("READY", saved.getVideoStatus());
        assertEquals(4, saved.getDurationSeconds());
        assertEquals("/images/lf1.jpg", saved.getLastFrameUrl());
    }

    @Test
    void failedGeneratorMarksFailedAndRethrows() {
        when(gen.generate(any())).thenThrow(new RuntimeException("boom"));
        assertThrows(RuntimeException.class, () -> service.generateForShot(7L, 3L));
        ArgumentCaptor<StoryShot> cap = ArgumentCaptor.forClass(StoryShot.class);
        verify(shotRepo, atLeastOnce()).save(cap.capture());
        assertEquals("FAILED", cap.getAllValues().get(cap.getAllValues().size() - 1).getVideoStatus());
    }

    @Test
    void localImageWithoutPublicUrlFailsWithGuidance() {
        shot.setImageUrl("/images/x.png");
        shot.setImageFileId("fid-x");
        when(storage.getStorageType()).thenReturn("local");
        when(storage.download("fid-x")).thenThrow(new RuntimeException("io"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.generateForShot(7L, 3L));
        assertTrue(ex.getMessage().contains("oss/minio"));
    }
}
```

（服务 local 分支异常消息固定含 "oss/minio" 指引：`"本地存储无法提供公网首帧 URL：请切换 storage 为 oss/minio，或确认协议支持 base64（video.wan.base64-enabled）"`。）

- [ ] **Step 2：跑红**。
- [ ] **Step 3：实现** 构造器 8 参存字段；@PostConstruct 双池（workers fixed + coordinator cached）@PreDestroy shutdown；generateForShot：归属→videoStatus GENERATING→resolveMediaUrl(firstFrame)→gen.generate（retry ≤ video.retry-attempts，间隔 2s）→下载转存已在 generator 完成→probe 回写 duration（clamp≥2）→extractLastFrame+upload 回写 lastFrameUrl/FileId→READY；异常 FAILED+rethrow。resolveMediaUrl 与 applyVideoCandidate 按 Interfaces 实现为 public（applyVideoCandidate 内部复用 generateForShot 的回写段，抽私有 helper `finalizeVideo(shot, url, fileId)` 共享）。批量：mode=PREV_LAST_FRAME 时**顺序**执行（i+1 首帧取 i.lastFrameUrl，未 READY 回退本镜图），否则 workers 并行；SecurityContext 捕获/传播 idiom 同 ImageGenerationService；SSE GET。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 5：首尾帧连续性模式

**Files:** Modify `service/VideoGenerationService.java`；Test `service/VideoGenerationContinuityTest.java`

**Interfaces:**
- Consumes: Task 4；切片 1 `ImageGeneratorFactory`（取 wan27）与 `ReferenceImageResolver` 无关（直接 resolveMediaUrl）。
- Produces: 批量入口读取 `script.getContinuityMode()`；GENERATED_LAST_FRAME：shot i 生成前若 wan27 可用且存在 i+1 → `wan27.generateWithReferences("Extend the final frame of this image toward: " + next.sceneDescription, null, 1280, 720, List.of(resolveMediaUrl(shot i)))` 产物 URL 作 shot i 的 lastFrame；wan27 不可用→warn+按 NONE 处理。

- [ ] **Step 1：写失败测试** `VideoGenerationContinuityTest`（继承 VideoGenerationServiceTest 的 setUp，完整用例）

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.video.generator.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoGenerationContinuityTest extends VideoGenerationServiceTest {

    private StoryShot secondShot() {
        StoryShot s2 = new StoryShot(); s2.setId(4L); s2.setShotOrder(2);
        s2.setImageUrl("http://a/2.png");
        when(shotRepo.findByIdAndScriptId(4L, 7L)).thenReturn(Optional.of(s2));
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot, s2));
        return s2;
    }

    private void stubSuccessPipeline() {
        when(gen.generate(any())).thenReturn(new GeneratedVideo("http://v/1.mp4", "vf1", "dashscope-wan", 4));
        when(composeService.probeDurationSeconds(any())).thenReturn(4);
        when(composeService.extractLastFrame(any(), any())).thenReturn(Path.of("last.jpg"));
        when(storage.upload(any(), anyString(), anyString())).thenReturn("lf1");
        when(storage.getStorageType()).thenReturn("minio");
        when(storage.getAccessUrl("lf1")).thenReturn("http://minio/lf1.jpg");
    }

    @Test
    void prevModeUsesPreviousLastFrameAsNextFirst() {
        script.setContinuityMode("PREV_LAST_FRAME");
        secondShot();
        stubSuccessPipeline();
        service.generateForScriptBlocking(7L);
        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        assertEquals("http://a/1.png", cap.getAllValues().get(0).firstFrameUrl());
        assertEquals("http://minio/lf1.jpg", cap.getAllValues().get(1).firstFrameUrl());
    }

    @Test
    void generatedModeCallsWan27ForLastFrame() {
        script.setContinuityMode("GENERATED_LAST_FRAME");
        secondShot();
        stubSuccessPipeline();
        ImageGenerator wan27 = mock(ImageGenerator.class);
        when(wan27.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("http://last/2.png", "lf2", "wan27", 1280, 720));
        when(imageFactory.getAllGenerators()).thenReturn(Map.of("wan27", wan27));
        service.generateForScriptBlocking(7L);
        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        assertEquals("http://last/2.png", cap.getAllValues().get(0).lastFrameUrl());
        assertNull(cap.getAllValues().get(1).lastFrameUrl());
    }

    @Test
    void generatedModeWithoutWan27FallsBackToNone() {
        script.setContinuityMode("GENERATED_LAST_FRAME");
        secondShot();
        stubSuccessPipeline();
        when(imageFactory.getAllGenerators()).thenReturn(Map.of());
        service.generateForScriptBlocking(7L);
        ArgumentCaptor<VideoGenerationRequest> cap = ArgumentCaptor.forClass(VideoGenerationRequest.class);
        verify(gen, times(2)).generate(cap.capture());
        assertNull(cap.getAllValues().get(0).lastFrameUrl());
    }
}
```

- [ ] **Step 2：跑红**。**Step 3：实现**。**Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 6：合成管线混合真实片段

**Files:** Modify `video/VideoComposeService.java`；Test `video/VideoComposeMixedClipsTest.java`（真 FFmpeg，沿用 VideoComposeFfmpegTest 条件开关 idiom）

**Interfaces:**
- Consumes: shot Map `videoUrl`（Task 3）。
- Produces: compose 内部 `downloadShotsMedia` 返回 `List<Path>`+`List<Boolean> isVideo`（videoUrl 非空→downloadMediaTo 为 shot_i.mp4，否则原图逻辑）；多镜头滤镜视频分支 `[i:v]scale=W:H:force_original_aspect_ratio=increase,crop=W:H,fps=30,setpts=PTS-STARTPTS,format=yuva420p[v i]`（**无 zoompan、无 -loop**）；单视频 `buildSingleVideoVideo`（同滤镜 + `-t duration`）；xfade 链/水印/-t exactDuration 沿用；durations 仍取 Map durationSeconds（i2v 已回写实测）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** idiom 复制 VideoComposeFfmpegTest：FFMPEG/FFPROBE 常量 + assumeTrue + ReflectionTestUtils 注入 */
class VideoComposeMixedClipsTest {

    private static final String FFMPEG = System.getProperty("video.ffmpeg-path",
            "D:/Program Files/ffmpeg/bin/ffmpeg.exe");
    private static final String FFPROBE = System.getProperty("video.ffprobe-path",
            "D:/Program Files/ffmpeg/bin/ffprobe.exe");

    @Test
    void mixedTwoClipsAndOneImageComposes() throws Exception {
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg not installed at " + FFMPEG);
        Path dir = Files.createTempDirectory("mixed-");
        Path clip1 = dir.resolve("c1.mp4");
        Path clip2 = dir.resolve("c2.mp4");
        Path img = dir.resolve("i.png");
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", clip1.toString());
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", clip2.toString());
        run(FFMPEG, "-y", "-f", "lavfi", "-i", "color=c=red:size=320x240:d=1",
                "-frames:v", "1", img.toString());
        StorageService storage = Mockito.mock(StorageService.class);
        when(storage.upload(any(InputStream.class), anyString(), anyString())).thenReturn("video_test.mp4");
        when(storage.getAccessUrl(anyString())).thenReturn("/images/video_test.mp4");
        VideoComposeService service = new VideoComposeService(storage);
        ReflectionTestUtils.setField(service, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(service, "composeTimeoutMinutes", 2);
        double exact = 2 + 3 + 2 - 2 * VideoComposeService.XFADE_DURATION;
        Path out = service.composeMediaForTest(List.of(clip1, clip2, img), List.of(2, 3, 2),
                List.of("", "", "推进"), List.of(true, true, false), dir,
                new VideoComposeService.ComposeOptions(), exact);
        assertTrue(Files.exists(out));
        double dur = probe(out);
        assertTrue(Math.abs(dur - exact) < 0.3, "混合合成时长失控: " + dur);
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private static double probe(Path file) throws Exception {
        Process p = new ProcessBuilder(FFPROBE, "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, p.waitFor(), out);
        return Double.parseDouble(out);
    }
}
```

（`composeMediaForTest` 为包私有测试 seam，委托真实私有合成方法；存量 VideoComposeFfmpegTest 的 newService/shot/probeDuration 为 private 不可跨类复用，故本类自带 helper。）

- [ ] **Step 2：跑红**。**Step 3：实现** 重构 composeVideoWithFfmpeg 签名为媒体+isVideo（原 images 调用点包装 isVideo 全 false）；downloadImages→downloadShotsMedia；单镜头分支按 isVideo 选 builder。
- [ ] **Step 4：跑绿 + 全量**（含存量 VideoComposeFfmpegTest/MotionFilterTest 回归）。**Step 5：暂存**。

---

### Task 7：控制器 + Agent 工具（8 工具）

**Files:** Create `controller/VideoGenerationController.java`；Modify `video/agent/AgentToolRequests.java`/`VideoAgentToolFactory.java`/`SpecialistAgentFactory.java`/`DirectorAgentService.java`；Modify 三存量测试断言；Test `VideoAgentToolFactoryTest` 增用例

**Interfaces:**
- Produces: 端点**镜像 ImageGenerationController idiom**：`POST /api/video/videos/shot/{scriptId}/{shotId}`（单镜同步）；`GET /api/video/videos/script/{scriptId}/stream`（produces TEXT_EVENT_STREAM_VALUE，批量+SSE）；`GET /api/video/videos/generators`；`PUT /api/video/videos/generator?type=`；工具 `generateShotVideo(scriptId, shotId)`；美术子集 +generateShotVideo；导演 prompt 增句"若 video.generator 已配置真 i2v，委托美术专家逐镜 generateShotVideo；未配置则跳过直接静图合成"。

- [ ] **Step 1：写失败测试** `VideoAgentToolFactoryTest` setUp 增 `videoGenerationService = mock(VideoGenerationService.class)` 并传入工厂构造器（7→8 参；存量构造点 VideoAgentControllerTest L62 与本类 L61 同步增 mock），增用例：

```java
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
```
- [ ] **Step 2：跑红**。**Step 3：实现** 控制器（归属在服务层；SSE GET produces TEXT_EVENT_STREAM_VALUE）；AgentToolRequests record GenerateShotVideo；工厂第 8 工具；SpecialistAgentFactory artist Set 增；DirectorAgentService System Prompt 追加句。
- [ ] **Step 4：存量断言** VideoAgentControllerTest seven→eight（方法名+assertEquals 8）；VideoAgentToolFactoryTest registersSeven→Eight（8）；SpecialistAgentFactoryTest artist 子集 `List.of("generateImage","evaluateImage","generateShotVideo")`。
- [ ] **Step 5：跑绿 + 全量**。**Step 6：暂存**。

---

### Task 8：前端 + 文档 + 验证

**Files:** Modify `templates/video-studio.html`、四份文档、`VIDEO_STUDIO_PLAN.md`；Test `video/generator/WanI2vRealSmokeTest.java`

- [ ] **Step 1：前端** shotCardHtml 增 video 徽章（`shot.videoStatus` READY 时 `<video src controls>` 替换 img 预览）+ 按钮 `生成视频` onclick generateShotVideo(shot.id)（POST /api/video/videos/shot/{scriptId}/{shotId}，完成后 loadScriptDetail）；action-bar 增 `videoGeneratorSelect`（GET /api/video/videos/generators、PUT /api/video/videos/generator?type=，none 显示"静图运镜"）+ 批量生成视频按钮（EventSource /api/video/videos/script/{id}/stream，沿用 images SSE 消费 idiom）；脚本头增 continuity 下拉（NONE/prev/generated，PUT /{id}/continuity）。JS 语法验证同切片 1 Task 10 命令。
- [ ] **Step 2：文档** 四份增 i2v 特性/配置（video.generator/video.wan.*）/端点表/FAQ（本地存储首帧指引、成本基线）；VIDEO_STUDIO_PLAN Phase 4 节切片 2 标记。
- [ ] **Step 3：真实冒烟** `WanI2vRealSmokeTest`（@SpringBootTest；assumeTrue Boolean.getBoolean("i2v") + Key；取/造一张首帧图→generate 4s 720P→断言转存 mp4 存在且 probe≥2s；日志路径供目视）。Run `mvn test -Dtest=WanI2vRealSmokeTest -Di2v=true`。
- [ ] **Step 4：全量门槛 + 启动冒烟**（none 默认：存量导出回归逐帧不变；H2 新列自动建）。
- [ ] **Step 5：暂存全部，汇报，等待提交指令**。

---

## 自检记录

**Spec 覆盖**：§5.1 接口/provider/配置→T1/T2；§5.2 服务/字段/基线→T3/T4；§5.3 连续性→T5；§5.4 合成升级+装配点→T3/T6；§5.5 Agent→T7；§5.6 测试→各任务；§5.7 协议核对→T2S1。Kling/Seedance 2-OPT 不实现（工厂 switch 未知 type 抛异常，配置注释预留）。**规格细化**：① 端点命名镜像 ImageGenerationController（/shot/{scriptId}/{shotId}、/script/{scriptId}/stream、/generators、/generator）替代 spec 示意名；② GeneratedVideo 补 width/height（spec §5.1）+4 参便捷构造器；③ applyVideoCandidate 用原始参数签名避免反向依赖切片 3 实体；④ 实际时长经 i2v READY 回写 durationSeconds 实现（compose/字幕/BGM 沿用现有 actual-duration 计算，等价 spec §5.4）。
**类型一致性**：VideoGenerationRequest/GeneratedVideo 字段全计划统一；构造器变更点（VideoAgentToolFactory 8 参）存量两测试已枚举；工具数 7→8 三处断言已枚举；composeMediaForTest seam 包私有。
**占位符扫描**：全部测试均为完整可编译类（含 setUp/继承复用）；无 TBD/TODO；其余步骤含完整代码/命令。