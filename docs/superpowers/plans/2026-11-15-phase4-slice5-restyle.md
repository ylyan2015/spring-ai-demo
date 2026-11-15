# Phase 4 切片 5：视频转绘新输出模式 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实拍视频上传 → AI 风格转绘（漫剧画风）→ 独立输出模式；一致性由实拍天然锁定（课程结论"视频转绘可能是目前最好的产出方向"）。不触碰主 pipeline。

**Architecture:** 独立 `RestyleTask` 实体 + `VideoRestyleService`（异步提交/轮询/转存，SecurityContext 传播 idiom 同切片 2）；provider 首选 DashScope wan2.7 视频编辑（复用 DASHSCOPE_API_KEY），Kling 4.0 transform 为 2-OPT 不默认实现；**先 0.5 天 spike 验收转绘效果，不达标按决策规则切换/阻塞**。

**Tech Stack:** Java 17 / Spring Boot 3.4 / DashScope 异步任务协议 / 存储转存 / SSE 不需要（3s 轮询 idiom 同编排前端）。

**Spec:** `docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md` §8、§9、§12。前置：切片 1-4 已完成（无强代码依赖，可并行）。

## Global Constraints

- 沿用全部红线；暂存不提交；四份双语文档。
- **规格落地细化（显式决策）**：spec §8.1 原文"产物记 VideoExport(mode=RESTYLE)"；因 `VideoExport.script_id` 为 `nullable=false` 且 H2 ddl-auto:update **无法放宽存量 NOT NULL 约束**，转绘无脚本归属，故改用独立 `RestyleTask` 实体；历史列表端点独立，不混入导出历史。
- 空 Key/未配置=不可用；转绘失败=任务 FAILED+error，不影响其他功能。
- 源视频 URL 回退链同切片 2（http→非 local accessUrl→local base64（协议核对）→FAILED 带 oss/minio 指引）。
- 冒烟 `-Drestyle=true` 默认跳过。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `entity/RestyleTask.java` / `repository/RestyleTaskRepository.java` | 转绘任务 |
| Create | `service/VideoRestyleService.java` | 异步转绘/轮询/转存/归属 |
| Create | `controller/VideoRestyleController.java` | /api/video/restyle |
| Modify | yml / video-studio.html / 四份文档 | 配置/前端/文档 |

---

### Task 1：协议 spike 与决策规则

**Files:** 无代码；产出写入本计划执行记录。

- [ ] **Step 1：** WebSearch `wan2.7 视频编辑 风格切换 API` + `Kling 4.0 transform video restyle API`；WebFetch 百炼视频编辑文档（§5.7 链接族）与 Kling 发布页。
- [ ] **Step 2：** 用现有 DASHSCOPE Key 对 10s 实拍/测试视频发起一次风格切换调用（curl 或临时 main），目视验收：① 动作/机位保持；② 画风动漫化。记录实际模型名/端点/media 字段/响应 URL 位置。
- [ ] **Step 3：决策** 达标→Task 2 按 wan2.7 实现（配置默认模型名以 spike 记录为准）；不达标且有 KLING_API_KEY→改 Kling transform 实现（本计划 Task 2 代码按 wan2.7 写，Kling 分支另列差异：端点 /v1/videos/transform、input.video_url+prompt，轮询同族）；两者皆不可→**切片标记 blocked 汇报用户，不硬做**。

---

### Task 2：RestyleTask + VideoRestyleService

**Files:** Create 实体/仓库/服务；Modify yml；Test `service/VideoRestyleServiceTest.java`

