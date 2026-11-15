# Phase 4 切片 3：抽卡多候选与局部重绘 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每镜多候选抽卡（图 N=3/视频 N=2 可配置）+ 质量评分排序 + 人工择优选定回写；wan2.7-image 框选局部重绘（重绘确定细节），重绘产物入候选不直接覆盖。

**Architecture:** 新 `ShotCandidate` 实体承载候选资产；`CandidateGenerationService` 顺序生成 N 候选（成本可控）+ 评分 + 暂取最高分回写主资产（保证流水线流动），人工 select 回写为最终决策；`ImageGenerator` 增 default `editWithMask`（Wan27 覆写，框选编辑协议核对先行）；一键编排保持 N=1 不抽卡（状态机不变）；Agent 美术专家增 `inpaintImage`（推荐/执行重绘，选择仍人工）。

**Tech Stack:** Java 17 / Spring Boot 3.4 / Spring Data JPA / wan2.7-image 框选编辑 / 切片 1 ShotPromptAssembler+ReferenceImageResolver / 切片 2 VideoGenerator。

**Spec:** `docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md` §6、§9、§12。前置：切片 1、2 已完成。

## Global Constraints

- 沿用切片 1/2 全部红线；暂存不提交；四份双语文档同步。
- 候选配置 `video.candidates.image-count:3` / `video.candidates.video-count:2`；**编排路径强制 N=1**（不调用 CandidateGenerationService）。
- 选择=人工闸门：select 端点归属校验；Agent 不自动 select。
- 重绘坐标 0-1 相对值，越界 400；不支持 edit 的 generator → 400。
- 主资产暂取最高分仅为 provisional，前端明确标注"待择优"。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `entity/ShotCandidate.java` / `repository/ShotCandidateRepository.java` | 候选资产 |
| Create | `service/ShotCandidateService.java` | list/select 回写/归属 |
| Create | `service/CandidateGenerationService.java` | N 候选生成+评分+provisional 回写 |
| Modify | `video/generator/ImageGenerator.java` / `Wan27ImageGenerator.java` | editWithMask/supportsEdit |
| Modify | `service/VideoGenerationService.java` | +applyVideoCandidate（probe/末帧/回写） |
| Create | `controller/ShotCandidateController.java` | /api/video/candidates + /api/video/images/inpaint |
| Modify | `video/agent/*` | inpaintImage（9 工具） |
| Modify | yml / video-studio.html / 四份文档 | 配置/前端/文档 |

---

### Task 1：ShotCandidate 实体与选择回写

**Files:** Create 实体/仓库/服务；Test `service/ShotCandidateServiceTest.java`