**Interfaces:**
- Produces: `RestyleTask`：id/userId/sourceFileId/sourceVideoUrl/stylePrompt/provider(默认 dashscope-wan)/status(PENDING/RUNNING/SUCCEEDED/FAILED)/resultUrl/resultFileId/error/createdAt/updatedAt；`RestyleTaskRepository.findByUserIdOrderByCreatedAtDesc(Long)`、`findByIdAndUserId(Long,Long)`；`VideoRestyleService.submit(InputStream, String fileName, String contentType, String stylePrompt)`→任务 Map（同步上传源+建任务+异步执行）；`taskStatus(Long)`/`listMine()`；包私有 seam `RestyleTask awaitAndGetForTest(long id)`（等待异步终态并返回实体，无登录要求，供测试/冒烟）；配置 `video.restyle.model`（默认值以 spike 为准，预期 wan2.7 视频编辑模型名）、`video.restyle.timeout-seconds:600`、`video.restyle.poll-interval-ms:5000`。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoRestyleServiceTest {

    private RestyleTaskRepository taskRepo;
    private StorageService storage;
    private HttpClient http;
    private VideoRestyleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        taskRepo = mock(RestyleTaskRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        http = mock(HttpClient.class);
        service = new VideoRestyleService(taskRepo, userRepo, storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com", "sk-test", "wan2.7-videoedit", 600, 10);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(storage.upload(any(), anyString(), anyString())).thenReturn("src1");
        when(storage.getAccessUrl("src1")).thenReturn("http://pub/src1.mp4");
        when(taskRepo.save(any())).thenAnswer(inv -> {
            RestyleTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId(9L);
            return t;
        });
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"output\":{\"task_id\":\"t1\"}}");
        HttpResponse<String> poll = mock(HttpResponse.class);
        when(poll.statusCode()).thenReturn(200);
        when(poll.body()).thenReturn("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"http://v/out.mp4\"}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submit, poll);
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn((HttpResponse) dl);
        when(storage.upload(any(), eq("restyle_out_9.mp4"), anyString())).thenReturn("out1");
        when(storage.getAccessUrl("out1")).thenReturn("/images/out1.mp4");
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void submitRunsAsyncToSucceeded() throws Exception {
        Map<String, Object> out = service.submit(new ByteArrayInputStream(new byte[]{1}),
                "a.mp4", "video/mp4", "anime style, cel shading");
        assertEquals(9L, out.get("id"));
        RestyleTask last = service.awaitAndGetForTest(9L);
        assertEquals("SUCCEEDED", last.getStatus());
        assertEquals("/images/out1.mp4", last.getResultUrl());
    }

    @Test
    void nonVideoContentTypeRejected() {
        assertThrows(RuntimeException.class, () -> service.submit(
                new ByteArrayInputStream(new byte[]{1}), "a.png", "image/png", "anime"));
    }

    @Test
    void listMineRequiresLogin() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.listMine());
    }
}
```

（awaitAndGetForTest 为包私有 seam：等待终态返回实体，不依赖登录态与未 stub 查询。）

- [ ] **Step 2：跑红**。**Step 3：实现** submit：contentType 须 video/*；上传源；建任务 RUNNING；专用单线程池（@PreDestroy shutdown）提交异步：SecurityContext 捕获/传播→resolveSourceUrl 回退链→buildSubmitBody（input.media[{type:"video",url}] + prompt=stylePrompt，异步头）→轮询终态→下载转存 `restyle_out_<id>.mp4`→SUCCEEDED/FAILED+error；awaitAndGetForTest 包私有（Future 表 get 超时兼底）。
- [ ] **Step 4：跑绿 + 全量**（干净启动：无 Key 时 submit 抛"需 DASHSCOPE_API_KEY"）。**Step 5：暂存**。

---

### Task 3：控制器

**Files:** Create `controller/VideoRestyleController.java`；Test 由服务测试覆盖 + 启动冒烟

- [ ] **Step 1：实现** `POST /api/video/restyle`（multipart file + style 参数，style 空→默认 "anime style, cel shading, clean lines"）；`GET /api/video/restyle/{id}`；`GET /api/video/restyle`（listMine）；响应 idiom success/data；taskToMap 全字段。
- [ ] **Step 2：启动冒烟** 匿名 POST→服务层"用户未登录"；登录态（浏览器冒烟 Task 4）200。
- [ ] **Step 3：暂存**。

---

### Task 4：前端转绘面板

**Files:** Modify `templates/video-studio.html`

- [ ] **Step 1：** agentPanel 后增 `<div class="input-section" id="restylePanel">`：file input(accept video/*)+风格 select（日系动漫/美漫/水彩→prompt 模板）+`转绘`按钮+`<div id="restyleList">`；JS：submitRestyle() FormData POST→loadRestyleTasks() 3s 轮询（仅当存在 RUNNING 任务时 setInterval，终态清除，idiom 同编排轮询）→列表行 status 徽章+结果 `<video controls>`+错误信息。
- [ ] **Step 2：JS 语法验证**（命令同切片 1 Task 10）。
- [ ] **Step 3：浏览器冒烟** 无 Key：提交→FAILED 带 Key 指引（验证错误路径）；有 Key 走 Task 5 真实冒烟。
- [ ] **Step 4：暂存**。

---

### Task 5：文档 + 验证

- [ ] **Step 1：四份文档** 增视频转绘特性/端点/配置（video.restyle.*）/FAQ（spike 验收结论、Kling 备选条件、成本按秒计费提示）；VIDEO_STUDIO_PLAN Phase 4 节切片 5 标记（或 blocked 原因）。
- [ ] **Step 2：真实冒烟** `VideoRestyleRealSmokeTest`（完整类）：

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.RestyleTask;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
class VideoRestyleRealSmokeTest {

    @Autowired VideoRestyleService service;
    @Value("${spring.ai.dashscope.api-key:}") String apiKey;

    @Test
    void restyleTenSecondSourceToAnime() throws Exception {
        assumeTrue(Boolean.getBoolean("restyle"), "真实冒烟需 -Drestyle=true");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "需 DASHSCOPE_API_KEY");
        Path src = Files.createTempFile("restyle-src-", ".mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi",
                "-i", "testsrc=duration=10:size=640x360:rate=24", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", src.toString()).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor());
        Map<String, Object> out = service.submit(Files.newInputStream(src), "src.mp4", "video/mp4",
                "anime style, cel shading, clean lines, keep original motion and camera");
        RestyleTask task = service.awaitAndGetForTest(((Number) out.get("id")).longValue());
        assertEquals("SUCCEEDED", task.getStatus(), "转绘失败: " + task.getError());
        assertNotNull(task.getResultUrl());
        System.out.println("[restyle-smoke] 目视验收路径: " + task.getResultUrl());
    }
}
```

Run `mvn test -Dtest=VideoRestyleRealSmokeTest -Drestyle=true`；目视：动作保持+画风动漫化。
- [ ] **Step 3：全量门槛**。**Step 4：暂存全部，汇报，等待提交指令**。

---

## 自检记录

**Spec 覆盖**：§8.1 入口/独立不触主 pipeline→T3/T4；§8.2 spike 决策规则→T1；§8.3 测试/冒烟→T2/T5。VideoExport→RestyleTask 细化已显式记录理由（NOT NULL 存量约束）。
**类型一致性**：RestyleTask 字段全计划统一；回退链/轮询/转存 idiom 与切片 2 一致；awaitAndGetForTest seam 包私有（测试/冒烟共用）。
**占位符扫描**：全部测试完整可编译；模型名默认值以 T1 spike 记录为准（显式决策规则，非 TBD）。

---

## 执行记录（2026-11-15）

**T1 spike 结论（实测）**：
- WebSearch/WebFetch 核对百炼 wan-video-editing-api-reference：模型 wan2.7-videoedit；POST {base}/services/aigc/video-generation/video-synthesis + X-DashScope-Async: enable；input.prompt + input.media[{type:video,url}]（视频有且仅 1 个，mp4/mov、2-10s、[240,4096]px、≤100MB）；parameters.resolution 720P/1080P（默认 1080P，取 720P 成本基线）/prompt_extend/watermark/audio_setting(origin 保原声)；轮询 GET {base}/tasks/{id} → output.task_status + output.video_url（24h）；usage.duration=输入+输出时长计费。
- 关键协议发现：**视频输入仅公网 URL/oss 临时 URL/asset_id，不支持 base64**（仅参考图支持）→回退链 local 分支直接 FAILED 带 oss/minio 指引（计划“local base64（协议核对）”分支被核对否定）。
- 实跑：现有 DASHSCOPE Key 对文档示例 5s 视频发起风格切换，约 4.3 分钟 SUCCEEDED；下载抽帧目视验收：机位/构图保持、画面明显动漫化（赛璐璐渲染）→达标，按 wan2.7-videoedit 实现（Kling 无 Key，2-OPT 不实现）。

**实现偏差**：
- VideoRestyleService 双构造器（@Autowired 9 参自建 HttpClient + 10 参注入 mock，idiom 同 AgentOrchestrationService）；baseUrl 归一兼容带/不带 /api/v1。
- 冒烟测试适配：local 存储断言 FAILED+oss/minio 指引（成功路径已由 spike 验收）；oss/minio 环境断言 SUCCEEDED。
- 冒烟首跑挂起修复：计划原码 ProcessBuilder waitFor 未消费 stdout→管道满死锁（ffmpeg 子进程存活 3min 实证）；改 -loglevel error + Redirect.DISCARD；并补冒烟专用登录用户（服务层登录校验）。
- multipart 上限 10MB→100MB（转绘协议视频≤100MB，原配置会截断上传）。

**CodeReview 修复（0C+1M+0m）**：
- M futures 表只增不减→新提交时 `futures.entrySet().removeIf(isDone)` 清理已终态条目（不用 whenComplete 立即移除，保 awaitAndGetForTest seam 语义）；补 doneFuturesEvictedOnNewSubmit 回归（递增 id mock + submit/poll 交替 stub）。
- 红线核对全过：空 Key 不可用/SSRF（仅 InputStream 入参）/归属校验/SecurityContext 传播+clear/线程池 shutdown/24h 转存/前端 esc/条件轮询。