**Interfaces:**
- Produces: `ShotCandidate`：id/shotId/type(IMAGE|VIDEO)/url/fileId/qualityScore(Double)/durationSeconds(Integer,视频用)/selected(boolean)/createdAt；`ShotCandidateRepository.findByShotIdOrderByIdAsc(Long)`、`findByIdAndShotId(Long,Long)`；`ShotCandidateService` **构造器 7 参** `(ShotCandidateRepository, StoryShotRepository, StoryScriptRepository, UserRepository, VideoGenerationService, ImageGeneratorFactory, StorageService)`（后两参供 Task 3 inpaint）；`listByShot(Long scriptId, Long shotId)`、`select(Long scriptId, Long candidateId)`（清同 type 旧 selected→置新→回写 shot 主资产；VIDEO 回写调 `videoGenerationService.applyVideoCandidate(shot, c.getUrl(), c.getFileId(), c.getDurationSeconds())`——切片 2 声明的原始参数签名）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShotCandidateServiceTest {

    private ShotCandidateRepository candRepo;
    private StoryShotRepository shotRepo;
    private StoryScriptRepository scriptRepo;
    private VideoGenerationService videoGen;
    private ShotCandidateService service;
    private StoryShot shot;

    @BeforeEach
    void setUp() {
        candRepo = mock(ShotCandidateRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        scriptRepo = mock(StoryScriptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        videoGen = mock(VideoGenerationService.class);
        service = new ShotCandidateService(candRepo, shotRepo, scriptRepo, userRepo, videoGen,
                mock(ImageGeneratorFactory.class), mock(StorageService.class));
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        shot = new StoryShot(); shot.setId(3L); shot.setScript(s);
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void selectImageWritesBackMainAssetAndFlags() {
        ShotCandidate old = new ShotCandidate(); old.setId(1L); old.setShotId(3L);
        old.setType("IMAGE"); old.setSelected(true);
        ShotCandidate neu = new ShotCandidate(); neu.setId(2L); neu.setShotId(3L);
        neu.setType("IMAGE"); neu.setUrl("/images/new.png"); neu.setFileId("fnew");
        when(candRepo.findByIdAndShotId(2L, 3L)).thenReturn(Optional.of(neu));
        when(candRepo.findByShotIdOrderByIdAsc(3L)).thenReturn(List.of(old, neu));
        service.select(7L, 2L);
        assertEquals("/images/new.png", shot.getImageUrl());
        assertEquals("fnew", shot.getImageFileId());
        assertFalse(old.isSelected());
        assertTrue(neu.isSelected());
    }

    @Test
    void selectVideoDelegatesApply() {
        ShotCandidate v = new ShotCandidate(); v.setId(5L); v.setShotId(3L);
        v.setType("VIDEO"); v.setUrl("/images/v.mp4"); v.setFileId("fv"); v.setDurationSeconds(4);
        when(candRepo.findByIdAndShotId(5L, 3L)).thenReturn(Optional.of(v));
        when(candRepo.findByShotIdOrderByIdAsc(3L)).thenReturn(List.of(v));
        service.select(7L, 5L);
        verify(videoGen).applyVideoCandidate(shot, "/images/v.mp4", "fv", 4);
        assertTrue(v.isSelected());
    }

    @Test
    void selectOtherUsersCandidateRejected() {
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.select(7L, 2L));
    }
}
```

- [ ] **Step 2：跑红**。**Step 3：实现**（实体单行 getter/setter idiom；select 内 IMAGE 回写 imageUrl/imageFileId+imageStatus READY；VIDEO 委托 applyVideoCandidate；两分支均 save shot+候选）。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 2：CandidateGenerationService（N 候选 + 评分 + provisional）

**Files:** Create `service/CandidateGenerationService.java`；Modify yml；Test `service/CandidateGenerationServiceTest.java`

**Interfaces:**
- Consumes: 切片 1 assembler/resolver/qualityService；切片 2 VideoGeneratorFactory/VideoGenerationService.applyVideoCandidate。
- Produces: `List<ShotCandidate> generateImageCandidates(Long scriptId, Long shotId)`、`generateVideoCandidates(...)`；顺序 N 次；每次失败 warn 跳过（全失败抛异常）；评分启用时 evaluate；provisional=最高分（无评分取首张）回写主资产（IMAGE 直接写；VIDEO 调 applyVideoCandidate）；候选均 save。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.generator.*;
import com.github.ylyan2015.springaidemo.video.quality.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateGenerationServiceTest {

    @Test
    void threeImageCandidatesBestScoreBecomesProvisionalMain() {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        ImageGenerator gen = mock(ImageGenerator.class);
        when(factory.getActiveGenerator()).thenReturn(gen);
        when(gen.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("u1", "f1", "wan27", 1280, 720),
                        new GeneratedImage("u2", "f2", "wan27", 1280, 720),
                        new GeneratedImage("u3", "f3", "wan27", 1280, 720));
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(true);
        ImageQualityScore s1 = new ImageQualityScore(); s1.setOverallScore(5.0);
        ImageQualityScore s2 = new ImageQualityScore(); s2.setOverallScore(9.0);
        ImageQualityScore s3 = new ImageQualityScore(); s3.setOverallScore(7.0);
        when(quality.evaluate(eq("u1"), anyString())).thenReturn(s1);
        when(quality.evaluate(eq("u2"), anyString())).thenReturn(s2);
        when(quality.evaluate(eq("u3"), anyString())).thenReturn(s3);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ShotCandidateRepository candRepo = mock(ShotCandidateRepository.class);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.charactersForShot(any())).thenReturn(List.of());
        when(resolver.resolve(any())).thenReturn(List.of());
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1); shot.setImagePrompt("p");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(candRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CandidateGenerationService service = new CandidateGenerationService(factory,
                mock(VideoGeneratorFactory.class), scriptRepo, shotRepo, userRepo, candRepo,
                new ShotPromptAssembler(), resolver, quality, mock(VideoGenerationService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "imageCount", 3);
        List<ShotCandidate> cands = service.generateImageCandidates(7L, 3L);
        assertEquals(3, cands.size());
        assertEquals("u2", shot.getImageUrl());
        assertEquals(9.0, shot.getQualityScore());
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 2：跑红**。**Step 3：实现**（@Value imageCount/videoCount；顺序循环；prompt=assembler.assemble；refs=resolver.resolve；supportsReferences 分支；quality 异常不阻断；provisional 回写：IMAGE 写 imageUrl/imageFileId/qualityScore+imageStatus READY；VIDEO 分支 gen=videoFactory.getActiveGenerator，req firstFrame=`videoGen.resolveMediaUrl(shot.getImageUrl(), shot.getImageFileId())`（切片 2 已声明 public），成功后调 `videoGen.applyVideoCandidate(shot, url, fileId, duration)`；候选均 save）。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 3：局部重绘（editWithMask + 协议核对 + 端点）

**Files:** Modify `ImageGenerator.java`/`Wan27ImageGenerator.java`；Create 控制器 inpaint 端点（并入 Task 4 控制器文件，本任务先建）；Test `video/generator/Wan27InpaintTest.java`

**Interfaces:**
- Produces: `default GeneratedImage editWithMask(String instruction, String imageUrl, double[] rect, int imgW, int imgH)` 抛 UnsupportedOperationException（**规格细化**：spec §6.2 签名为 (prompt,imageUrl,rect,instruction)，实现增加 imgW/imgH 两参用于相对坐标→像素换算，语义不变）；`default boolean supportsEdit()` false；Wan27 覆写；包私有 `buildEditBody(instruction, imageUrl, rect, imgW, imgH)`；`POST /api/video/images/inpaint` {shotId,x,y,w,h,instruction} → 新候选 IMAGE 返回。

- [ ] **Step 1：协议核对** WebSearch `wan2.7-image 框选编辑 bbox API`，WebFetch 百炼 Wan2.7-Image 文档，记录 bbox 字段名/坐标像素制/指令 text 位置；与代码不符以文档为准调整并记录。
- [ ] **Step 2：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Wan27InpaintTest {

    @Test
    void buildEditBodyConvertsRelativeRectToPixels() {
        Wan27ImageGenerator gen = new Wan27ImageGenerator(mock(StorageService.class), new ObjectMapper(),
                mock(HttpClient.class), "https://x", "sk", "wan2.7-image", 120);
        String body = gen.buildEditBody("replace umbrella with red one", "http://img/1.png",
                new double[]{0.1, 0.2, 0.5, 0.4}, 1000, 800);
        assertTrue(body.contains("100") && body.contains("160") && body.contains("600") && body.contains("480"));
        assertTrue(body.contains("replace umbrella with red one"));
    }

    @Test
    void defaultEditUnsupported() {
        ImageGenerator g = new ImageGenerator() {
            @Override public GeneratedImage generate(String p, String n, int w, int h) { return null; }
            @Override public String getType() { return "t"; }
            @Override public String getName() { return "t"; }
            @Override public boolean isAvailable() { return true; }
        };
        assertFalse(g.supportsEdit());
        assertThrows(UnsupportedOperationException.class,
                () -> g.editWithMask("i", "u", new double[]{0, 0, 1, 1}, 10, 10));
    }
}
```

（像素换算：x1=round(x*W)=100, y1=round(y*H)=160, x2=round((x+w)*W)=600, y2=round((y+h)*H)=480。）

- [ ] **Step 3：跑红**。**Step 4：实现** default 方法 + Wan27 覆写（下载图字节 ImageIO 读宽高→buildEditBody→提交/解析/转存 idiom 同 generate）；**inpaint 业务逻辑单一落点 `ShotCandidateService.inpaint(Long shotId, double[] rect, String instruction)`**（归属经 shot→script.userId 推导，工具 record 无需 scriptId）：归属→坐标校验（0≤x,y；w,h>0；x+w≤1；y+h≤1，否则 IllegalArgumentException→400）→active.supportsEdit 否则 400→下载原图读宽高→editWithMask→存候选（type IMAGE, selected=false, score null）→返回候选实体；控制器端点薄委托（Task 4 建文件）。
- [ ] **Step 5：跑绿 + 全量**。**Step 6：暂存**。

---

### Task 4：候选控制器 + 前端对比择优

**Files:** Create `controller/ShotCandidateController.java`（含 Task 3 inpaint 端点）；Modify `video-studio.html`；Test 控制器薄层由服务测试覆盖 + JS 语法验证

**Interfaces:**
- Produces: `GET /api/video/candidates?shotId=&scriptId=`；`POST /api/video/candidates/{id}/select?scriptId=`；`POST /api/video/candidates/generate {scriptId,shotId,type}`（同步返回候选列表，耗时提示前端 loading）；inpaint 端点（Task 3）。

- [ ] **Step 1：控制器** 三端点 + inpaint，响应 idiom success/data；generate 端点 type=IMAGE|VIDEO 分派 CandidateGenerationService。
- [ ] **Step 2：前端** shotCardHtml 增两个按钮 `抽卡图`/`抽卡视频`（分别 POST generate type=IMAGE/VIDEO；视频按钮在 videoGenerator=none 时禁用）→ GET list → 模态网格（img 或 video 缩略 + 质量分 + "选用" onclick selectCandidate(id) → POST select → loadScriptDetail）；主资产徽章"待择优"当存在未 selected 候选；重绘 UI：shot 图片区增"重绘"按钮 → 弹层 4 数值输入(x/y/w/h 百分比)+指令 textarea → POST inpaint → 刷新候选模态。JS 语法验证命令同切片 1。
- [ ] **Step 3：浏览器冒烟**（chrome-devtools，zhipu_verify 账号）：抽卡 3 图→对比→选用回写→重绘框选→候选出现。无 wan27 Key 时重绘 400 路径验证。
- [ ] **Step 4：暂存**。

---

### Task 5：Agent inpaintImage 工具（9 工具）

**Files:** Modify `AgentToolRequests/VideoAgentToolFactory/SpecialistAgentFactory`；三存量测试断言 8→9；Test 增 `inpaintImageDelegates`

- [ ] **Step 1：写失败测试**（call idiom 同切片 1；setUp 增 `shotCandidateService = mock(ShotCandidateService.class)` 并传入工厂构造器）：

```java
@Test
void inpaintImageDelegates() throws Exception {
    ShotCandidate c = new ShotCandidate();
    c.setId(8L);
    c.setUrl("/images/inpainted.png");
    when(shotCandidateService.inpaint(eq(5L), any(double[].class), eq("fix hands")))
            .thenReturn(c);
    JsonNode out = call("inpaintImage",
            "{\"shotId\":5,\"x\":0.1,\"y\":0.2,\"w\":0.5,\"h\":0.4,\"instruction\":\"fix hands\"}");
    assertTrue(out.get("success").asBoolean());
    assertEquals("/images/inpainted.png", out.get("url").asText());
}
```

工具方法体：rect=new double[]{x,y,w,h}→shotCandidateService.inpaint→candidateToMap+success。
- [ ] **Step 2：跑红**。**Step 3：实现** record InpaintImage(shotId, x, y, w, h, instruction)；工厂第 9 工具（**VideoAgentToolFactory 构造器增 ShotCandidateService，8→9 参；存量构造点 VideoAgentControllerTest L62 与 VideoAgentToolFactoryTest L61 同步增 mock**）；artist Set 增；prompt 增"可用 inpaintImage 局部重绘，重绘产物入候选，选择由用户完成"。
- [ ] **Step 4：存量断言** 8→9 三处（方法名 eight→nine）。
- [ ] **Step 5：跑绿 + 全量**。**Step 6：暂存**。

---

### Task 6：文档 + 验证

- [ ] **Step 1：四份文档** 增抽卡/重绘特性、`video.candidates.*` 配置、候选/inpaint 端点表、FAQ（成本：N 可降 1；编排不抽卡）。
- [ ] **Step 2：全量门槛** `mvn test`；启动冒烟新表 shot_candidates 自动建。
- [ ] **Step 3：真实冒烟** `-Dwan27=true`：抽卡 3 图评分排序目视 + 重绘一次目视（沿用切片 1 冒烟账号/脚本）。
- [ ] **Step 4：暂存全部，汇报，等待提交指令**。

---

## 自检记录

**Spec 覆盖**：§6.1 候选机制/N/评分/回写/前端→T1/T2/T4；§6.2 重绘→T3；§6.3 Agent→T5；§6.4 测试→各任务。编排 N=1 显式决策。**规格细化**：候选端点增 scriptId 参数（spec 仅 shotId）用于服务层归属校验，语义不变。
**类型一致性**：ShotCandidate 字段（含 durationSeconds）全计划统一；ShotCandidateService 7 参构造器与测试一致；applyVideoCandidate/resolveMediaUrl 为切片 2 声明的 public 原始参数签名（不反向依赖本切片实体）；工具数 8→9 三处断言枚举；inpaint 逻辑单一落点 ShotCandidateService.inpaint。
**占位符扫描**：全部测试完整可编译；无 TBD。