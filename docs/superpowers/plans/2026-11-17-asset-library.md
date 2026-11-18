# 素材库与画面感增强实施计划（S1→S5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立跨脚本公共参考素材库（人物+场景），支持文件夹扫描/上传/booru 网搜入库，LLM 自动推荐+人工确认后作为参考图喂入本地 ComfyUI IP-Adapter / 云 wan2.7 双链路，并升级分镜脚本画面感。

**Architecture:** 方案 A 扩展 MediaAsset（加 category 列，null→INSERT 存量兼容）+ StoryShot.referenceAssetIds 绑定列；AssetMatchService 文本匹配推荐；ReferenceImageResolver 扩展合并素材 refs；ImageGenerator 加 default 组合方法；BooruSearchService 双源适配+三道安全闸。

**Tech Stack:** Spring Boot 3.4 / Java 17 / Spring AI ChatClient / JPA(ddl-auto=update) / ComfyUI API(SD1.5+IPAdapter_plus) / booru tag API / Thymeleaf+原生 JS

**Spec:** `docs/superpowers/specs/2026-11-17-asset-library-design.md`（六节已逐节确认，本计划从 spec 论证，执行者两者都读）

## Global Constraints

- 每 Task 末 **git add 暂存，不 commit**（用户约定，覆盖模板默认 commit 步骤）；每切片末 `mvn clean package` 全量回归绿才进下一切片
- TDD 先红后绿：先写测试跑红，再实现跑绿
- PowerShell 用 `;` 分隔命令，不用 `&&`；含 `$_`/`$var` 的逻辑写 .ps1 用 `-File` 执行
- 数值占位符不带引号（`"weight": {{IP_WEIGHT}}`）；字符串占位符经 `ComfyUiClient.jsonEscape`
- Track B 存量行为逐字不变：category=null→INSERT；既有测试不改必须绿
- SSRF 红线：参考图仅接受存储相对路径；booru 下载 host 白名单+禁重定向+入库只认 provider+postId 重查
- video-studio.html 主 script 块保持 `th:inline="none"`；SecurityConfig permitAll 含 `/error`
- 新端点挂 `/api/video/**`（HTTP permitAll + 服务层 getCurrentUserId 登录校验 idiom）
- 真实链路测试全 gated（`-Dipadapter=true` / `-Dbooru=true`），不污染常规回归
- 用户机器 IPAdapter 节点+两模型已就位（2026-11-17 实查）：`D:\Comfy-Desktop\ComfyUI-Installs\ComfyUI\ComfyUI\custom_nodes\ComfyUI_IPAdapter_plus`、`models\ipadapter\ip-adapter-plus_sd15.safetensors`、`models\clip_vision\CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors`——**无需下载，S3 Task 9 仅模板校准**

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| entity/MediaAsset.java | 改 | +category 列 + effectiveCategory() |
| entity/StoryShot.java | 改 | +referenceAssetIds 列 |
| service/MediaAssetService.java | 改 | category 贯穿 upload/ingest/list + scanReferenceDir + parseRefFileName |
| controller/MediaAssetController.java | 改 | upload/list 加 category 参数 + POST /scan-reference |
| service/StoryScriptService.java | 改 | updateShot 绑定校验 + generateAndSaveScript 清单注入 |
| service/AssetMatchService.java | 新 | LLM 批量匹配+关键词降级（嵌套 record DTO） |
| controller/AssetMatchController.java | 新 | POST /api/video/scripts/{sid}/asset-matches |
| video/generator/ReferenceImageResolver.java | 改 | +resolveAssets/resolveAll（构造器 2→4 参：+MediaAssetRepository +StoryScriptRepository，归属校验需脚本 owner） |
| video/generator/ImageGenerator.java | 改 | +default generateWithLoraAndReferences |
| service/ImageGenerationService.java | 改 | generateWithRetry 组合分支 + resolveAll 接线 |
| service/CandidateGenerationService.java | 改 | 候补循环组合分支 + resolveAll 接线 |
| video/generator/ComfyUiImageGenerator.java | 改 | IP-Adapter 模板装配 + supportsReferences + 组合覆写（构造器 10→13 参） |
| config/ImageGeneratorConfig.java | 改 | comfyUiImageGenerator Bean 加 3 参 |
| video/generator/BooruProvider.java | 新 | 接口 + BooruPost record |
| video/generator/SafebooruOrgProvider.java | 新 | safebooru.org 适配 |
| video/generator/DanbooruSafeProvider.java | 新 | safebooru.donmai.us 适配 |
| service/BooruSearchService.java | 新 | 三道闸 + search/preview/ingest |
| controller/BooruAssetController.java | 新 | /api/video/assets/booru 三端点 |
| video/VideoScriptService.java | 改 | system prompt 画面感升级 + generateScript 三参重载 |
| templates/video-studio.html | 改 | 匹配 chip 流 + 素材库面板 |
| application.yml | 改 | video.assets + image.comfyui.ipadapter 配置 |
| docs/comfyui/sd15_ipadapter.template.json | 新(Task 9 用户校准) | IP-Adapter 工作流模板 |
| docs/comfyui/sd15_ipadapter_lora.template.json | 新(Task 9 用户校准) | 组合版模板 |
| tools/e2e-asset-library.js | 新 | 全链路 e2e 驱动 |

测试文件与各 Task 并列。包根：`src/main/java/com/github/ylyan2015/springaidemo/`，测试根：`src/test/java/com/github/ylyan2015/springaidemo/`（下文省略）。

---

# S1 数据模型 + 扫描

### Task 1: MediaAsset.category + 存量兼容语义

**Files:**
- Modify: `entity/MediaAsset.java`
- Modify: `service/MediaAssetService.java`（upload/ingest/list）
- Modify: `controller/MediaAssetController.java`（upload/list 参数）
- Test: `service/MediaAssetCategoryTest.java`

**Interfaces:**
- Produces: `MediaAsset.getCategory()/setCategory(String)`；`MediaAsset.effectiveCategory()`（null/blank→"INSERT"）；`MediaAssetService.upload(file,name,tags,trimStart,trimEnd,category)` 六参 + 旧五参重载委托 null；`MediaAssetService.list(String category)` 过滤重载（null=不过滤）

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaAssetCategoryTest {

    @Test
    void nullCategoryMeansInsert() {
        MediaAsset a = new MediaAsset();
        assertEquals("INSERT", a.effectiveCategory());
        a.setCategory("  ");
        assertEquals("INSERT", a.effectiveCategory());
    }

    @Test
    void explicitCategoryPreserved() {
        MediaAsset a = new MediaAsset();
        a.setCategory("CHARACTER");
        assertEquals("CHARACTER", a.effectiveCategory());
        a.setCategory("SCENE");
        assertEquals("SCENE", a.effectiveCategory());
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=MediaAssetCategoryTest -q`
Expected: 编译失败「找不到符号 effectiveCategory」

- [ ] **Step 3: 实现**

MediaAsset.java 在 sourceNote 字段后加：

```java
    /** CHARACTER / SCENE / INSERT；null=存量 Track B 素材，按 INSERT 语义 */
    @Column(length = 20)
    private String category;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /** 存量兼容：null/空白一律 INSERT（Track B 行为逐字不变） */
    public String effectiveCategory() {
        return category == null || category.isBlank() ? "INSERT" : category;
    }
```

MediaAssetService：`upload` 加六参版（旧五参保留委托 `upload(file,name,tags,trimStart,trimEnd,null)`）；私有 `ingest` 签名末尾加 `String category`，在 `a.setTags(tags);` 后加 `a.setCategory(category);`；`ingestPath` 加四参重载 `(Path p, String name, String tags, String category)`，旧三参委托 null。`list(String category)`：

```java
    public List<MediaAsset> list(String category) {
        List<MediaAsset> all = assetRepository.findByUserIdOrderByIdDesc(getCurrentUserId());
        if (category == null || category.isBlank()) return all;
        return all.stream().filter(a -> category.equals(a.effectiveCategory())).toList();
    }
```

旧 `list()` 委托 `list(null)`。MediaAssetController：upload 加 `@RequestParam(required = false) String category` 传六参；list 加同参数传 `list(category)`。

- [ ] **Step 4: 跑绿 + 存量回归**

Run: `mvn test -Dtest=MediaAssetCategoryTest,MediaAssetService*Test -q`（先 `search_file` 确认存量测试类名，全部必须绿）
Expected: PASS，存量用例零改动

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/entity/MediaAsset.java src/main/java/com/github/ylyan2015/springaidemo/service/MediaAssetService.java src/main/java/com/github/ylyan2015/springaidemo/controller/MediaAssetController.java src/test/java/com/github/ylyan2015/springaidemo/service/MediaAssetCategoryTest.java
```

### Task 2: char_/scene_ 参考素材扫描目录

**Files:**
- Modify: `service/MediaAssetService.java`（+referenceImportDir 配置 + parseRefFileName + scanReferenceDir）
- Modify: `controller/MediaAssetController.java`（+POST /scan-reference）
- Modify: `src/main/resources/application.yml`（video.assets 块）
- Test: `service/MediaAssetReferenceScanTest.java`

**Interfaces:**
- Consumes: Task 1 `ingestPath(p,name,tags,category)`
- Produces: `static String[] parseRefFileName(String)` → `{category,name,tags}` 或 null；`List<MediaAsset> scanReferenceDir()`；配置 `video.assets.reference-import-dir`

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaAssetReferenceScanTest {

    @Test
    void parsesCharConvention() {
        String[] r = MediaAssetService.parseRefFileName("char_林晚_正面全身.png");
        assertArrayEquals(new String[]{"CHARACTER", "林晚", "正面全身"}, r);
    }

    @Test
    void parsesSceneConventionJpg() {
        String[] r = MediaAssetService.parseRefFileName("scene_雨巷_黄昏.jpg");
        assertArrayEquals(new String[]{"SCENE", "雨巷", "黄昏"}, r);
    }

    @Test
    void rejectsWrongPrefixAndExtension() {
        assertNull(MediaAssetService.parseRefFileName("ep01_标签_名称.png"));
        assertNull(MediaAssetService.parseRefFileName("char_林晚_正面.mp4"));
        assertNull(MediaAssetService.parseRefFileName("char_林晚.png"));
        assertNull(MediaAssetService.parseRefFileName(null));
    }

    @Test
    void webpAndJpegAccepted() {
        assertNotNull(MediaAssetService.parseRefFileName("scene_雪山_垭口.webp"));
        assertNotNull(MediaAssetService.parseRefFileName("char_甲_乙.jpeg"));
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=MediaAssetReferenceScanTest -q`
Expected: 编译失败「找不到符号 parseRefFileName」

- [ ] **Step 3: 实现**

MediaAssetService 加（镜像 ASSET_NAME/scanImportDir idiom）：

```java
    @Value("${video.assets.reference-import-dir:}")
    private String referenceImportDir;

    private static final Pattern REF_NAME =
            Pattern.compile("^(char|scene)_([^_]+)_(.+)\\.(png|jpg|jpeg|webp)$");

    static String[] parseRefFileName(String fileName) {
        if (fileName == null) return null;
        Matcher m = REF_NAME.matcher(fileName);
        if (!m.matches()) return null;
        return new String[]{"char".equals(m.group(1)) ? "CHARACTER" : "SCENE", m.group(2), m.group(3)};
    }

    public List<MediaAsset> scanReferenceDir() {
        if (referenceImportDir == null || referenceImportDir.isBlank()) return List.of();
        Path dir = Path.of(referenceImportDir);
        if (!Files.isDirectory(dir)) {
            log.warn("参考素材扫描目录不存在: {}", referenceImportDir);
            return List.of();
        }
        List<MediaAsset> ingested = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String[] parsed = parseRefFileName(f.getFileName().toString());
                if (parsed == null) { log.info("跳过非约定命名参考素材: {}", f.getFileName()); continue; }
                try {
                    ingested.add(ingestPath(f, parsed[1], parsed[2], parsed[0]));
                } catch (Exception e) {
                    log.warn("参考素材入库失败 {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("参考素材扫描失败: " + e.getMessage(), e);
        }
        log.info("参考素材扫描入库 {} 个（目录 {}）", ingested.size(), referenceImportDir);
        return ingested;
    }
```

MediaAssetController 加（镜像 scan() 的 try/catch Map 响应 idiom）：

```java
    @PostMapping("/scan-reference")
    public ResponseEntity<Map<String, Object>> scanReference() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", mediaAssetService.scanReferenceDir());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
```

application.yml 在 `video:` 下（commentary 块后）加：

```yaml
  assets:                              # 素材库增强（2026-11-17 spec）：参考素材扫描目录
    reference-import-dir: ""           # char_名_标签.png / scene_名_标签.jpg 约定；空=禁用
```

- [ ] **Step 4: 跑绿**

Run: `mvn test -Dtest=MediaAssetReferenceScanTest -q`
Expected: 4/4 PASS

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/service/MediaAssetService.java src/main/java/com/github/ylyan2015/springaidemo/controller/MediaAssetController.java src/main/resources/application.yml src/test/java/com/github/ylyan2015/springaidemo/service/MediaAssetReferenceScanTest.java
```

### Task 3: StoryShot.referenceAssetIds + updateShot 绑定校验

**Files:**
- Modify: `entity/StoryShot.java`
- Modify: `service/StoryScriptService.java:413`（updateShot 加 referenceAssetIds 分支）
- Test: `service/StoryScriptServiceAssetBindingTest.java`

**Interfaces:**
- Consumes: `MediaAsset.effectiveCategory()`；`StoryScriptService.parseCharacterIds(String)`（L773 静态，复用解析）；`mediaAssetRepository`（StoryScriptService 已注入，第 6 构造参）
- Produces: `StoryShot.getReferenceAssetIds()/setReferenceAssetIds(String)`（逗号分隔）；updateShot 接受 `referenceAssetIds`（List 或 String，镜像 characterIds 分支形态）

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceAssetBindingTest {

    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StoryShotRepository shotRepo = mock(StoryShotRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptService service;
    StoryShot shot;

    @BeforeEach
    void setUp() {
        service = new StoryScriptService(scriptRepo, shotRepo, userRepo,
                mock(com.github.ylyan2015.springaidemo.video.VideoScriptService.class),
                mock(StoryCharacterRepository.class), assetRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageUrl("/images/old.png"); shot.setImageStatus("READY");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MediaAsset asset(long id, long userId, String kind, String category) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(userId); a.setKind(kind); a.setCategory(category);
        when(assetRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void bindsValidReferenceAssets() {
        asset(11L, 1L, "IMAGE", "CHARACTER");
        asset(12L, 1L, "IMAGE", "SCENE");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11", "12")));
        assertEquals("11,12", shot.getReferenceAssetIds());
    }

    @Test
    void rejectsOtherUsersAsset() {
        asset(11L, 2L, "IMAGE", "CHARACTER");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11")));
        assertNull(shot.getReferenceAssetIds());
    }

    @Test
    void rejectsInsertAndVideo() {
        asset(11L, 1L, "IMAGE", null);      // 存量 INSERT
        asset(12L, 1L, "VIDEO", "SCENE");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11", "12")));
        assertNull(shot.getReferenceAssetIds());
    }

    @Test
    void bindingDoesNotInvalidateImageOrVideo() {
        asset(11L, 1L, "IMAGE", "CHARACTER");
        shot.setVideoUrl("/images/v.mp4"); shot.setVideoStatus("READY");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11")));
        assertEquals("/images/old.png", shot.getImageUrl());
        assertEquals("/images/v.mp4", shot.getVideoUrl());
    }

    @Test
    void emptyListClearsBinding() {
        shot.setReferenceAssetIds("11");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of()));
        assertNull(shot.getReferenceAssetIds());
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=StoryScriptServiceAssetBindingTest -q`
Expected: 编译失败「找不到符号 getReferenceAssetIds」

- [ ] **Step 3: 实现**

StoryShot.java 加（镜像 characterIds 列形态）：

```java
    /** 绑定的参考素材 id（逗号分隔，MediaAsset CHARACTER/SCENE）；null=未绑定 */
    @Column(length = 500)
    private String referenceAssetIds;

    public String getReferenceAssetIds() { return referenceAssetIds; }
    public void setReferenceAssetIds(String referenceAssetIds) { this.referenceAssetIds = referenceAssetIds; }
```

StoryScriptService.updateShot 在 characterIds 分支后加（归属过滤镜像 characterIds idiom；**不调 invalidateVideo**）：

```java
        if (updates.containsKey("referenceAssetIds")) {
            Object v = updates.get("referenceAssetIds");
            List<String> raw = new ArrayList<>();
            if (v instanceof List<?> list) {
                list.forEach(x -> raw.add(String.valueOf(x)));
            } else if (v instanceof String s && !s.isBlank()) {
                raw.add(s);
            }
            Long uid = getCurrentUserId();
            List<String> valid = parseCharacterIds(String.join(",", raw)).stream()
                    .map(id -> mediaAssetRepository.findById(id).orElse(null))
                    .filter(a -> a != null && uid.equals(a.getUserId())
                            && "IMAGE".equals(a.getKind())
                            && ("CHARACTER".equals(a.effectiveCategory()) || "SCENE".equals(a.effectiveCategory())))
                    .map(a -> String.valueOf(a.getId()))
                    .collect(Collectors.toList());
            shot.setReferenceAssetIds(valid.isEmpty() ? null : String.join(",", valid));
        }
```

- [ ] **Step 4: 跑绿 + 存量回归**

Run: `mvn test -Dtest=StoryScriptServiceAssetBindingTest,StoryScriptService*Test -q`
Expected: 新 5 绿 + 存量全绿

- [ ] **Step 5: 暂存（不提交）+ S1 切片门槛**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/entity/StoryShot.java src/main/java/com/github/ylyan2015/springaidemo/service/StoryScriptService.java src/test/java/com/github/ylyan2015/springaidemo/service/StoryScriptServiceAssetBindingTest.java
mvn clean package -q
```

Expected: BUILD SUCCESS 全量绿（S1 完，进 S2）

---

# S2 匹配与确认流

### Task 4: AssetMatchService + asset-matches 端点

**Files:**
- Create: `service/AssetMatchService.java`
- Create: `controller/AssetMatchController.java`
- Test: `service/AssetMatchServiceTest.java`

**Interfaces:**
- Consumes: `MediaAssetRepository.findByUserIdOrderByIdDesc`；`StoryScriptRepository.findByIdAndUserId`；`StoryShotRepository.findByScriptIdOrderByShotOrderAsc`（已核实实际方法名）；`ModelService.getChatClient()`（ChatClient.prompt().system().user().call().content() idiom）；`MediaAsset.effectiveCategory()`
- Produces: `AssetMatchService.match(Long scriptId)` → `MatchResult`；嵌套 record：`MatchResult(String source, List<ShotMatch> matches)`、`ShotMatch(Long shotId, List<Candidate> candidates)`、`Candidate(Long assetId, String name, String category, String reason)`；source ∈ {"LLM","FALLBACK","EMPTY"}；端点 `POST /api/video/scripts/{scriptId}/asset-matches` 返回 `{success,data:MatchResult}`

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetMatchServiceTest {

    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StoryShotRepository shotRepo = mock(StoryShotRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    ModelService modelService = mock(ModelService.class);
    AssetMatchService service;

    @BeforeEach
    void setUp() {
        service = new AssetMatchService(assetRepo, scriptRepo, shotRepo, userRepo,
                modelService, new com.fasterxml.jackson.databind.ObjectMapper());
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setSceneDescription("林晚走在黄昏的雨巷，霓虹倒映在青石板上");
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MediaAsset asset(long id, String name, String category, String tags) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(1L); a.setKind("IMAGE");
        a.setName(name); a.setCategory(category); a.setTags(tags);
        return a;
    }

    @Test
    void emptyLibraryShortCircuitsWithoutLlm() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("EMPTY", r.source());
        verifyNoInteractions(modelService);
        verify(assetRepo, never()).save(any());
    }

    @Test
    void insertAssetsExcludedFromCatalog() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(11L, "久留美截图", null, "ep01")));
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("EMPTY", r.source());
        verifyNoInteractions(modelService);
    }

    @Test
    void llmJsonParsedTop3AndIdsValidated() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直,红大衣"),
                asset(12L, "雨巷", "SCENE", "黄昏,霓虹"),
                asset(13L, "雪山", "SCENE", "垭口")));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("{\"matches\":[{\"shotId\":3,\"assetIds\":[11,12,13,99],\"reasons\":[\"出场角色\",\"环境匹配\",\"无关\",\"伪造id\"]}]}");
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("LLM", r.source());
        assertEquals(3, r.matches().get(0).candidates().size()); // 99 非法剔除 + top3 截断
        assertEquals(11L, r.matches().get(0).candidates().get(0).assetId());
        verify(assetRepo, never()).save(any());
    }

    @Test
    void invalidLlmJsonFallsBackToKeywordOverlap() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直"),
                asset(12L, "雨巷", "SCENE", "黄昏,霓虹"),
                asset(13L, "雪山", "SCENE", "垭口")));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("不是JSON");
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("FALLBACK", r.source());
        List<Long> ids = r.matches().get(0).candidates().stream().map(c -> c.assetId()).toList();
        assertTrue(ids.contains(11L) && ids.contains(12L)); // 描述含"林晚""雨巷""黄昏""霓虹"
        assertFalse(ids.contains(13L));                     // 雪山零重叠
    }

    @Test
    void llmThrowsFallsBack() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(11L, "林晚", "CHARACTER", "")));
        when(modelService.getChatClient()).thenThrow(new RuntimeException("LLM 不可用"));
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("FALLBACK", r.source());
        assertEquals(11L, r.matches().get(0).candidates().get(0).assetId());
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=AssetMatchServiceTest -q`
Expected: 编译失败「找不到符号 AssetMatchService」

- [ ] **Step 3: 实现 AssetMatchService**

```java
package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 素材库自动推荐（spec §2）：LLM 批量判断镜头×素材文本相关性，失败降级关键词重叠。
 * 推荐不落库——确认走 updateShot.referenceAssetIds。LLM 只碰 name/tags 文本，不碰图片。
 */
@Service
public class AssetMatchService {

    private static final Logger log = LoggerFactory.getLogger(AssetMatchService.class);
    private static final int CATALOG_CAP = 100;
    private static final int TOP_N = 3;

    public record Candidate(Long assetId, String name, String category, String reason) {}
    public record ShotMatch(Long shotId, List<Candidate> candidates) {}
    public record MatchResult(String source, List<ShotMatch> matches) {}

    private final MediaAssetRepository assetRepository;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public AssetMatchService(MediaAssetRepository assetRepository, StoryScriptRepository scriptRepository,
                             StoryShotRepository shotRepository, UserRepository userRepository,
                             ModelService modelService, ObjectMapper objectMapper) {
        this.assetRepository = assetRepository;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    public MatchResult match(Long scriptId) {
        Long userId = getCurrentUserId();
        scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        List<MediaAsset> catalog = assetRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(a -> "IMAGE".equals(a.getKind())
                        && ("CHARACTER".equals(a.effectiveCategory()) || "SCENE".equals(a.effectiveCategory())))
                .limit(CATALOG_CAP)
                .toList();
        if (catalog.isEmpty() || shots.isEmpty()) {
            return new MatchResult("EMPTY", List.of());
        }
        try {
            return llmMatch(shots, catalog);
        } catch (Exception e) {
            log.warn("LLM 素材匹配失败，降级关键词重叠: {}", e.getMessage());
            return keywordMatch(shots, catalog);
        }
    }

    private MatchResult llmMatch(List<StoryShot> shots, List<MediaAsset> catalog) throws Exception {
        StringBuilder cat = new StringBuilder();
        for (MediaAsset a : catalog) {
            cat.append("id=").append(a.getId())
               .append(" [").append("CHARACTER".equals(a.effectiveCategory()) ? "人物" : "场景").append("] ")
               .append(a.getName()).append(" tags=").append(a.getTags() == null ? "" : a.getTags()).append('\n');
        }
        StringBuilder sh = new StringBuilder();
        for (StoryShot s : shots) {
            sh.append("镜头id=").append(s.getId()).append(": ").append(s.getSceneDescription()).append('\n');
        }
        String system = """
                你是分镜素材匹配助手。给定素材目录和镜头列表，为每个镜头推荐最相关的素材（最多3个）。
                人物素材优先匹配镜头出场角色，场景素材匹配环境描述。没有相关素材的镜头返回空数组。
                只输出 JSON：{"matches":[{"shotId":镜头id,"assetIds":[素材id...],"reasons":["每个素材一句理由"...]}]}
                assetIds 必须来自素材目录的 id，禁止编造。""";
        String response = modelService.getChatClient().prompt()
                .system(system).user("素材目录：\n" + cat + "\n镜头列表：\n" + sh)
                .call().content();
        String cleaned = response == null ? "" : response.trim();
        if (cleaned.startsWith("```")) {
            int f = cleaned.indexOf('\n');
            cleaned = cleaned.substring(f + 1, cleaned.lastIndexOf("```")).trim();
        }
        JsonNode root = objectMapper.readTree(cleaned);
        Set<Long> validIds = new HashSet<>();
        Map<Long, MediaAsset> byId = new HashMap<>();
        for (MediaAsset a : catalog) { validIds.add(a.getId()); byId.put(a.getId(), a); }
        List<ShotMatch> matches = new ArrayList<>();
        for (StoryShot s : shots) {
            List<Candidate> candidates = new ArrayList<>();
            JsonNode m = findShotNode(root, s.getId());
            if (m != null) {
                JsonNode ids = m.get("assetIds");
                JsonNode reasons = m.get("reasons");
                for (int i = 0; ids != null && i < ids.size() && candidates.size() < TOP_N; i++) {
                    long id = ids.get(i).asLong();
                    if (!validIds.contains(id)) continue;
                    MediaAsset a = byId.get(id);
                    String reason = reasons != null && reasons.has(i) ? reasons.get(i).asText() : "";
                    candidates.add(new Candidate(id, a.getName(), a.effectiveCategory(), reason));
                }
            }
            matches.add(new ShotMatch(s.getId(), candidates));
        }
        return new MatchResult("LLM", matches);
    }

    private JsonNode findShotNode(JsonNode root, Long shotId) {
        JsonNode arr = root.get("matches");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("LLM 输出缺少 matches 数组");
        for (JsonNode n : arr) {
            if (n.has("shotId") && n.get("shotId").asLong() == shotId) return n;
        }
        return null;
    }

    private MatchResult keywordMatch(List<StoryShot> shots, List<MediaAsset> catalog) {
        List<ShotMatch> matches = new ArrayList<>();
        for (StoryShot s : shots) {
            String text = (s.getSceneDescription() == null ? "" : s.getSceneDescription())
                    + " " + (s.getImagePrompt() == null ? "" : s.getImagePrompt());
            List<Candidate> candidates = new ArrayList<>();
            for (MediaAsset a : catalog) {
                int score = 0;
                if (a.getName() != null && !a.getName().isBlank() && text.contains(a.getName())) score += 2;
                if (a.getTags() != null) {
                    for (String t : a.getTags().split("[,，]")) {
                        if (!t.isBlank() && text.contains(t.trim())) score++;
                    }
                }
                if (score > 0) candidates.add(new Candidate(a.getId(), a.getName(), a.effectiveCategory(),
                        "关键词重叠 " + score + " 分（LLM 不可用降级）"));
            }
            candidates.sort((x, y) -> Integer.compare(
                    Integer.parseInt(y.reason().replaceAll("\\D+", "")),
                    Integer.parseInt(x.reason().replaceAll("\\D+", ""))));
            matches.add(new ShotMatch(s.getId(), candidates.stream().limit(TOP_N).toList()));
        }
        return new MatchResult("FALLBACK", matches);
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在")).getId();
    }
}
```

方法名已核实：StoryShotRepository 实际为 `findByScriptIdOrderByShotOrderAsc`（L24），不存在 findByScriptIdOrderByIdAsc。

- [ ] **Step 4: 实现 AssetMatchController**

```java
package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.AssetMatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/video/scripts")
public class AssetMatchController {

    private final AssetMatchService assetMatchService;

    public AssetMatchController(AssetMatchService assetMatchService) {
        this.assetMatchService = assetMatchService;
    }

    @PostMapping("/{scriptId}/asset-matches")
    public ResponseEntity<Map<String, Object>> match(@PathVariable Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", assetMatchService.match(scriptId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
```

- [ ] **Step 5: 跑绿 + 安全配置核验**

Run: `mvn test -Dtest=AssetMatchServiceTest -q` → 5/5 PASS
核验 SecurityConfig L30-43 requestMatchers 列表覆盖 `/api/video/**`（读文件确认；若为逐条枚举则补 `/api/video/scripts/*/asset-matches`，并确认 `/error` 在列）。

- [ ] **Step 6: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/service/AssetMatchService.java src/main/java/com/github/ylyan2015/springaidemo/controller/AssetMatchController.java src/test/java/com/github/ylyan2015/springaidemo/service/AssetMatchServiceTest.java
```

### Task 5: 前端匹配 chip 流（video-studio）

**Files:**
- Modify: `src/main/resources/templates/video-studio.html`（shotCardHtml L804-850 区域 + 新函数块）

**Interfaces:**
- Consumes: Task 4 端点 `POST /api/video/scripts/{sid}/asset-matches`；Task 3 `PUT .../shots/{shotId}` body `{referenceAssetIds:[...]}`；页面现有 `post/put/esc/toast/renderScript/loadScriptDetail/findShot/currentScript` 工具函数
- Produces: 全局 `assetMatchCache = {}`（shotId→candidates）；`matchAssetsForScript()`、`matchChipsHtml(shot)`、`adoptMatch(shotId,assetId)`、`removeMatch(shotId,assetId)`、`adoptAllMatches(shotId)`

- [ ] **Step 1: 加状态与函数**（在「镜头编辑（模块 A）」注释块前插入）

```javascript
// ==================== 素材匹配推荐（spec §2：推荐不落库，确认走 updateShot） ====================
let assetMatchCache = {};   // shotId -> [{assetId,name,category,reason}]
let assetMatchSource = '';  // LLM / FALLBACK / EMPTY

async function matchAssetsForScript() {
    if (!currentScript) return;
    toast('正在匹配素材推荐...', '');
    const data = await post('/api/video/scripts/' + currentScript.id + '/asset-matches', {});
    if (!data.success) { toast(data.message || '匹配失败', 'error'); return; }
    assetMatchCache = {};
    assetMatchSource = data.data.source;
    (data.data.matches || []).forEach(m => { assetMatchCache[m.shotId] = m.candidates || []; });
    if (assetMatchSource === 'EMPTY') { toast('素材库为空（人物/场景类），请先在素材库面板入库', ''); return; }
    toast(assetMatchSource === 'FALLBACK' ? 'LLM 不可用，已用关键词匹配' : '素材推荐已生成，请逐镜确认', 'success');
    renderScript();
}

function matchChipsHtml(shot) {
    const bound = shot.referenceAssetIds || [];
    const recs = assetMatchCache[shot.id] || [];
    if (!bound.length && !recs.length) return '';
    let h = '<div class="shot-camera" style="margin-top:4px">参考素材: ';
    bound.forEach(id => {
        h += '<span class="badge" style="background:#2a3e2a;color:#2ecc71">已绑定 #' + id + ' <a href="javascript:void(0)" onclick="removeMatch(' + shot.id + ',' + id + ')" style="color:#f74a4a">×</a></span> ';
    });
    recs.filter(c => !bound.includes(c.assetId)).forEach(c => {
        h += '<span class="badge" style="background:#1a2a3e;color:#6ab0f7" title="' + esc(c.reason || '') + '">' + (c.category === 'CHARACTER' ? '人物' : '场景') + '·' + esc(c.name) + ' <a href="javascript:void(0)" onclick="adoptMatch(' + shot.id + ',' + c.assetId + ')" style="color:#2ecc71">采用</a></span> ';
    });
    if (recs.some(c => !bound.includes(c.assetId))) {
        h += '<a href="javascript:void(0)" onclick="adoptAllMatches(' + shot.id + ')" style="font-size:11px;color:#ffd75e">全部采用</a>';
    }
    return h + '</div>';
}

async function adoptMatch(shotId, assetId) {
    const shot = findShot(shotId);
    const ids = (shot.referenceAssetIds || []).concat([assetId]);
    await saveAssetBinding(shotId, ids);
}

async function removeMatch(shotId, assetId) {
    const shot = findShot(shotId);
    await saveAssetBinding(shotId, (shot.referenceAssetIds || []).filter(x => x !== assetId));
}

async function adoptAllMatches(shotId) {
    const recs = assetMatchCache[shotId] || [];
    const shot = findShot(shotId);
    const ids = (shot.referenceAssetIds || []).concat(recs.map(c => c.assetId));
    await saveAssetBinding(shotId, [...new Set(ids)]);
}

async function saveAssetBinding(shotId, ids) {
    const data = await put('/api/video/scripts/' + currentScript.id + '/shots/' + shotId, { referenceAssetIds: ids });
    if (!data.success) { toast(data.message || '绑定失败', 'error'); return; }
    await loadScriptDetail(currentScript.id);
    document.getElementById('scriptSelect').value = currentScript.id;
}
```

- [ ] **Step 2: 接线 shotCardHtml 与工具栏 + DTO 装配点**

shotCardHtml 非编辑分支 `h += '<div class="shot-camera">镜头运动: ...'` 行之后插入：`h += matchChipsHtml(shot);`
shot-actions 里「编辑」按钮前插入：`h += '<button class="btn btn-sm btn-ghost" onclick="matchAssetsForScript()" title="LLM 推荐参考素材（确认后才生效）">匹配素材</button>';`

**DTO 装配点（已核实）**：控制器 shotToMap 委托 `StoryScriptService.shotToMap`（L673），在 L687 `map.put("characterIds", parseCharacterIds(shot.getCharacterIds()));` 行后加：

```java
        map.put("referenceAssetIds", parseCharacterIds(shot.getReferenceAssetIds()));
```

（镜像 characterIds 输出形态 List<Long>；**Map 传参链路新增字段须枚举全部组装点**——cameraMovement 断链教训；ImageGenerationController.shotToMap 为独立手写 Map，不含 characterIds 也不含本字段，生图响应不需绑定信息，不改。）

**写侧组装点（Task 3 审查 Important#1 收口）**：同控制器 `mapToStoryShot`（L518-548，含 characterIds 搬运）服务于 `PUT /{id}/shots` 批量替换（batchUpdateShots 删旧插新语义，不补则回传 shot map 静默丢绑定），在其 characterIds 块后镜像加：

```java
        if (data.get("referenceAssetIds") instanceof List<?> list) {
            shot.setReferenceAssetIds(list.isEmpty() ? null : list.stream()
                    .map(String::valueOf).collect(Collectors.joining(",")));
        }
```

**候选缩略图（spec §2.2）**：matchAssetsForScript 成功后追加加载素材清单缓存 `const al = await get('/api/video/assets'); assetThumbCache = {}; (al.data || []).forEach(a => { assetThumbCache[a.id] = a.url; });`（全局 `let assetThumbCache = {};` 与 assetMatchCache 并列声明）；matchChipsHtml 推荐 chip 内在类别角标前插缩略图：`'<img src="' + esc(assetThumbCache[c.assetId] || '') + '" style="width:20px;height:20px;object-fit:cover;border-radius:3px;vertical-align:middle"> '`。

- [ ] **Step 3: 浏览器验证**

启动应用（`--auth.captcha.debug-text=true`），chrome-devtools MCP：注册→建脚本→素材库有 CHARACTER/SCENE 素材（可先 SQL/接口造数）→点「匹配素材」→chip 出现→「采用」→刷新后「已绑定」常驻。主 script 块 `th:inline="none"` 保持。

- [ ] **Step 4: 暂存（不提交）+ S2 切片门槛**

```powershell
git add src/main/resources/templates/video-studio.html
mvn clean package -q
```

Expected: BUILD SUCCESS（S2 完，进 S3）

---

# S3 双链路消费

### Task 6: ReferenceImageResolver 扩展（resolveAssets / resolveAll）

**Files:**
- Modify: `video/generator/ReferenceImageResolver.java`（构造器 2→4 参 + 两新方法；resolve() 存量逐字不动）
- Modify: `src/test/java/.../video/generator/ReferenceImageResolverTest.java:18`（构造器机械补 2 个 mock 参）
- Modify: `src/test/java/.../service/ImageGenerationServiceEndCardTest.java:65`（同上机械补参）
- Test: `video/generator/ReferenceImageResolverAssetTest.java`

**Interfaces:**
- Consumes: Task 1 `MediaAsset.effectiveCategory()`；Task 3 `StoryShot.getReferenceAssetIds()`；`StoryScriptService.parseCharacterIds(String)`（静态复用）
- Produces: 构造器 `ReferenceImageResolver(StoryCharacterRepository, StorageService, MediaAssetRepository, StoryScriptRepository)`；`List<String> resolveAssets(StoryShot)`；`List<String> resolveAll(StoryShot, List<StoryCharacter>)`（角色 refs 在前 + 素材 refs 在后，素材内 CHARACTER 优先 SCENE）

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceImageResolverAssetTest {

    StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StorageService storage = mock(StorageService.class);
    ReferenceImageResolver resolver;
    StoryShot shot;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceImageResolver(charRepo, storage, assetRepo, scriptRepo);
        ReflectionTestUtils.setField(resolver, "storageType", "local");
        shot = new StoryShot(); shot.setId(3L);
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        shot.setScript(s); // scriptId 为派生只读属性（getScriptId 来自 script 关系），只能经 setScript 赋值
        when(scriptRepo.findById(7L)).thenReturn(Optional.of(s));
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of());
    }

    private MediaAsset asset(long id, long userId, String kind, String category, String url, String fileId) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(userId); a.setKind(kind); a.setCategory(category);
        a.setUrl(url); a.setFileId(fileId);
        when(assetRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void localAssetResolvesToBase64DataUri() throws Exception {
        shot.setReferenceAssetIds("11");
        asset(11L, 1L, "IMAGE", "CHARACTER", "/images/a.png", "f11");
        when(storage.download("f11")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        List<String> refs = resolver.resolveAssets(shot);
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("data:image/png;base64,"));
    }

    @Test
    void httpUrlUsedDirectlyAndNonLocalUsesAccessUrl() {
        shot.setReferenceAssetIds("11,12");
        asset(11L, 1L, "IMAGE", "SCENE", "https://cdn.example.com/a.png", "f11");
        asset(12L, 1L, "IMAGE", "SCENE", "/images/b.png", "f12");
        assertEquals("https://cdn.example.com/a.png", resolver.resolveAssets(shot).get(0));
        ReflectionTestUtils.setField(resolver, "storageType", "oss");
        when(storage.getAccessUrl("f12")).thenReturn("https://oss.example.com/f12");
        assertEquals("https://oss.example.com/f12", resolver.resolveAssets(shot).get(1));
    }

    @Test
    void ownershipKindCategoryGuards() {
        shot.setReferenceAssetIds("11,12,13,14");
        asset(11L, 2L, "IMAGE", "CHARACTER", null, "f11");   // 他人素材
        asset(12L, 1L, "VIDEO", "SCENE", null, "f12");       // VIDEO
        asset(13L, 1L, "IMAGE", null, null, "f13");          // 存量 INSERT
        asset(14L, 1L, "IMAGE", "CHARACTER", null, null);    // 无 fileId
        assertTrue(resolver.resolveAssets(shot).isEmpty());
    }

    @Test
    void downloadFailureSkipsWithWarn() throws Exception {
        shot.setReferenceAssetIds("11");
        asset(11L, 1L, "IMAGE", "SCENE", "/images/a.png", "f11");
        when(storage.download("f11")).thenThrow(new RuntimeException("file gone"));
        assertTrue(resolver.resolveAssets(shot).isEmpty());
    }

    @Test
    void resolveAllMergesCharactersFirstAndCharacterAssetsBeforeScene() throws Exception {
        shot.setCharacterIds("5");
        shot.setReferenceAssetIds("12,11");   // 12=SCENE 在前，11=CHARACTER 应排前
        StoryCharacter c = new StoryCharacter(); c.setId(5L); c.setName("林晚");
        c.setReferenceImageFileId("fc");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(c));
        asset(11L, 1L, "IMAGE", "CHARACTER", null, "f11");
        asset(12L, 1L, "IMAGE", "SCENE", null, "f12");
        when(storage.download("fc")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(storage.download("f11")).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(storage.download("f12")).thenReturn(new ByteArrayInputStream(new byte[]{3}));
        List<String> refs = resolver.resolveAll(shot, resolver.charactersForShot(shot));
        assertEquals(3, refs.size()); // 角色定妆图 + 2 素材，全部 base64
        assertTrue(refs.stream().allMatch(r -> r.startsWith("data:image/png;base64,")));
    }

    @Test
    void noBindingReturnsEmptyWithoutRepoCalls() {
        assertTrue(resolver.resolveAssets(shot).isEmpty());
        verifyNoInteractions(assetRepo);
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=ReferenceImageResolverAssetTest -q`
Expected: 编译失败「找不到符号 resolveAssets / 构造器不匹配」

- [ ] **Step 3: 实现**

ReferenceImageResolver：import 补 `MediaAsset/StoryScript/MediaAssetRepository/StoryScriptRepository/java.util.Comparator`；字段+构造器加 `mediaAssetRepository`、`scriptRepository`（@Component 自动装配，无需改 Config）。**resolve() 与 charactersForShot() 逐字不动**。追加：

```java
    /**
     * 素材库参考图解析（spec §3.5）：按 shot.referenceAssetIds 加载 MediaAsset，
     * 归属+kind+category 三校验后走与角色参考图完全相同的回退链；CHARACTER 优先 SCENE。
     */
    public List<String> resolveAssets(StoryShot shot) {
        List<Long> ids = StoryScriptService.parseCharacterIds(shot.getReferenceAssetIds());
        if (ids.isEmpty()) {
            return List.of();
        }
        Long ownerId = scriptRepository.findById(shot.getScriptId())
                .map(StoryScript::getUserId).orElse(null);
        if (ownerId == null) {
            return List.of();
        }
        List<MediaAsset> assets = new ArrayList<>();
        for (Long id : ids) {
            MediaAsset a = mediaAssetRepository.findById(id).orElse(null);
            if (a == null || !ownerId.equals(a.getUserId()) || !"IMAGE".equals(a.getKind())
                    || !("CHARACTER".equals(a.effectiveCategory()) || "SCENE".equals(a.effectiveCategory()))) {
                continue;
            }
            assets.add(a);
        }
        assets.sort(Comparator.comparing(a -> "CHARACTER".equals(a.effectiveCategory()) ? 0 : 1));
        List<String> refs = new ArrayList<>();
        for (MediaAsset a : assets) {
            String url = a.getUrl();
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                refs.add(url);
                continue;
            }
            String fileId = a.getFileId();
            if (fileId == null || fileId.isBlank()) {
                continue;
            }
            if (!"local".equals(storageType)) {
                refs.add(storageService.getAccessUrl(fileId));
                continue;
            }
            try (InputStream in = storageService.download(fileId)) {
                refs.add("data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes()));
            } catch (Exception e) {
                log.warn("Reference asset {} unavailable, skip: {}", a.getName(), e.getMessage());
            }
        }
        return refs;
    }

    /** 最终 refs = 角色定妆图（在前）+ 素材库参考图（spec §3.5） */
    public List<String> resolveAll(StoryShot shot, List<StoryCharacter> characters) {
        List<String> refs = new ArrayList<>(resolve(characters));
        refs.addAll(resolveAssets(shot));
        return refs;
    }
```

存量测试机械补参（行为断言零改动）：
- ReferenceImageResolverTest L18：`new ReferenceImageResolver(mock(StoryCharacterRepository.class), storage, mock(MediaAssetRepository.class), mock(StoryScriptRepository.class))`
- ImageGenerationServiceEndCardTest L65：同款补 `mock(MediaAssetRepository.class), mock(StoryScriptRepository.class)` 两参

- [ ] **Step 4: 跑绿 + 存量回归**

Run: `mvn test -Dtest=ReferenceImageResolver*Test,ImageGenerationServiceEndCardTest -q`
Expected: 新 6 绿 + 存量全绿

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/video/generator/ReferenceImageResolver.java src/test/java/com/github/ylyan2015/springaidemo/video/generator/ReferenceImageResolverAssetTest.java src/test/java/com/github/ylyan2015/springaidemo/video/generator/ReferenceImageResolverTest.java src/test/java/com/github/ylyan2015/springaidemo/service/ImageGenerationServiceEndCardTest.java
```

### Task 7: 组合 default 方法 + 路由 seam + 三处 resolveAll 接线

**Files:**
- Modify: `video/generator/ImageGenerator.java`（+default generateWithLoraAndReferences）
- Modify: `service/ImageGenerationService.java`（+static routeGeneration seam；generateWithRetry 委托；L157/L398 refs 改 resolveAll）
- Modify: `service/CandidateGenerationService.java`（L102 refs 改 resolveAll；L111-116 三元改调 seam）
- Test: `service/ImageGenerationRoutingTest.java`

**Interfaces:**
- Consumes: Task 6 `resolveAll(StoryShot, List<StoryCharacter>)`；现有 `LoraSpec` record（loraName()/controlNetRefUrl()）
- Produces: `ImageGenerator.generateWithLoraAndReferences(String prompt, String negativePrompt, int width, int height, String loraName, String controlNetRefUrl, java.util.List<String> referenceImageUrls)` default 降级 generateWithReferences；`static GeneratedImage ImageGenerationService.routeGeneration(ImageGenerator gen, String prompt, String negativePrompt, int width, int height, List<String> refs, LoraSpec lora)`（包私有，CandidateGenerationService 同包复用）

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.generator.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageGenerationRoutingTest {

    private final GeneratedImage img = new GeneratedImage("/images/x.png", "fx", "mock", 1280, 704);

    @Test
    void loraPlusRefsRoutesToCombined() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsLora()).thenReturn(true);
        when(g.supportsReferences()).thenReturn(true);
        when(g.generateWithLoraAndReferences(anyString(), any(), anyInt(), anyInt(), anyString(), any(), anyList()))
                .thenReturn(img);
        LoraSpec lora = new LoraSpec("a.safetensors", null, null);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), lora));
        verify(g).generateWithLoraAndReferences("p", "n", 1280, 720, "a.safetensors", null, List.of("r1"));
        verify(g, never()).generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void refsOnlyRoutesToReferences() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsReferences()).thenReturn(true);
        when(g.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), null));
        verify(g).generateWithReferences("p", "n", 1280, 720, List.of("r1"));
    }

    @Test
    void loraOnlyKeepsExistingPath() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsLora()).thenReturn(true);
        when(g.generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any())).thenReturn(img);
        LoraSpec lora = new LoraSpec("a.safetensors", null, "/images/cn.png");
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of(), lora));
        verify(g).generateWithLora("p", "n", 1280, 720, "a.safetensors", "/images/cn.png");
    }

    @Test
    void plainWhenNothing() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.generate(anyString(), any(), anyInt(), anyInt())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, null, null));
        verify(g).generate("p", "n", 1280, 720);
    }

    @Test
    void refsSilentlyIgnoredWhenUnsupported() {
        ImageGenerator g = mock(ImageGenerator.class); // supportsReferences 默认 false
        when(g.generate(anyString(), any(), anyInt(), anyInt())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), null));
        verify(g).generate("p", "n", 1280, 720);
        verify(g, never()).generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList());
    }

    @Test
    void defaultCombinedDegradesToReferencesThenGenerate() {
        // 匿名最小实现：只覆写 generate，验证两级 default 降级链逐字成立
        ImageGenerator minimal = new ImageGenerator() {
            @Override public GeneratedImage generate(String p, String n, int w, int h) { return img; }
            @Override public String getType() { return "minimal"; }
            @Override public String getName() { return "minimal"; }
            @Override public boolean isAvailable() { return true; }
        };
        assertSame(img, minimal.generateWithLoraAndReferences("p", "n", 1, 1, "l", null, List.of("r")));
        assertSame(img, minimal.generateWithReferences("p", "n", 1, 1, List.of("r")));
    }
}
```

已核实：`LoraSpec` 为三参 record `(String loraName, String triggerWord, String controlNetRefUrl)`（LoraSpec.java L8），测试构造已按三参写。

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=ImageGenerationRoutingTest -q`
Expected: 编译失败「找不到符号 routeGeneration / generateWithLoraAndReferences」

- [ ] **Step 3: 实现**

ImageGenerator.java 在 generateWithLora 之后加：

```java
    /**
     * 素材库切片 S3：LoRA + 参考图组合模式。
     * 默认降级 generateWithReferences（uploadFinal 同款 default idiom）→ 其余 generator 零改动。
     */
    default GeneratedImage generateWithLoraAndReferences(String prompt, String negativePrompt,
                                                         int width, int height, String loraName,
                                                         String controlNetRefUrl,
                                                         java.util.List<String> referenceImageUrls) {
        return generateWithReferences(prompt, negativePrompt, width, height, referenceImageUrls);
    }
```

ImageGenerationService 加包私有静态 seam（四象限唯一裁决点，DRY 供两服务复用）：

```java
    /** 路由矩阵（spec §3.1）：LoRA+refs→组合 / 仅 refs→refs / 仅 LoRA→lora / 无→plain；不支持 refs 静默忽略 */
    static GeneratedImage routeGeneration(ImageGenerator generator, String prompt, String negativePrompt,
                                          int width, int height, List<String> refs, LoraSpec lora) {
        boolean hasRefs = refs != null && !refs.isEmpty() && generator.supportsReferences();
        if (lora != null && generator.supportsLora() && hasRefs) {
            return generator.generateWithLoraAndReferences(prompt, negativePrompt, width, height,
                    lora.loraName(), lora.controlNetRefUrl(), refs);
        }
        if (lora != null && generator.supportsLora()) {
            return generator.generateWithLora(prompt, negativePrompt, width, height,
                    lora.loraName(), lora.controlNetRefUrl());
        }
        if (hasRefs) {
            return generator.generateWithReferences(prompt, negativePrompt, width, height, refs);
        }
        return generator.generate(prompt, negativePrompt, width, height);
    }
```

generateWithRetry 循环体内原三段 if（L549-560）整体替换为一行：

```java
                return routeGeneration(generator, prompt, negativePrompt,
                        parseWidth(defaultSize), parseHeight(defaultSize), refs, lora);
```

CandidateGenerationService L111-116 三元表达式替换为：

```java
                GeneratedImage img = ImageGenerationService.routeGeneration(gen, prompt,
                        style.getNegativePrompt(), 1280, 720, refs, lora);
```

**resolveAll 接线三处**（Map 传参链路教训：组装点必须枚举全）：
- ImageGenerationService L157：`List<String> refs = referenceResolver.resolveAll(shot, characters);`
- ImageGenerationService L398：同上
- CandidateGenerationService L102：同上

- [ ] **Step 4: 跑绿 + 存量回归**

Run: `mvn test -Dtest=ImageGenerationRoutingTest,ImageGenerationService*Test,CandidateGenerationService*Test -q`
Expected: 新 6 绿 + 存量全绿（无素材绑定时 resolveAll 与 resolve 结果一致，存量行为不变）

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/video/generator/ImageGenerator.java src/main/java/com/github/ylyan2015/springaidemo/service/ImageGenerationService.java src/main/java/com/github/ylyan2015/springaidemo/service/CandidateGenerationService.java src/test/java/com/github/ylyan2015/springaidemo/service/ImageGenerationRoutingTest.java
```

### Task 8: ComfyUI IP-Adapter 模板装配 + 配置接线

**Files:**
- Modify: `video/generator/ComfyUiImageGenerator.java`（构造器 10→13 参 + supportsReferences + 两覆写 + buildWorkflow/doGenerate 扩展）
- Modify: `config/ImageGeneratorConfig.java:91-104`（Bean 加 3 个 @Value 参）
- Modify: `src/main/resources/application.yml`（image.comfyui 块 +3 行）
- Modify: `src/test/java/.../video/generator/ComfyUiImageGeneratorTest.java`（newGen/直构造机械补参 + capabilityFlags 断言更新）
- Test: 同上文件新增用例

**Interfaces:**
- Consumes: Task 7 组合 default 方法签名；`ComfyUiClient.uploadImage(byte[], String)/fetchBytes(String)/jsonEscape(ObjectMapper, String)`（现有）
- Produces: 构造器末三参 `(String ipadapterPath, String ipadapterLoraPath, double ipWeight)`；`buildWorkflow(prompt, negative, w, h, loraName, uploadedCnName)` 六参保留委托新八参 `(…, java.util.List<String> uploadedRefNames, double ipWeight)`；占位符 {{REF_IMAGE}}/{{REF_IMAGE2}}（字符串 jsonEscape）/{{IP_WEIGHT}}（**数值不带引号**）；配置 `image.comfyui.workflow-ipadapter/workflow-ipadapter-lora/ipadapter-weight`

- [ ] **Step 1: 写失败测试**（ComfyUiImageGeneratorTest 追加；writeTemplates 扩展为 5 模板，newGen 改 13 参）

writeTemplates 追加两个模板并返回 5 段 `plain|lora|canny|ipadapter|ipadapterLora`：

```java
        Path ipa = temp.resolve("ipadapter.json");
        Files.writeString(ipa, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "10":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE}}"}},
             "11":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE2}}"}},
             "12":{"class_type":"ImageBatch","inputs":{}},
             "13":{"class_type":"IPAdapter","inputs":{"weight":{{IP_WEIGHT}}}}}
            """);
        Path ipaLora = temp.resolve("ipadapter_lora.json");
        Files.writeString(ipaLora, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "9":{"class_type":"LoraLoader","inputs":{"lora_name":"{{LORA_NAME}}"}},
             "10":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE}}"}},
             "11":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE2}}"}},
             "13":{"class_type":"IPAdapter","inputs":{"weight":{{IP_WEIGHT}}}}}
            """);
```

newGen 改为（存量用例经此 helper 自动获得新参）：

```java
    private ComfyUiImageGenerator newGen(HttpClient http, StorageService storage, String paths,
                                         int timeout, long poll) {
        String[] p = paths.split("\\|");
        return new ComfyUiImageGenerator(storage, om, http, "http://127.0.0.1:8188",
                p[0], p[1], p[2], 8080, timeout, poll, p[3], p[4], 0.7);
    }
```

新增用例：

```java
    @Test void ipadapterWorkflowSubstitutesRefsAndNumericWeight() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", "n", 1280, 720, null, null, java.util.List.of("r1.png", "r2.png"), 0.7);
        assertTrue(types(wf).contains("IPAdapter"));
        assertTrue(types(wf).contains("ImageBatch"));
        assertEquals("r1.png", wf.at("/10/inputs/image").asText());
        assertEquals("r2.png", wf.at("/11/inputs/image").asText());
        assertTrue(wf.at("/13/inputs/weight").isNumber());   // 数值占位符不带引号回归用例
        assertEquals(0.7, wf.at("/13/inputs/weight").asDouble());
        assertEquals(704, wf.at("/4/inputs/height").asInt());
    }

    @Test void combinedWorkflowHasLoraAndIpAdapter() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", "n", 1280, 720, "saki.safetensors", null, java.util.List.of("r1.png", "r2.png"), 0.7);
        assertTrue(types(wf).contains("LoraLoader"));
        assertTrue(types(wf).contains("IPAdapter"));
        assertEquals("saki.safetensors", wf.at("/9/inputs/lora_name").asText());
    }

    @Test void supportsReferencesFollowsTemplatePresence() throws Exception {
        String[] p = writeTemplates().split("\\|");
        ComfyUiImageGenerator with = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, p[3], p[4], 0.7);
        assertTrue(with.supportsReferences());
        ComfyUiImageGenerator without = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, "", "", 0.7);
        assertFalse(without.supportsReferences());  // 模板未就位自动降级，不阻塞启动
    }

    @Test @SuppressWarnings("unchecked")
    void singleRefUploadsTwiceToFillBothSlots() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"ref.png\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},"
                + "\"outputs\":{\"7\":{\"images\":[{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci3");
        when(storage.getAccessUrl("ci3")).thenReturn("/images/ci3.png");

        newGen(http, storage, writeTemplates(), 60, 1)
                .generateWithReferences("p", null, 1280, 720, java.util.List.of("data:image/png;base64,AQ=="));
        // 实测修正（2026-11-18 Task 8 执行时）：计划原写法 verify(http, times(4)).send(any(), any(BodyHandler.class))
        // 实际得 5 次——any(HttpResponse.BodyHandler.class) 是按类型匹配，把 download 的 ofByteArray 请求也算进去了
        // （后声明的 eq(ofByteArray()) 桩按“最近匹配优先”抢走 /view）。必须落到 URI path 维度计数：
        var paths = sendsByPath(http);   // 见下方 helper
        assertEquals(2L, paths.getOrDefault("/upload/image", 0L));  // 单张重复上传补第二槽位
        assertEquals(1L, paths.getOrDefault("/prompt", 0L));
        assertEquals(1L, paths.getOrDefault("/history/p1", 0L));
        assertEquals(1L, paths.getOrDefault("/view", 0L));
        assertEquals(5L, paths.values().stream().mapToLong(Long::longValue).sum());
    }

    /** 按请求路径统计 send 次数（比裸次数强：能区分「上传了几次参考图」和「下载了一次产物」） */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Long> sendsByPath(HttpClient http) throws Exception {
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        return cap.getAllValues().stream().collect(java.util.stream.Collectors.groupingBy(
                r -> java.net.URI.create(r.uri().toString()).getPath(), java.util.stream.Collectors.counting()));
    }

    @Test void combinedDegradesToLoraWhenCombinedTemplateMissing() throws Exception {
        String[] p = writeTemplates().split("\\|");
        ComfyUiImageGenerator g = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, p[3], "", 0.7);
        // 组合模板缺失 → 降级 generateWithLora（身份优先）；模板装配层验证不走 ipadapter
        var wf = g.buildWorkflow("p", "n", 1280, 720, "saki.safetensors", null);
        assertFalse(types(wf).contains("IPAdapter"));
        assertTrue(types(wf).contains("LoraLoader"));
    }
```

同一用例集的三条执行时补强（均已落地，后人改此块时勿退回）：

1. **`combinedDegradesToLoraWhenCombinedTemplateMissing` 要跑两种缺失形态**：`ipadapterLoraPath=""`（配置为空）与 `temp.resolve("absent_ipadapter_lora.json").toString()`（**已配非空路径但文件不在盘上**）。后者才是生产真实形态（@Value 默认值恒非空 + Task 9 未校准）。只用 `""` 会让 `!Files.exists(...)` 这一段守卫零覆盖——把 `Files.exists` 删掉测试仍全绿（Task 8 审查 I-2 实测）。
2. **`supportsReferencesFollowsTemplatePresence` 同理补第三段** absent-path 断言。
3. **补 spec §6.1「超 2 跳过」用例**（计划本节原本漏派）：3 张 http 参考图 → `sendsByPath` 断言 `/r1.png`、`/r2.png` 各 1 次、`/r3.png` **根本不出现**（第 3 张连回源都不该发生）、`/upload/image` 恰 2 次。用 http URL 而非 `data:` 才能观测到「取了哪两张」。

存量用例机械修正：L159/L186 直构造末尾补 `"", "", 0.7` 三参；`capabilityFlags` 断言改 `assertTrue(g.supportsReferences())`（newGen 现在含 ipadapter 模板），另保留 without 变体断言 false（已在新用例覆盖）。

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=ComfyUiImageGeneratorTest -q`
Expected: 编译失败「构造器不匹配 / 找不到八参 buildWorkflow」

- [ ] **Step 3: 实现 ComfyUiImageGenerator**

字段+构造器加 `ipadapterPath/ipadapterLoraPath/ipWeight(double)`（末三参）；构造器 log 补两模板路径。新增/修改：

```java
    @Override
    public boolean supportsReferences() {
        return ipadapterPath != null && !ipadapterPath.isBlank() && Files.exists(Path.of(ipadapterPath));
    }

    @Override
    public GeneratedImage generateWithReferences(String prompt, String negativePrompt, int width, int height,
                                                 java.util.List<String> referenceImageUrls) {
        return doGenerate(prompt, negativePrompt, width, height, null, null, referenceImageUrls);
    }

    @Override
    public GeneratedImage generateWithLoraAndReferences(String prompt, String negativePrompt, int width, int height,
                                                        String loraName, String controlNetRefUrl,
                                                        java.util.List<String> referenceImageUrls) {
        if (ipadapterLoraPath == null || ipadapterLoraPath.isBlank() || !Files.exists(Path.of(ipadapterLoraPath))) {
            log.warn("IP-Adapter+LoRA 组合模板未就位，降级纯 LoRA（身份优先）: {}", ipadapterLoraPath);
            return generateWithLora(prompt, negativePrompt, width, height, loraName, controlNetRefUrl);
        }
        // 组合模板不含 ControlNet 节点：controlNetRefUrl 忽略（spec YAGNI，机位锁定留纯 LoRA+canny 链路）
        return doGenerate(prompt, negativePrompt, width, height, loraName, null, referenceImageUrls);
    }

    /** 参考图上传文件名：毫秒+UUID 防并行同毫秒 overwrite 串图（cnFileName 同款） */
    String refFileName() {
        return "comfyui_ref_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".png";
    }
```

generate/generateWithLora 改调七参 doGenerate（末参 null）。buildWorkflow 六参保留委托：

```java
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName) {
        return buildWorkflow(prompt, negativePrompt, width, height, loraName, uploadedCnName, null, ipWeight);
    }

    /** 模板选择：refs 优先（组合>单 refs）> canny > lora > plain；PROMPT/NEGATIVE 最后替换防二次替换 */
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName,
                             java.util.List<String> uploadedRefNames, double ipWeight) {
        boolean hasRefs = uploadedRefNames != null && !uploadedRefNames.isEmpty();
        String path = hasRefs ? (loraName != null ? ipadapterLoraPath : ipadapterPath)
                : uploadedCnName != null ? cannyPath : loraName != null ? loraPath : plainPath;
        try {
            String tpl = Files.readString(Path.of(path));
            int[] size = imageSize(width, height);
            String wf = tpl;
            if (hasRefs) {
                wf = wf.replace("{{REF_IMAGE}}", ComfyUiClient.jsonEscape(objectMapper, uploadedRefNames.get(0)))
                        .replace("{{REF_IMAGE2}}", ComfyUiClient.jsonEscape(objectMapper,
                                uploadedRefNames.size() > 1 ? uploadedRefNames.get(1) : uploadedRefNames.get(0)))
                        .replace("{{IP_WEIGHT}}", String.valueOf(ipWeight)); // 数值不带引号
            }
            if (uploadedCnName != null) {
                wf = wf.replace("{{CN_IMAGE}}", ComfyUiClient.jsonEscape(objectMapper, uploadedCnName));
            }
            if (loraName != null) {
                wf = wf.replace("{{LORA_NAME}}", ComfyUiClient.jsonEscape(objectMapper, loraName));
            }
            wf = wf.replace("{{WIDTH}}", String.valueOf(size[0]))
                    .replace("{{HEIGHT}}", String.valueOf(size[1]))
                    .replace("{{SEED}}", String.valueOf(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE)))
                    .replace("{{NEGATIVE}}", ComfyUiClient.jsonEscape(objectMapper, negativePrompt == null ? "" : negativePrompt))
                    .replace("{{PROMPT}}", ComfyUiClient.jsonEscape(objectMapper, prompt == null ? "" : prompt));
            return (ObjectNode) objectMapper.readTree(wf);
        } catch (Exception e) {
            throw new RuntimeException("工作流模板装配失败（" + path + "）: " + e.getMessage(), e);
        }
    }
```

doGenerate 加末参 `java.util.List<String> referenceImageUrls`，开头上传段替换为：

```java
            java.util.List<String> refNames = null;
            String cnName = null;
            if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
                if (referenceImageUrls.size() > 2) {
                    log.warn("参考图 {} 张超上限，仅取前 2 张（CHARACTER 优先已由 resolver 排序）", referenceImageUrls.size());
                }
                byte[] first = fetchRefBytes(referenceImageUrls.get(0));
                refNames = new java.util.ArrayList<>();
                refNames.add(client.uploadImage(first, refFileName()));
                // 单张重复上传补第二槽位（模板 ImageBatch 双 LoadImage 固定两槽）
                refNames.add(client.uploadImage(referenceImageUrls.size() > 1
                        ? fetchRefBytes(referenceImageUrls.get(1)) : first, refFileName()));
            } else if (controlNetRefUrl != null && !controlNetRefUrl.isBlank()) {
                cnName = client.uploadImage(fetchRefBytes(controlNetRefUrl), cnFileName());
            }
```

后续 `buildWorkflow(prompt, negativePrompt, width, height, loraName, cnName, refNames, ipWeight)`，其余逐字不变。

- [ ] **Step 4: 配置接线**

ImageGeneratorConfig.comfyUiImageGenerator 加三参（**形参末尾**，不是 cannyPath 后——Bean 方法形参与构造调用实参需同序，而 `ComfyUiImageGenerator` 构造器契约要求末三参；@Value 按名绑定，位置不影响行为）：

```java
            @Value("${image.comfyui.workflow-ipadapter:docs/comfyui/sd15_ipadapter.template.json}") String ipadapterPath,
            @Value("${image.comfyui.workflow-ipadapter-lora:docs/comfyui/sd15_ipadapter_lora.template.json}") String ipadapterLoraPath,
            @Value("${image.comfyui.ipadapter-weight:0.7}") double ipWeight,
```

构造调用末尾补 `ipadapterPath, ipadapterLoraPath, ipWeight`。application.yml `image.comfyui` 块（workflow-canny 行后）加：

```yaml
      workflow-ipadapter: ${COMFYUI_IMG_IPADAPTER:docs/comfyui/sd15_ipadapter.template.json}
      workflow-ipadapter-lora: ${COMFYUI_IMG_IPADAPTER_LORA:docs/comfyui/sd15_ipadapter_lora.template.json}
      ipadapter-weight: 0.7              # 行业甜点区 0.65-0.85
```

- [ ] **Step 5: 跑绿 + 存量回归**

Run: `mvn test -Dtest=ComfyUiImageGeneratorTest -q`
Expected: 存量 + 新增全绿（repoTemplatesCalibrated 仍绿：六参委托不受影响）

- [ ] **Step 6: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/video/generator/ComfyUiImageGenerator.java src/main/java/com/github/ylyan2015/springaidemo/config/ImageGeneratorConfig.java src/main/resources/application.yml src/test/java/com/github/ylyan2015/springaidemo/video/generator/ComfyUiImageGeneratorTest.java
```

### Task 9: 模板校准（⚠️ 用户手动步骤）+ gated 真实冒烟

**Files:**
- Create: `docs/comfyui/sd15_ipadapter.template.json`（用户校准产出）
- Create: `docs/comfyui/sd15_ipadapter_lora.template.json`（用户校准产出）
- Test: `service/IpAdapterRealSmokeTest.java`

**Interfaces:**
- Consumes: Task 8 全部；spec 附录 A 节点图与占位符对照表
- Produces: 两个已校准模板（supportsReferences 转 true 的开关）；gated 冒烟 `-Dipadapter=true`；**同 seed 对照入口 `ComfyUiImageGenerator.generateWithSeed(prompt, negative, w, h, refs, seed)`**（refs 传 null 即对照组；不进 `ImageGenerator` 接口）

- [ ] **Step 1: ⚠️ 提醒用户模板校准（本步由用户执行，AI 不代搭工作流）**

向用户发出如下提醒并等待完成：

> **S3 模板校准提醒**：模型与节点已实查就位（无需下载）：
> - 节点 `D:\Comfy-Desktop\ComfyUI-Installs\ComfyUI\ComfyUI\custom_nodes\ComfyUI_IPAdapter_plus`
> - `models\ipadapter\ip-adapter-plus_sd15.safetensors` + `models\clip_vision\CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors`
>
> 请在 ComfyUI Desktop 搭工作流（spec 附录 A）：`CheckpointLoaderSimple → IPAdapter(weight 0.7) → KSampler → VAEDecode → SaveImage`，`LoadImage×2 → ImageBatch → IPAdapter`，`IPAdapterUnifiedLoader` 选 PLUS 模型 + ViT-H CLIP Vision；prompt/negative/尺寸/seed 沿用现有 sd15_storyboard 模板配方（22 步 CFG7 euler/normal）。跑通一张后导出 API JSON，字面量换占位符：图片文件名→`{{REF_IMAGE}}`/`{{REF_IMAGE2}}`（保留引号）、weight→`{{IP_WEIGHT}}`（**去引号**）、prompt/negative→`{{PROMPT}}`/`{{NEGATIVE}}`（保留引号）、宽高/seed→`{{WIDTH}}`/`{{HEIGHT}}`/`{{SEED}}`（去引号），存 `docs/comfyui/sd15_ipadapter.template.json`。组合版再串 LoraLoader（`{{LORA_NAME}}`，strength 1.0/1.0 沿用身份 LoRA 实测配方）存 `sd15_ipadapter_lora.template.json`。

校准前代码切片不阻塞：模板缺失时 supportsReferences=false 自动降级，常规回归不受影响。

> **校准清单补两条（Task 8 审查 S-2/S-3，2026-11-18）**：
> - **双参考图分辨率归一**：现有 `sd15_ipadapter.template.json` 节点 5/6 两路 `LoadImage` 直进 `ImageBatch`，中间没有任何缩放节点。`ImageBatch` 对「两路尺寸不同」的行为**版本相关**（旧版张量 concat 会直接报错，新版才可能隐式 resize），而素材库里角色定妆图（常见 512×768）+ 场景图（常见 1024×1024）尺寸不匹配是常态。请在校准时用**两张不同尺寸**的参考图实跑一次；若报错，在 `ImageBatch` 前各补一个 `ImageScale`（统一 512×768 或 768×768）。单张场景（双槽同字节同尺寸）不受影响。
> - **容器形态**：`Dockerfile` 只 `COPY src`，运行镜像不含 `docs/` → 容器内 `supportsReferences()` 恒 false、IP-Adapter 静默降级（存量 `isAvailable()` 同命运）。本机验证请在 host 跑；若要容器生效，需挂 `docs/comfyui` 卷并同步 `COMFYUI_IMG_IPADAPTER*` 路径。
>
> **生效/失效线索**：组合模板缺失的 WARN 已改为**每实例只打一次**（后续降 DEBUG），整集 40+ 镜不会刷屏；判断是否真走了 IP-Adapter，看完成日志的 `refs=` 字段（`refs=0` = 降级，`refs=2` = 双槽补位成功）。

- [ ] **Step 2: 写 gated 冒烟测试**（镜像 ComfyUiImageRealSmokeTest idiom：DEFINED_PORT 保证同源取字节）

> 端口分配（2026-11-18 用户指令）：`ComfyUiRealSmokeTest`=18080、`ComfyUiImageRealSmokeTest`=18081（均为存量已就位），本新冒烟类预留 **18082** —— DEFINED_PORT 下类内 server.port 必须全局唯一，否则两类均真跑时后启动者上下文即 `PortInUseException`；平时 gated 类被 assumeTrue skip，此类冲突只在相关开关打开时暴露，新类仍须先查现有端口再选号。

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.generator.ComfyUiImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S3 IP-Adapter 真实冒烟（默认跳过；-Dipadapter=true 执行）。
 * 生效唯一可信证据 = 同 prompt 无 refs 对照组并排目视（spec §6.3），产物落 target/ipadapter-smoke/。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18082")
class IpAdapterRealSmokeTest {

    @Autowired ComfyUiImageGenerator gen;
    @Autowired com.github.ylyan2015.springaidemo.storage.StorageService storage;

    @Test
    void ipAdapterSingleShotWithControlGroup() throws Exception {
        assumeTrue(Boolean.getBoolean("ipadapter"), "真实冒烟需 -Dipadapter=true");
        assumeTrue(gen.isAvailable(), "需 ComfyUI 启动且模板已校准");
        assumeTrue(gen.supportsReferences(), "需 sd15_ipadapter.template.json 已校准（Task 9 Step 1）");
        // 参考图：任取一张本地人物图（测试资源或用户素材），经存储转存拿相对 URL
        Path refSrc = Path.of("docs/comfyui/smoke-ref.png");
        assumeTrue(Files.exists(refSrc), "需准备参考图 docs/comfyui/smoke-ref.png（用户素材任选一张）");
        String fileId;
        try (var in = Files.newInputStream(refSrc)) {
            fileId = storage.upload(in, "smoke-ref.png", "image/png");
        }
        String prompt = "1girl, black long hair, red coat, rainy alley at dusk, neon reflections, best quality, anime coloring";
        String negative = "low quality, blurry, watermark, text";
        // 实测修正（2026-11-18 Task 9 执行时）：spec §6.3 写的是「同 prompt 同 seed 无 refs 对照组」，
        // 而常规 generate 每次重掷 seed（buildWorkflow 内 ThreadLocalRandom），两张图的差异 = IP-Adapter 效应
        // + 随机构图漂移，无法归因。故两组均走 generateWithSeed 固定同一个 seed。
        long seed = 20261118L;
        GeneratedImage withRefs = gen.generateWithSeed(prompt, negative, 1280, 720,
                List.of(storage.getAccessUrl(fileId)), seed);
        assertNotNull(withRefs.getFileId());
        assertEquals(1280, withRefs.getWidth());
        assertEquals(704, withRefs.getHeight());
        GeneratedImage control = gen.generateWithSeed(prompt, negative, 1280, 720, null, seed);
        Path out = Path.of("target/ipadapter-smoke");
        Files.createDirectories(out);
        try (var in = storage.download(withRefs.getFileId())) {
            Files.copy(in, out.resolve("with-refs.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        try (var in = storage.download(control.getFileId())) {
            Files.copy(in, out.resolve("control-no-refs.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        // 实测修正：不断言「两图字节不同」（seed 随机时恒真，给不了生效信号），改断言可解码（零依赖，能抓截断/坏图），
        // 并把两张并排合成 side-by-side.png（左=带参考图、右=同 seed 对照组），使「并排目视」只需打开一个文件
        assertNotNull(ImageIO.read(out.resolve("with-refs.png").toFile()), "with-refs 必须是可解码 PNG");
        assertNotNull(ImageIO.read(out.resolve("control-no-refs.png").toFile()), "control 必须是可解码 PNG");
        writeSideBySide(out);
        // 目视判读：side-by-side.png 左图应继承参考图配色/天气/材质，右图不应（同 seed 下差异可归因给 IPAdapter）
    }

    /** 把两张产物并排合成一个 PNG（「并排目视」交付为单文件） */
    private static void writeSideBySide(Path out) throws Exception {
        BufferedImage refs = ImageIO.read(out.resolve("with-refs.png").toFile());
        BufferedImage control = ImageIO.read(out.resolve("control-no-refs.png").toFile());
        BufferedImage both = new BufferedImage(refs.getWidth() + control.getWidth(),
                Math.max(refs.getHeight(), control.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        g.drawImage(refs, 0, 0, null);
        g.drawImage(control, refs.getWidth(), 0, null);
        g.dispose();
        ImageIO.write(both, "png", out.resolve("side-by-side.png").toFile());
    }
}
```

- [ ] **Step 3: 常规回归验证 gating 不污染**

Run: `mvn test -Dtest=IpAdapterRealSmokeTest -q`
Expected: SKIPPED（assumeTrue 三连，无 -Dipadapter 时零真实调用）

- [ ] **Step 4: 用户校准完成后执行真实冒烟**

Run: `mvn test -Dtest=IpAdapterRealSmokeTest -Dipadapter=true -q`
Expected: PASS；目视 **`target/ipadapter-smoke/side-by-side.png`**（左=带参考图、右=同 seed 无参考图对照组），左图应继承参考图的配色/天气/材质而建；结果+该图路径记入验收文档（Task 14 汇总）。已执行取证（2026-11-18，ComfyUI 8188 在线）：19/19 绿，side-by-side 左图=青雾+暖橙灯笼+雨丝+湿石板反射+传统瓦檐（与参考图同），右图=红霓虹冷灰走廊无雨；checkpoint/steps/cfg/sampler/scheduler 两组全同，唯一变量=IPAdapter 子图。

- [ ] **Step 5: 暂存（不提交）+ S3 切片门槛**

```powershell
git add src/test/java/com/github/ylyan2015/springaidemo/service/IpAdapterRealSmokeTest.java docs/comfyui/sd15_ipadapter.template.json docs/comfyui/smoke-ref.png
# 注（2026-11-18 实测）：组合模板 docs/comfyui/sd15_ipadapter_lora.template.json 属用户手动产出，
# 未落盘前不能写进 git add（pathspec 会直接报错退非零）；smoke-ref.png 为 gated 冒烟参考图 fixture，
# 已从 uploads/ 任一素材复制入库（否则 assumeTrue 永远 skip，S3 无法取证）。
mvn clean package -q
```

Expected: BUILD SUCCESS（S3 完，进 S4；真实冒烟若用户尚未校准则记录待办，不阻塞 S4 代码切片）

---

# S4 网搜入库

### Task 10: BooruProvider 接口 + 双源适配器

**Files:**
- Create: `video/generator/BooruProvider.java`（接口 + BooruPost record 同文件）
- Create: `video/generator/SafebooruOrgProvider.java`
- Create: `video/generator/DanbooruSafeProvider.java`
- Test: `video/generator/BooruProviderTest.java`

**Interfaces:**
- Consumes: 注入 mock HttpClient（OpenAiCompatibleImageGeneratorTest idiom）；Jackson ObjectMapper
- Produces: `record BooruPost(String provider, long postId, String previewUrl, String fileUrl, String tags, String rating, int width, int height, String sourcePageUrl)`；`interface BooruProvider { String name(); java.util.List<BooruPost> search(String tags, int limit, int page) throws Exception; BooruPost findById(long postId) throws Exception; }`；两实现 @Component（name()="safebooru"/"danbooru-safe"），List 注入自动收录；`static String BooruProvider.normalizeTags(String)`（trim+小写+空白转下划线）

- [ ] **Step 1: 写失败测试**

> 实测修正（2026-11-18 Task 10 执行时）：下方 `json(body)` helper 内联在 `thenReturn(json(..))` 参数位会在外层 stubbing 未完成时开新桩，Mockito 抛 `UnfinishedStubbingException`（12 例中 7 例同因）。正确写法是改成 `private void stubJson(HttpClient http, String body) throws Exception`（先 mock+stub 好 HttpResponse，再 stub `http.send`）并将各调用点改为 `stubJson(http, "...")`；helper 必须声明 `throws Exception`（`HttpClient.send` 带受检异常）。已落地的测试共 12 例（比本节5 例多 7 例：encodeTagQuery 分隔符、脏条目过滤、非数组体、danbooru 单对象 findById、无 file_url 抛错、preview 回退、结构红线依赖断言）。

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BooruProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private HttpResponse<String> json(String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(200);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void normalizeTagsLowercasesAndUnderscores() {
        assertEquals("hatsune_miku", BooruProvider.normalizeTags("  Hatsune Miku "));
        assertEquals("1girl", BooruProvider.normalizeTags("1girl"));
    }

    @Test @SuppressWarnings("unchecked")
    void safebooruOrgParsesDapiAndBuildsFileUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(json(
                "[{\"id\":123,\"directory\":\"aa\",\"image\":\"bb.png\",\"tags\":\"1girl hatsune_miku\","
                        + "\"rating\":\"safe\",\"width\":800,\"height\":600,\"preview_url\":\"https://safebooru.org/samples/aa/sample_bb.jpg\"}]"));
        SafebooruOrgProvider p = new SafebooruOrgProvider(http, om);
        var posts = p.search("hatsune_miku", 20, 0);
        assertEquals(1, posts.size());
        BooruPost post = posts.get(0);
        assertEquals("safebooru", post.provider());
        assertEquals(123, post.postId());
        assertEquals("https://safebooru.org/images/aa/bb.png", post.fileUrl());
        assertEquals("safe", post.rating());
        assertEquals("https://safebooru.org/index.php?page=post&s=view&id=123", post.sourcePageUrl());
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        String uri = cap.getValue().uri().toString();
        assertTrue(uri.startsWith("https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1"));
        assertTrue(uri.contains("tags=hatsune_miku") && uri.contains("limit=20") && uri.contains("pid=0"));
    }

    @Test @SuppressWarnings("unchecked")
    void danbooruSafeForcesRatingGAndParsesPostsJson() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(json(
                "[{\"id\":456,\"file_url\":\"https://safebooru.donmai.us/data/cc.png\","
                        + "\"preview_file_url\":\"https://safebooru.donmai.us/data/preview/cc.jpg\","
                        + "\"tag_string\":\"1girl hatsune_miku\",\"rating\":\"g\",\"image_width\":800,\"image_height\":600}]"));
        DanbooruSafeProvider p = new DanbooruSafeProvider(http, om);
        var posts = p.search("hatsune_miku", 20, 0);
        assertEquals("https://safebooru.donmai.us/data/cc.png", posts.get(0).fileUrl());
        assertEquals("g", posts.get(0).rating());
        assertEquals("https://safebooru.donmai.us/posts/456", posts.get(0).sourcePageUrl());
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        String uri = cap.getValue().uri().toString();
        assertTrue(uri.startsWith("https://safebooru.donmai.us/posts.json"));
        assertTrue(uri.contains("rating%3Ag") || uri.contains("rating:g")); // 强制追加 rating:g
        assertTrue(uri.contains("page=1")); // 0 基页码转 1 基
    }

    @Test @SuppressWarnings("unchecked")
    void findByIdUsesIdTagAndThrowsWhenMissing() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(json("[]"));
        SafebooruOrgProvider p = new SafebooruOrgProvider(http, om);
        assertThrows(RuntimeException.class, () -> p.findById(999));
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(cap.getValue().uri().toString().contains("tags=id%3A999")
                || cap.getValue().uri().toString().contains("tags=id:999"));
    }

    @Test @SuppressWarnings("unchecked")
    void non200Throws() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(403);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(r);
        assertThrows(RuntimeException.class, () -> new DanbooruSafeProvider(http, om).search("x", 20, 0));
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=BooruProviderTest -q`
Expected: 编译失败「找不到符号 BooruProvider」

- [ ] **Step 3: 实现**

BooruProvider.java：

```java
package com.github.ylyan2015.springaidemo.video.generator;

import java.util.List;

/** booru 图站适配器（spec §4.1）：归一化 BooruPost，双源 List 注入 */
public interface BooruProvider {

    record BooruPost(String provider, long postId, String previewUrl, String fileUrl,
                     String tags, String rating, int width, int height, String sourcePageUrl) {}

    String name();

    /** tags 已归一化（下划线，多 tag + 连接）；page 0 基；limit ≤20 */
    List<BooruPost> search(String tags, int limit, int page) throws Exception;

    /** 入库重查专用（spec §4.2 闸 2：不信任客户端 URL） */
    BooruPost findById(long postId) throws Exception;

    static String normalizeTags(String q) {
        return q == null ? "" : q.trim().toLowerCase().replaceAll("\\s+", "_");
    }
}
```

SafebooruOrgProvider.java（@Component；构造器 `(HttpClient, ObjectMapper)` 供测试注入；另提供无参构造自建 `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()` 供 Spring 装配）：

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** safebooru.org 主源（Gelbooru 引擎 dapi json=1；全站 safe + 服务层 rating 复核） */
@Component
public class SafebooruOrgProvider implements BooruProvider {

    private static final String BASE = "https://safebooru.org";
    private final HttpClient http;
    private final ObjectMapper om;

    public SafebooruOrgProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper());
    }

    public SafebooruOrgProvider(HttpClient http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override public String name() { return "safebooru"; }

    @Override
    public List<BooruPost> search(String tags, int limit, int page) throws Exception {
        String url = BASE + "/index.php?page=dapi&s=post&q=index&json=1&tags="
                + URLEncoder.encode(tags, StandardCharsets.UTF_8)
                + "&limit=" + limit + "&pid=" + page;
        return parse(get(url));
    }

    @Override
    public BooruPost findById(long postId) throws Exception {
        List<BooruPost> posts = search("id:" + postId, 1, 0);
        if (posts.isEmpty()) {
            throw new RuntimeException("safebooru 帖子不存在: " + postId);
        }
        return posts.get(0);
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "spring-ai-demo-asset-library").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("safebooru 响应异常: HTTP " + resp.statusCode());
        }
        return om.readTree(resp.body());
    }

    private List<BooruPost> parse(JsonNode arr) {
        List<BooruPost> posts = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return posts; // 空结果时 dapi 可能返回非数组，视为空
        }
        for (JsonNode n : arr) {
            String fileUrl = BASE + "/images/" + n.path("directory").asText() + "/" + n.path("image").asText();
            String preview = n.path("preview_url").asText("");
            posts.add(new BooruPost(name(), n.path("id").asLong(),
                    preview.isBlank() ? fileUrl : preview, fileUrl,
                    n.path("tags").asText(""), n.path("rating").asText(""),
                    n.path("width").asInt(), n.path("height").asInt(),
                    BASE + "/index.php?page=post&s=view&id=" + n.path("id").asLong()));
        }
        return posts;
    }
}
```

DanbooruSafeProvider.java（同款双构造器；BASE=`https://safebooru.donmai.us`）：search URL = `BASE + "/posts.json?tags=" + URLEncoder.encode(tags + "+rating:g", UTF_8) + "&limit=" + limit + "&page=" + (page + 1)`；findById = GET `BASE + "/posts/" + postId + ".json"` 单对象解析；字段映射 `file_url/preview_file_url/tag_string/rating/image_width/image_height`，sourcePageUrl=`BASE + "/posts/" + id`；非 200 抛 RuntimeException。

- [ ] **Step 4: 跑绿**

Run: `mvn test -Dtest=BooruProviderTest -q`
Expected: 5/5 PASS

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/video/generator/BooruProvider.java src/main/java/com/github/ylyan2015/springaidemo/video/generator/SafebooruOrgProvider.java src/main/java/com/github/ylyan2015/springaidemo/video/generator/DanbooruSafeProvider.java src/test/java/com/github/ylyan2015/springaidemo/video/generator/BooruProviderTest.java
```

### Task 11: BooruSearchService 三道闸 + 三端点

**Files:**
- Create: `service/BooruSearchService.java`
- Create: `controller/BooruAssetController.java`
- Modify: `src/main/resources/application.yml`（video.assets.booru 块）
- Test: `service/BooruSearchServiceTest.java`

**Interfaces:**
- Consumes: Task 10 `BooruProvider/BooruPost`（List 注入）；Task 1 category 语义；`StorageService.upload(InputStream, String, String)`；`ModelService.getChatClient()`（中文转 tag）
- Produces: `List<BooruPost> search(String q, String provider, int page)`；`byte[] preview(String provider, long postId)`（controller 补 contentType）；`MediaAsset ingest(String provider, long postId, String category, String name, String tags)`；静态 seam `static void validateUrl(String url, java.util.Set<String> allowedHosts)`；配置 `video.assets.booru.allowed-hosts/default-provider`

- [ ] **Step 1: 写失败测试**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BooruSearchServiceTest {

    BooruProvider provider = mock(BooruProvider.class);
    StorageService storage = mock(StorageService.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    ModelService modelService = mock(ModelService.class);
    HttpClient http = mock(HttpClient.class);
    BooruSearchService service;

    private static final BooruPost POST = new BooruPost("safebooru", 123,
            "https://safebooru.org/samples/aa/sample_bb.jpg", "https://safebooru.org/images/aa/bb.png",
            "1girl hatsune_miku", "safe", 800, 600,
            "https://safebooru.org/index.php?page=post&s=view&id=123");

    @BeforeEach
    void setUp() {
        when(provider.name()).thenReturn("safebooru");
        service = new BooruSearchService(List.of(provider), storage, assetRepo, userRepo, modelService, http,
                "safebooru.org,safebooru.donmai.us", "safebooru");
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void validateUrlRejectsNonWhitelistHttpAndRedirectProneForms() {
        Set<String> hosts = Set.of("safebooru.org");
        assertDoesNotThrow(() -> BooruSearchService.validateUrl("https://safebooru.org/images/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("https://evil.com/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("http://safebooru.org/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("https://safebooru.org.evil.com/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("not-a-url", hosts));
    }

    @Test
    void searchFiltersUnsafeRatingAndNonWhitelistHost() throws Exception {
        BooruPost unsafe = new BooruPost("safebooru", 1, "https://safebooru.org/p.jpg",
                "https://safebooru.org/f.jpg", "t", "explicit", 1, 1, "https://safebooru.org/index.php?page=post&s=view&id=1");
        BooruPost evilHost = new BooruPost("safebooru", 2, "https://evil.com/p.jpg",
                "https://evil.com/f.jpg", "t", "safe", 1, 1, "https://safebooru.org/index.php?page=post&s=view&id=2");
        when(provider.search(eq("hatsune_miku"), eq(20), eq(0))).thenReturn(List.of(POST, unsafe, evilHost));
        var result = service.search("hatsune_miku", "safebooru", 0);
        assertEquals(1, result.size());
        assertEquals(123, result.get(0).postId());
    }

    @Test
    void danbooruRatingCodingIsAlsoAccepted() throws Exception {
        // 双源 rating 编码不同（safebooru=safe / donmai=g）：复核若只认 "safe"，备源会整源落空
        BooruPost gPost = new BooruPost("danbooru-safe", 3, "https://safebooru.donmai.us/p.jpg",
                "https://safebooru.donmai.us/f.jpg", "t", "g", 1, 1, "https://safebooru.donmai.us/posts/3");
        BooruPost qPost = new BooruPost("danbooru-safe", 4, "https://safebooru.donmai.us/p2.jpg",
                "https://safebooru.donmai.us/f2.jpg", "t", "q", 1, 1, "https://safebooru.donmai.us/posts/4");
        when(provider.search(eq("hatsune_miku"), eq(20), eq(0))).thenReturn(List.of(gPost, qPost));
        var result = service.search("hatsune_miku", "danbooru-safe", 0);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0).postId());
    }

    @Test
    void chineseQueryTranslatesViaLlmWithFallback() throws Exception {
        var client = mock(org.springframework.ai.chat.client.ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("hatsune_miku");
        when(provider.search(eq("hatsune_miku"), anyInt(), anyInt())).thenReturn(List.of(POST));
        assertEquals(1, service.search("初音未来", "safebooru", 0).size());
        // LLM 挂 → 降级原文下划线化直查，不报错
        when(modelService.getChatClient()).thenThrow(new RuntimeException("LLM 不可用"));
        when(provider.search(eq("初音未来"), anyInt(), anyInt())).thenReturn(List.of());
        assertTrue(service.search("初音未来", "safebooru", 0).isEmpty());
    }

    @Test @SuppressWarnings("unchecked")
    void ingestRequeriesProviderAndWritesSourceNote() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(resp.body()).thenReturn(new byte[]{1, 2, 3});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(resp);
        when(storage.upload(any(), anyString(), eq("image/png"))).thenReturn("bf1");
        when(storage.getAccessUrl("bf1")).thenReturn("/images/bf1.png");

        MediaAsset a = service.ingest("safebooru", 123, "CHARACTER", "初音", null);
        assertEquals("IMAGE", a.getKind());
        assertEquals("CHARACTER", a.getCategory());
        assertEquals("bf1", a.getFileId());
        assertTrue(a.getSourceNote().contains("https://safebooru.org/index.php?page=post&s=view&id=123"));
        assertTrue(a.getSourceNote().contains("hatsune_miku"));
        verify(provider).findById(123); // 闸 2：服务端重查，不信任客户端 URL
        // 红线硬断言（Task 10 审查 S-4）：外部 fileUrl 绝不可进 MediaAsset.url
        assertEquals("/images/bf1.png", a.getUrl());
        assertFalse(a.getUrl().contains("safebooru"), "url 必须是自家存储访问路径，不是外链直链");
    }

    @Test
    void ingestRejectsInsertCategory() {
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "INSERT", "x", null));
    }

    @Test @SuppressWarnings("unchecked")
    void downloadGuardRejectsOversizeAndNonImage() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<byte[]> big = mock(HttpResponse.class);
        when(big.statusCode()).thenReturn(200);
        when(big.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(big.body()).thenReturn(new byte[20 * 1024 * 1024 + 1]);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(big);
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));

        HttpResponse<byte[]> html = mock(HttpResponse.class);
        when(html.statusCode()).thenReturn(200);
        when(html.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")), (a, b) -> true));
        when(html.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(html);
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));
    }

    @Test
    void pageCapAtFive() throws Exception {
        when(provider.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        service.search("x", "safebooru", 99);
        verify(provider).search(eq("x"), eq(20), eq(4)); // ≤5 页（0 基最大 4）
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=BooruSearchServiceTest -q`
Expected: 编译失败「找不到符号 BooruSearchService」

- [ ] **Step 3: 实现 BooruSearchService**

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * booru 网搜入库（spec §4）：三道闸 = host 白名单+https+禁重定向 / ingest 只认 provider+postId 重查 / 下载护栏。
 * 外链永不进生成链路：字节经 storageService.upload 转存，sourceNote 版权可追溯。
 */
@Service
public class BooruSearchService {

    private static final Logger log = LoggerFactory.getLogger(BooruSearchService.class);
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE = 4; // 0 基，≤5 页
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final Map<String, BooruProvider> providers = new LinkedHashMap<>();
    private final StorageService storageService;
    private final MediaAssetRepository assetRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;
    private final HttpClient http; // 禁重定向（构造器注入或自建 NEVER）
    private final Set<String> allowedHosts;
    private final String defaultProvider;

    public BooruSearchService(List<BooruProvider> providerList, StorageService storageService,
                              MediaAssetRepository assetRepository, UserRepository userRepository,
                              ModelService modelService, HttpClient http,
                              @Value("${video.assets.booru.allowed-hosts:safebooru.org,safebooru.donmai.us}") String allowedHosts,
                              @Value("${video.assets.booru.default-provider:safebooru}") String defaultProvider) {
        providerList.forEach(p -> providers.put(p.name(), p));
        this.storageService = storageService;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
        this.http = http;
        this.allowedHosts = new HashSet<>(Arrays.asList(allowedHosts.split(",")));
        this.defaultProvider = defaultProvider;
    }

    /** 闸 1：https + host 精确匹配白名单（子域伪装拒） */
    static void validateUrl(String url, Set<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new RuntimeException("非法 URL: " + url);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !allowedHosts.contains(uri.getHost())) {
            throw new RuntimeException("URL 不在白名单或非 https: " + url);
        }
    }

    public List<BooruPost> search(String q, String provider, int page) {
        getCurrentUserId();
        BooruProvider p = resolveProvider(provider);
        String tags = toBooruTags(q);
        try {
            List<BooruPost> posts = p.search(tags, PAGE_SIZE, Math.max(0, Math.min(MAX_PAGE, page)));
            List<BooruPost> safe = new ArrayList<>();
            for (BooruPost post : posts) {
                if (!("safe".equals(post.rating()) || "g".equals(post.rating()))) {
                    continue; // 分级复核：非 safe/g 一律丢弃
                }
                try {
                    validateUrl(post.fileUrl(), allowedHosts);
                    validateUrl(post.previewUrl(), allowedHosts);
                } catch (RuntimeException e) {
                    log.warn("booru 结果 host 非白名单丢弃: {}", e.getMessage());
                    continue;
                }
                safe.add(post);
            }
            return safe;
        } catch (Exception e) {
            throw new RuntimeException("booru 搜索失败: " + e.getMessage(), e);
        }
    }

    /** 预览代理：服务端重查拿 previewUrl 过闸取字节（解决浏览器直连网络问题） */
    public byte[] preview(String provider, long postId) {
        getCurrentUserId();
        BooruPost post = requery(provider, postId);
        validateUrl(post.previewUrl(), allowedHosts);
        return guardedDownload(post.previewUrl());
    }

    public MediaAsset ingest(String provider, long postId, String category, String name, String tags) {
        Long userId = getCurrentUserId();
        if (!("CHARACTER".equals(category) || "SCENE".equals(category))) {
            throw new RuntimeException("参考素材 category 仅支持 CHARACTER/SCENE: " + category);
        }
        BooruPost post = requery(provider, postId); // 闸 2：不信任客户端 URL
        validateUrl(post.fileUrl(), allowedHosts);
        byte[] bytes = guardedDownload(post.fileUrl());
        String ext = post.fileUrl().substring(post.fileUrl().lastIndexOf('.'));
        String contentType = ext.equalsIgnoreCase(".png") ? "image/png"
                : ext.equalsIgnoreCase(".webp") ? "image/webp" : "image/jpeg";
        String fileId = storageService.upload(new ByteArrayInputStream(bytes),
                "booru_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ext, contentType);
        MediaAsset a = new MediaAsset();
        a.setUserId(userId);
        a.setName(name == null || name.isBlank() ? post.tags() : name);
        a.setKind("IMAGE");
        a.setCategory(category);
        a.setFileId(fileId);
        a.setUrl(storageService.getAccessUrl(fileId));
        a.setTags(tags == null || tags.isBlank() ? post.tags() : tags);
        a.setSourceNote(post.sourcePageUrl() + " | tags=" + post.tags() + " | rating=" + post.rating());
        MediaAsset saved = assetRepository.save(a);
        log.info("Booru asset ingested: id={}, provider={}, postId={}, user={}", saved.getId(), provider, postId, userId);
        return saved;
    }

    // ==================== 内部方法 ====================

    private BooruProvider resolveProvider(String name) {
        BooruProvider p = providers.get(name == null || name.isBlank() ? defaultProvider : name);
        if (p == null) {
            throw new RuntimeException("未知 booru 源: " + name);
        }
        return p;
    }

    private BooruPost requery(String provider, long postId) {
        try {
            return resolveProvider(provider).findById(postId);
        } catch (Exception e) {
            throw new RuntimeException("booru 帖子重查失败: " + e.getMessage(), e);
        }
    }

    /** 中文 → LLM 转 booru 英文 tag；失败降级原文下划线化直查（spec §4.1） */
    private String toBooruTags(String q) {
        String normalized = BooruProvider.normalizeTags(q);
        if (q != null && q.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
            try {
                String translated = modelService.getChatClient().prompt()
                        .system("把中文动漫角色/场景名转成 booru 英文 tag：小写、空格转下划线、多 tag 用 + 连接。只输出 tag 本身。")
                        .user(q).call().content();
                if (translated != null && !translated.isBlank()) {
                    return translated.trim().toLowerCase();
                }
            } catch (Exception e) {
                log.warn("中文转 booru tag 失败，降级原文直查: {}", e.getMessage());
            }
        }
        return normalized;
    }

    /** 闸 3：10s 连接/30s 读取（HttpClient 构造时已设）+ ≤20MB + Content-Type image/*；禁重定向 */
    private byte[] guardedDownload(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "spring-ai-demo-asset-library").GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("下载响应异常: HTTP " + resp.statusCode());
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("");
            if (!ct.startsWith("image/")) {
                throw new RuntimeException("非图片 Content-Type: " + ct);
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                throw new RuntimeException("图片大小超限或为空: " + (body == null ? 0 : body.length));
            }
            return body;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("booru 图片下载失败: " + e.getMessage(), e);
        }
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }
}
```

**HttpClient 装配**：BooruSearchService 需禁重定向 client——在 config 新建 `config/BooruConfig.java`：

```java
package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;

/**
 * booru 专用 HttpClient：禁跟随重定向（spec §4.2 闸 1）+ 10s 连接超时。
 * 策略必须复用 BooruProvider.newHttpClient()——不得在此重写一份 10s/NEVER：
 * 两处安全配置日后只改一处即漂移（Task 10 审查 S-3）。
 */
@Configuration
public class BooruConfig {

    @Bean
    public HttpClient booruHttpClient() {
        return BooruProvider.newHttpClient();
    }
}
```

注意：若容器已有其他 HttpClient Bean 会冲突——执行时 grep `HttpClient` @Bean 核实；有则本 Bean 加 `@Qualifier("booruHttpClient")` 且 BooruSearchService 构造参同步加 Qualifier。

BooruAssetController（镜像 MediaAssetController 响应 idiom）：

```java
package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.BooruSearchService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/video/assets/booru")
public class BooruAssetController {

    private final BooruSearchService booruSearchService;

    public BooruAssetController(BooruSearchService booruSearchService) {
        this.booruSearchService = booruSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q,
                                                      @RequestParam(required = false) String provider,
                                                      @RequestParam(defaultValue = "0") int page) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", booruSearchService.search(q, provider, page));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestParam String provider, @RequestParam long postId) {
        byte[] bytes = booruSearchService.preview(provider, postId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", booruSearchService.ingest(
                    (String) body.get("provider"),
                    Long.parseLong(String.valueOf(body.get("postId"))),
                    (String) body.get("category"),
                    (String) body.get("name"),
                    (String) body.get("tags")));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
```

application.yml `video.assets` 块（Task 2 已建）追加：

```yaml
    booru:
      allowed-hosts: safebooru.org,safebooru.donmai.us   # 两源图片均同域，无额外 CDN；可扩展
      default-provider: safebooru                        # safebooru / danbooru-safe
```

- [ ] **Step 4: 跑绿 + 安全配置核验**

Run: `mvn test -Dtest=BooruSearchServiceTest -q` → 计划本节的 Step 1 实为 **8 个 @Test**（不是 Step 4 原先写的 7/7）；执行时补至 24 例（含 BooruConfigTest 1 例）。
核验 SecurityConfig `/api/video/**` 覆盖新端点（Task 4 同款核验）。

- [ ] **Step 5: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/service/BooruSearchService.java src/main/java/com/github/ylyan2015/springaidemo/controller/BooruAssetController.java src/main/java/com/github/ylyan2015/springaidemo/config/BooruConfig.java src/main/resources/application.yml src/test/java/com/github/ylyan2015/springaidemo/service/BooruSearchServiceTest.java src/test/java/com/github/ylyan2015/springaidemo/config/BooruConfigTest.java
```

#### Task 11 执行时实测修正（2026-11-18，两轮审查 + 三轮变异复证的产出，后人改此块时勿退回）

**计划缺陷七条（照抄会红或带洞）：**

1. `danbooruRatingCodingIsAlsoAccepted` 只注了一个 `name()="safebooru"` 的 mock 却查 `"danbooru-safe"` → `resolveProvider` 必抛「未知 booru 源」。正解：用例内按生产形态重建双源 service。
2. Step 1 实有 8 个 @Test，Step 4 写「7/7 PASS」。
3. `preview`/`ingest` 未做 rating 复核，与 spec §4.2 L140「搜索/预览代理/下载**全部**校验」+ 验收矩阵 L196「三入口」冲突。正解：三入口共用 `isSafeRating`，且闸在 `guardedDownload` **之前**（用 `verifyNoInteractions(http, storage)` 钉住零下载零入库）。
4. `fileUrl.substring(lastIndexOf('.'))` 在无扩展名 URL（donmai `/data/<md5>` 历史帖形态）下会把含 `/` 的域名后缀串当扩展名拼进存储文件名。正解：`extensionOf()` 先剔 query、只认最后一段路径内的后缀、再用 `\.[a-z0-9]{1,5}` 白名单否则回落 `.jpg`。
5. 全文未提 `MediaAsset` 列宽（name=200/tags=500/sourceNote=500）与远端 tag 串长度（donmai `tag_string` 常见 300-800 字符）的冲突：不截断则 `save` 抛 `DataIntegrityViolationException`，被 controller 归成 400 并把**含 SQL 与列名的 Hibernate 原文**回给前端。正解：`limit(value, max)` 先折叠空白再截断；sourceNote 段序改 `URL | rating= | tags=`（长 tag 被截也不丢出处与分级）；`name` 回落用 `"booru-"+postId` 不取整串 tag；`save` 失败要 `storageService.delete(fileId)` 补偿孤儿文件。
6. 未提 20MB 检查与缓冲次序：`ofByteArray()` 会把整个响应体先读进堆才比长度。正解：`ofInputStream()` + `readCapped(InputStream, max)` 边读边计数。
7. **审查者自己给的修法不可实现**：JDK 17 的 `HttpRequest.Builder` 没有 `limit(long)` 也没有 `maxBodySize(long)`（`javap` 核实：只有 uri/expectContinue/version/header/headers/timeout/setHeader/GET/POST/PUT/DELETE/method/build/copy）。只有流式读取这一条路。

**新增的跨 Task 契约（Task 12 前端必须知道）：**

- `BooruSearchService.RejectException`（public static nested）= 策略拒绝（白名单/分级/category/未知源/非 200/非 image/超限/未登录）。controller 分流：**RejectException → 400 + 原文 message**（面向用户可行动），**其余 RuntimeException → 500 + 固定文案「素材取用失败，请稍后重试或更换关键词」**（内部故障细节只进日志）。这比存量 `MediaAssetController`（一律 400 + 透传 message）**严一档**，是有意偏离。
- 因此 Task 12 的前端 `fetch` **不能只当 2xx 成功**：得先 `resp.json()` 再读 `success/message`，否则 500 的固定文案会被吞成「未知错误」。
- `requery` 里 `resolveProvider` 必须在 try **外**：否则 provider 写错时 `/search` 是 400、`/ingest` 与 `/preview` 被包成普通 RuntimeException 变 500（三入口不一致）。
- `sanitizeTags` 必须把 `+` 当**分隔符**而不是非法字符删掉：system prompt 要求的输出形态就是 `1girl+hatsune_miku`，删 `+` 会合并成假 tag、检索恒空（复审 N-1 实为回归）。分隔符类还必须与 `limit()` 同口径 `[\\p{Cntrl}\\p{Zs}\\s+]+`：否则 U+00A0/U+3000 会先被后面的字符白名单删掉，同样粘连成假 tag（同一失效机制、不同触发字符）。
- `preview` 仍硬编码 `IMAGE_JPEG`（改返回类型会动计划声明的产出签名），留给 Task 12 一并处理。
- **HTTP 边界两例（复审 S-15）留 Task 12 用 MockMvc 补**：`POST /ingest` 缺/非数字 `postId` 现由 controller 的 `Long.parseLong(String.valueOf(...))` 抛 NumberFormatException → 归 500 + 固定文案（客户端错误被当服务端故障）；`GET /preview` 缺 `provider` → Spring 400（空体）。这两条只在 HTTP 层可见，Task 11 的 24 例 service 单测覆盖不到。
- `BooruPost` 契约（Task 10）无 author 字段 → sourceNote 无法满足 spec L144「含作者/source」；属跨 Task 契约缺口，记给 Task 14 统一处理。

### Task 12: 前端素材库面板 + gated booru 冒烟

**Files:**
- Modify: `src/main/resources/templates/video-studio.html`（顶栏按钮 + 素材库面板 div + 新函数块）
- Test: `service/BooruRealSmokeTest.java`

**Interfaces:**
- Consumes: Task 1/2 上传/扫描端点（category 参数）；Task 11 三端点；页面现有 `post/get/esc/toast` 工具函数（执行时 grep 核实实际名称，以存量为准）
- Produces: 面板三入口（上传带 category / 扫描参考目录 / 网搜）；全局 `openAssetPanel()/loadAssetPanel()/uploadRefAsset()/scanRefDir()/booruSearch()/renderBooruGrid()/booruIngestSelected()`；gated 冒烟 `-Dbooru=true`

- [ ] **Step 1: 面板 HTML + JS**

顶栏（脚本选择器同行工具区）加按钮：`<button class="btn btn-sm btn-ghost" onclick="openAssetPanel()">素材库</button>`。
主内容区末尾加面板（默认隐藏；Track B INSERT 管理留 commentary-studio 不动，本面板仅 CHARACTER/SCENE）：

```html
<div id="assetPanel" style="display:none;margin-top:12px;padding:12px;border:1px solid #2a3441;border-radius:8px">
  <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
    <strong>参考素材库</strong>
    <select id="assetCategoryFilter" onchange="loadAssetPanel()">
      <option value="">全部</option><option value="CHARACTER">人物</option><option value="SCENE">场景</option>
    </select>
    <input type="file" id="refAssetFile" accept="image/*" style="display:none" onchange="uploadRefAsset()">
    <select id="refAssetUploadCategory"><option value="CHARACTER">人物</option><option value="SCENE">场景</option></select>
    <button class="btn btn-sm" onclick="document.getElementById('refAssetFile').click()">上传图片</button>
    <button class="btn btn-sm btn-ghost" onclick="scanRefDir()">扫描参考目录</button>
    <input id="booruQuery" placeholder="角色名/场景（英文 tag 佳，中文自动转）" style="width:220px">
    <select id="booruProvider"><option value="safebooru">safebooru.org</option><option value="danbooru-safe">danbooru safe</option></select>
    <button class="btn btn-sm" onclick="booruSearch(0)">网搜</button>
    <button class="btn btn-sm btn-ghost" onclick="document.getElementById('assetPanel').style.display='none'">收起</button>
  </div>
  <div id="assetGrid" style="display:flex;flex-wrap:wrap;gap:8px;margin-top:10px"></div>
  <div id="booruResults" style="margin-top:10px"></div>
</div>
```

JS 函数块（插在 Task 5 素材匹配块后；主 script 块 `th:inline="none"` 保持）：

```javascript
// ==================== 素材库面板（spec §4.3：上传/扫描/网搜三入口） ====================
let booruSelected = {};   // postId -> post

function openAssetPanel() {
    document.getElementById('assetPanel').style.display = 'block';
    loadAssetPanel();
}

async function loadAssetPanel() {
    const cat = document.getElementById('assetCategoryFilter').value;
    const data = await get('/api/video/assets' + (cat ? '?category=' + cat : ''));
    const grid = document.getElementById('assetGrid');
    if (!data.success) { grid.innerHTML = '<span style="color:#f74a4a">' + esc(data.message || '加载失败') + '</span>'; return; }
    const assets = (data.data || []).filter(a => a.kind === 'IMAGE' && (a.category === 'CHARACTER' || a.category === 'SCENE'));
    grid.innerHTML = assets.length ? assets.map(a =>
        '<div style="width:120px;text-align:center">' +
        '<img src="' + esc(a.url) + '" style="width:120px;height:90px;object-fit:cover;border-radius:6px">' +
        '<div style="font-size:11px">' + (a.category === 'CHARACTER' ? '人物' : '场景') + '·' + esc(a.name) + '</div>' +
        '<a href="javascript:void(0)" onclick="deleteRefAsset(' + a.id + ')" style="font-size:11px;color:#f74a4a">删除</a></div>'
    ).join('') : '<span style="color:#8a97a8">库空：上传/扫描/网搜入库</span>';
}

async function uploadRefAsset() {
    const input = document.getElementById('refAssetFile');
    if (!input.files.length) return;
    const fd = new FormData();
    fd.append('file', input.files[0]);
    fd.append('name', input.files[0].name.replace(/\.[^.]+$/, ''));
    fd.append('category', document.getElementById('refAssetUploadCategory').value);
    const resp = await fetch('/api/video/assets', { method: 'POST', body: fd });
    const data = await resp.json();
    toast(data.success ? '上传入库成功' : (data.message || '上传失败'), data.success ? 'success' : 'error');
    input.value = '';
    loadAssetPanel();
}

async function scanRefDir() {
    const data = await post('/api/video/assets/scan-reference', {});
    toast(data.success ? '扫描入库 ' + (data.data || []).length + ' 个' : (data.message || '扫描失败'), data.success ? 'success' : 'error');
    loadAssetPanel();
}

async function deleteRefAsset(id) {
    const data = await fetch('/api/video/assets/' + id, { method: 'DELETE' }).then(r => r.json());
    toast(data.success ? '已删除' : (data.message || '删除失败'), data.success ? 'success' : 'error');
    loadAssetPanel();
}

async function booruSearch(page) {
    const q = document.getElementById('booruQuery').value.trim();
    if (!q) { toast('请输入搜索词', 'error'); return; }
    const provider = document.getElementById('booruProvider').value;
    toast('正在搜索 ' + provider + ' ...', '');
    const data = await get('/api/video/assets/booru/search?q=' + encodeURIComponent(q) + '&provider=' + provider + '&page=' + page);
    const box = document.getElementById('booruResults');
    if (!data.success) { box.innerHTML = '<span style="color:#f74a4a">' + esc(data.message || '搜索失败') + '</span>'; return; }
    const posts = data.data || [];
    booruSelected = {};
    if (!posts.length) { box.innerHTML = '<span style="color:#8a97a8">无结果，试试英文 tag（如 hatsune_miku）</span>'; return; }
    box.innerHTML = '<div style="display:flex;flex-wrap:wrap;gap:8px">' + posts.map(p =>
        '<label style="width:140px;text-align:center;cursor:pointer">' +
        '<input type="checkbox" onchange="toggleBooruSelect(' + p.postId + ', this.checked)"> ' +
        '<img src="/api/video/assets/booru/preview?provider=' + encodeURIComponent(p.provider) + '&postId=' + p.postId + '" style="width:140px;height:105px;object-fit:cover;border-radius:6px" loading="lazy">' +
        '<div style="font-size:10px;color:#8a97a8">' + p.width + '×' + p.height + ' · <span style="color:#2ecc71">safe</span></div>' +
        '<div style="font-size:10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="' + esc(p.tags) + '">' + esc(p.tags) + '</div></label>'
    ).join('') + '</div>' +
    '<div style="margin-top:8px;display:flex;gap:8px;align-items:center">' +
    '<select id="booruIngestCategory"><option value="CHARACTER">人物</option><option value="SCENE">场景</option></select>' +
    '<input id="booruIngestName" placeholder="素材名（缺省用搜索词）" style="width:160px">' +
    '<button class="btn btn-sm" onclick="booruIngestSelected()">入库选中</button>' +
    (page < 4 ? '<button class="btn btn-sm btn-ghost" onclick="booruSearch(' + (page + 1) + ')">下一页</button>' : '') +
    '</div>';
    window._booruPosts = posts;
}

function toggleBooruSelect(postId, checked) {
    const post = (window._booruPosts || []).find(p => p.postId === postId);
    if (checked && post) booruSelected[postId] = post; else delete booruSelected[postId];
}

async function booruIngestSelected() {
    const posts = Object.values(booruSelected);
    if (!posts.length) { toast('请先勾选图片（每张必须人工确认）', 'error'); return; }
    const category = document.getElementById('booruIngestCategory').value;
    const name = document.getElementById('booruIngestName').value.trim() || document.getElementById('booruQuery').value.trim();
    let ok = 0;
    for (const p of posts) {
        const data = await post('/api/video/assets/booru/ingest', { provider: p.provider, postId: p.postId, category, name });
        if (data.success) ok++; else toast(data.message || '入库失败', 'error');
    }
    toast('入库 ' + ok + '/' + posts.length + ' 张', ok ? 'success' : 'error');
    booruSelected = {};
    loadAssetPanel();
}
```

执行时核实：页面现有 HTTP 工具函数名（get/post 或 apiGet/apiPost）与 DELETE 端点形态（MediaAssetController.delete），不一致则按存量 idiom 适配；上传端点参数名以 Task 1 控制器为准。

- [ ] **Step 2: gated booru 真实冒烟**

> 前置核验（Task 10 复审附项，非代码缺陷）：本计划与 spec §4.2 闸 1 都假定「两源图片 URL 均在各自同域、无额外 CDN」，因此 `allowed-hosts` 只列两域，且 `DanbooruSafeProvider.toPost` 会丢弃不以 `https://safebooru.donmai.us/` 开头的 file_url。现代 donmai 系实例普遍把图片放在 **cdn.donmai.us** 子域；若真实 `posts.json` 返该域，备源会**不报错而整源落空**。执行本 Task 时先对 danbooru-safe 打一发真实 `posts.json`（或在面板里选 danbooru 源搜一次）核对 `file_url` 实际 host；若为 cdn.donmai.us 则同步放宽 `video.assets.booru.allowed-hosts` 并将 S-2 早筛改为同注册域（donmai.us 后缀）判定，两侧必同步改，只改一侧等于没改。

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** S4 booru 真实网络冒烟（默认跳过；-Dbooru=true 执行，需外网可达） */
@SpringBootTest
class BooruRealSmokeTest {

    @Autowired BooruSearchService service;
    @Autowired UserRepository userRepository;

    @Test
    void searchAndIngestRealPost() {
        assumeTrue(Boolean.getBoolean("booru"), "真实冒烟需 -Dbooru=true");
        User u = new User();
        u.setUsername("booru_smoke_" + System.currentTimeMillis() % 100000);
        u.setPassword("smoke-not-for-login");
        userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getUsername(), null, List.of()));
        try {
            var posts = service.search("hatsune_miku", "safebooru", 0);
            assumeTrue(!posts.isEmpty(), "safebooru 无结果（网络/站点变化），跳过");
            assertTrue(posts.size() <= 20);
            posts.forEach(p -> {
                assertTrue("safe".equals(p.rating()) || "g".equals(p.rating()));
                assertTrue(p.fileUrl().startsWith("https://safebooru.org/"));
            });
            MediaAsset a = service.ingest("safebooru", posts.get(0).postId(), "CHARACTER", "初音冒烟", null);
            assertNotNull(a.getFileId());
            assertEquals("IMAGE", a.getKind());
            assertTrue(a.getSourceNote().contains("safebooru.org/index.php?page=post"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
```

- [ ] **Step 3: 浏览器验证**

启动应用（`--auth.captcha.debug-text=true`），chrome-devtools MCP：注册→开素材库面板→上传一张图（选 CHARACTER）→网格出现→网搜 hatsune_miku→缩略图经代理加载→勾选 1 张→入库→网格刷新出现新素材。主 script 块 `th:inline="none"` 保持。

- [ ] **Step 4: 跑回归 + 暂存 + S4 切片门槛**

```powershell
mvn test -Dtest=BooruRealSmokeTest -q
git add src/main/resources/templates/video-studio.html src/test/java/com/github/ylyan2015/springaidemo/service/BooruRealSmokeTest.java
mvn clean package -q
```

Expected: 冒烟 SKIPPED（无 -Dbooru）；BUILD SUCCESS（S4 完，进 S5）；外网可用时补跑 `mvn test -Dtest=BooruRealSmokeTest -Dbooru=true -q` 并记录结果

#### Task 12 执行时实测修正（2026-11-18，浏览器实跑 + 五轮变异复证的产出，后人改此块时勿退回）

**前置核验结论（本 Task Step 2 开头那段）——成立，且必须三处同步而不是两处：**

1. donmai 系现代实例的图片确实不在 API 同域，而在 **cdn.donmai.us**（`/original/xx/xx/<md5>.<ext>` 与 `/sample/...`）——三处独立来源 + 实跑证实。
2. 三处而非两处：`application.yml` 的 `allowed-hosts`、`DanbooruSafeProvider.toPost` 的早筛、以及 `BooruSearchService` 构造器里那个 `@Value` **默认值**。第三个副本以前没人验——现在有一条反射读 `@Value` 串并与 yml 逐项比对用它（`ctorValueDefaultListsAtLeastWhatYamlLists`）。
3. 早筛判据选「**https + 注册域后缀**」（`host.equals("donmai.us") || host.endsWith(".donmai.us")`），而不是「域名字符串包含 donmai.us」：后者会把 `cdn.donmai.us.evil.example` 放进来。用例里三种伪装形态（后缀拼接 / 前缀拼接 / 明文 http）都须仍被拒。
4. 每条侧面都有专测钉住，只改一侧不会全绿：`danbooruCdnSubdomainFileUrlSurvivesWhileForeignHostStillDropped`（适配器）/ `yamlAllowedHostsActuallyAcceptTheCdnUrlsProvidersEmit`（把 yml 原串喂给真实构造器跑一次 search）/ `ctorValueDefault...`（@Value 副本）。

**计划缺陷六条（照抄会红或带洞）：**

1. **页面 HTTP 工具函数不叫 `get`**：存量 idiom 是 `api(url, options)` + `post/put`，DELETE 也走 `api(url, {method:'DELETE'})`。计划 JS 里的 `get(...)` 未定义，照抄即整块函数报错（只有浏览器验证能暴露，面板 HTML 本身渲染正常）。
2. **multipart 不能走 `api()`**：它固定 `Content-Type: application/json`，带 FormData 时边界会丢。上传入口必须直接 `fetch(url, {method:'POST', body: fd})` 并自接异常（无 `data.success` 可取时退化到手拼的 message）。
3. **rating 词表假设错（重大）**：spec/计划写 `safebooru.org = safe`，实测 dapi 返 **`general`**（长式），donmai 返 `g`。只认 `{safe, g}` 时主源表现为「搜索成功、空列表」而不是报错——这是整源落空的第二个独立成因（第一个是上面的 cdn 域）。正解：`SAFE_RATINGS = {safe, g, general}`；夹具必须用真实响应形态（`directory` 是**数字**不是 `"aa"`、`file_url` 字段本来就存在）。
4. **计划 JS 的勾选态不会自动清除**：`booruSelected = {}` 后 DOM 里的复选框看上去仍选中，再点「入库选中」会报「请先勾选」。正解：入库后显式把 `#booruResults input[type=checkbox]` 置 `checked = false`。
5. **分页只有「下一页」**：无「上一页」时用户只能重搜回到第一页。服务端上限是 5 页（`MAX_PAGE=4`，0 基），页码提示要写明「第 N/5 页」免得看成无限制。
6. **`scanRefDir` 的空列表有两种原因**（目录未配置 / 已全部入库），服务端只返成功项。面板必须把空列表说成「无新增：目录未配置或已全部入库」，否则用户看成「扫描坏了」（Task 2 挂账的 S-2 skipped count 在本 Task 以文案形式收口，未改服务层返回型）。

**新增/变更的跨 Task 契约（Task 13/14 需知）：**

- **`esc()` 现在额外转义双引号**（`.replace(/"/g, '&quot;')`）。原计划在 Task 14 才做这条，但素材库把属性上下文的数据源从 LLM 输出换成了第三方图站元数据（`tags`/`sourceNote`），一个 `"` 就能从 `title="…"` 新开 `onmouseover` 属性 → 存储型 XSS，所以前置到本 Task。全页属性上下文（含 Task 5 芯片）一并受益；`commentary-studio.html` 的同名 `esc()` 本来就已经转义双引号，两页现在一致。Task 14 不要再重复动这条。
- controller 入参收口全部在 controller 层，**`BooruSearchService` 的产出签名零改动**：`parsePostId(Object)`（缺失/非法 → `RejectException`，回显截 32 字符）、`text(Object)`（非 String 不再 CCE→500）、`sniffImageType(byte[])`（魔数 **PNG / JPEG / WEBP / GIF8**，之外 `application/octet-stream`，不再谎报 JPEG）。
- **格式清单只有一份**：ingest 侧的 `contentType = switch (ext)` 必须逐个列出嗅探支持的同一批类型（含 `.gif → image/gif`）。只修读侧不修写侧，动图的 contentType 会被写成 JPEG（oss/minio/fastdfs 落进对象元数据；local 存储完全不报错，所以很静）。
- 面板下拉的 `<option value>` 与 Bean 的 `name()` 已有一条**零外网**契约测试（`panelProviderOptionsMatchTheWiredBeanNames`，在 `BooruRealSmokeTest` 里但不受 `-Dbooru` 开关控制）：任一侧改名即红。计划原来那个「真实上下文里跑 search 验源名」的写法已废弃——它会在每次全量测试里打外网。
- **不存在的 postId 现在是 500**（适配器抛普通 RuntimeException「帖子不存在」），属客户端可修正错误被归成服务端故障；改它需要类型化异常或让重查未命中返 null，属跨 Task 契约调整 → 记给 Task 14。
- **上游突发限流现象**（实测，非代码缺陷）：面板一次并发拉 20 张代理缩略图时，safebooru 对其中 3 个 `id:` 重查返 404（被归 500）；顺序重跑同一批全部 200。设计建议（Task 14+）：preview 可改用 search 已回传并已过闸的 `previewUrl`，免得每图一次重查（闸 2 语义必须保留在 ingest 路径）。

**审查 round 1 后的三条硬约束（后人不得逆回）：**

1. **`HttpRequest.timeout(30s)` 不保护响应体**。JDK 语义上它只保到「响应头到达」，`ofInputStream()` 交回以后的 `in.read()` 完全不受它管—— trickle 源（每次几十字节）可以无限占住 Tomcat 工作线程。spec 闸 3 写的「30s 读取」必须自己在读取循环里加墙钟（`readCapped(in, max, deadlineNanos)`）+ 预检 `Content-Length` 才有真实含义。残留（已知、不假装修完）：中途彻底不吐字节的硬卡死仍需看门狗线程 `close` 这条流。
2. **魔数嗅探必须逐个列出真实存在的类型**。只处理 PNG/JPEG/WEBP 而把 GIF 落到 `application/octet-stream`，相对原来的硬编码 `image/jpeg` 是**功能回退**（两源都有大量动图帖；原来浏览器还能自己猜出来，现在直接不渲染）。改嗅探类代码时，先枚举该数据源真实会有的格式再动手。
3. **适配器早筛不得比服务层白名单窄**。两道闸的不对称是故意的：适配器 `isDonmaiMediaUrl` 按「同注册域（含根域 donmai.us）」宽筛，服务层按精确 host 列表窄筛并打 WARN。窄的那一道丢人是静默的——站点一旦换域，用户只看到「搜索成功、0 结果」。

---

# S5 脚本增强

### Task 13: 分镜 prompt 画面感升级 + 素材清单注入

**Files:**
- Modify: `video/VideoScriptService.java`（system prompt 升级 + 三参重载）
- Modify: `service/StoryScriptService.java:78-84`（buildAssetCatalog + 三参调用；构造器零改动，mediaAssetRepository 已注入）
- Test: `video/VideoScriptServicePromptTest.java`、`service/StoryScriptServiceCatalogTest.java`

**Interfaces:**
- Consumes: Task 1 category 语义；`MediaAssetRepository.findByUserIdOrderByIdDesc`（id 倒序≈createdAt 倒序）；现有 BeanOutputConverter<Storyboard> 契约**零变化**
- Produces: `VideoScriptService.generateScript(String creativeIdea, String templateGuidance, String assetCatalog)` 三参重载（两参委托 null）；`StoryScriptService` 私有 `buildAssetCatalog(Long userId)`（≤30 条/tags 截 40/行≤60/空库 null/异常降级 null）

- [ ] **Step 1: 写失败测试**

VideoScriptServicePromptTest（deep-stub ChatClient + ArgumentCaptor 捕 system prompt）：

```java
package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoScriptServicePromptTest {

    private ChatClient stubClient(String response) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(response);
        return client;
    }

    private static final String MINIMAL_JSON = "{\"title\":\"t\",\"theme\":\"th\",\"characters\":[],\"shots\":[]}";

    @Test
    void catalogInjectedBetweenBaseAndGuidance() {
        ModelService ms = mock(ModelService.class);
        ChatClient client = stubClient(MINIMAL_JSON);
        when(ms.getChatClient()).thenReturn(client);
        VideoScriptService service = new VideoScriptService(ms, mock(ImageService.class));
        service.generateScript("创意", "模板引导XYZ", "[人物] 林晚：黑长直,红大衣\n");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(client.prompt()).system(cap.capture());
        String sys = cap.getValue();
        assertTrue(sys.contains("可用素材库"));
        assertTrue(sys.contains("[人物] 林晚：黑长直,红大衣"));
        assertTrue(sys.indexOf("可用素材库") < sys.indexOf("模板引导XYZ")); // 拼接顺序：基础+清单+guidance
        assertTrue(sys.contains("光影"));       // 画面感四要素
        assertTrue(sys.contains("beautiful scene")); // 反空泛禁令
    }

    @Test
    void nullCatalogInjectsNothing() {
        ModelService ms = mock(ModelService.class);
        ChatClient client = stubClient(MINIMAL_JSON);
        when(ms.getChatClient()).thenReturn(client);
        new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null, null);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(client.prompt()).system(cap.capture());
        assertFalse(cap.getValue().contains("可用素材库"));
    }

    @Test
    void twoArgOverloadStillWorks() {
        ModelService ms = mock(ModelService.class);
        when(ms.getChatClient()).thenReturn(stubClient(MINIMAL_JSON));
        assertNotNull(new VideoScriptService(ms, mock(ImageService.class)).generateScript("创意", null));
    }
}
```

StoryScriptServiceCatalogTest（mock 仓库 + 捕三参调用）：

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceCatalogTest {

    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    VideoScriptService videoScriptService = mock(VideoScriptService.class);
    StoryScriptService service;

    @BeforeEach
    void setUp() {
        UserRepository userRepo = mock(UserRepository.class);
        service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class), userRepo,
                videoScriptService, mock(StoryCharacterRepository.class), assetRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        Storyboard sb = new Storyboard(); sb.setTitle("t"); sb.setTheme("th");
        when(videoScriptService.generateScript(anyString(), any(), any())).thenReturn(sb);
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MediaAsset asset(long id, String name, String category, String tags) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(1L); a.setKind("IMAGE");
        a.setName(name); a.setCategory(category); a.setTags(tags);
        return a;
    }

    private String capturedCatalog() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(videoScriptService).generateScript(eq("创意"), isNull(), cap.capture());
        return cap.getValue();
    }

    @Test
    void catalogCappedAt30AndTruncated() {
        List<MediaAsset> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(asset(i, "角色" + i, "CHARACTER", "标签非常非常非常非常非常非常非常非常非常非常非常非常长"));
        }
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(many);
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertEquals(30, catalog.lines().count());
        catalog.lines().forEach(l -> assertTrue(l.length() <= 60));
    }

    @Test
    void insertAssetsExcludedAndEmptyLibraryInjectsNull() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(1, "截图", null, "ep01")));
        service.generateAndSaveScript("创意");
        assertNull(capturedCatalog());
    }

    @Test
    void repoFailureDegradesToNullWithoutBlocking() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenThrow(new RuntimeException("DB 挂了"));
        assertNotNull(service.generateAndSaveScript("创意"));
        assertNull(capturedCatalog());
    }

    @Test
    void characterAndSceneFormatted() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(1, "林晚", "CHARACTER", "黑长直,红大衣"), asset(2, "雨巷", "SCENE", "黄昏,霓虹")));
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertTrue(catalog.contains("[人物] 林晚：黑长直,红大衣"));
        assertTrue(catalog.contains("[场景] 雨巷：黄昏,霓虹"));
    }
}
```

已核实：Storyboard 有无参构造 + setTitle/setTheme（Storyboard.java L13-19），shots=null 分支 generateAndSaveScript 已兼容。

- [ ] **Step 2: 跑红**

Run: `mvn test -Dtest=VideoScriptServicePromptTest,StoryScriptServiceCatalogTest -q`
Expected: 编译失败「找不到三参 generateScript」

- [ ] **Step 3: 实现 VideoScriptService**

两参 generateScript 改委托 `generateScript(creativeIdea, templateGuidance, null)`；新三参方法体 = 原方法体，systemPrompt 升级为（spec §5.1 全文）：

```java
        String systemPrompt = """
                你是一位专业的短视频导演和编剧，擅长将创意概念转化为引人入胜、画面感强烈的分镜脚本。

                请根据用户的创意想法，生成一个包含4-6个镜头的短视频分镜脚本。

                要求：
                0. 先人设后分镜：characters 数组列出全部出场角色（name 中文角色名；featureTable 英文固定特征表：发型/瞳色/服装/体型，跨镜复用锁定身份；personality 性格关键词；suggestedVoice 可选 edge-tts 声线名）
                1. 标题简洁有力，能概括视频主题
                2. 主题描述一句话概括视频核心思想
                3. 每个镜头包含：
                   - order: 镜头顺序（从1开始）
                   - sceneDescription: 画面内容描述（中文，四要素缺一不可：①主体——谁/什么+外观服装状态 ②动作 ③具体背景环境 ④光影色调；情绪通过表情/肢体表达，不写抽象形容词）
                   - cameraMovement: 镜头运动方式（如：固定、推进、拉远、平移、俯拍、仰拍等）
                   - durationSeconds: 镜头时长（3-8秒）
                   - narration: 旁白文字（中文，字数 ≤ round(2.5 × durationSeconds)，配合画面的解说或台词）
                   - imagePrompt: 用于AI图像生成的提示词（必须用英文，固化公式：subject + action + environment details + lighting + color palette + composition + art style；环境细节至少 2 个具体物件）
                   - characterNames: 本镜出场角色名数组（必须与 characters 中的 name 一致）
                   - shotSize: 景别（CLOSE/MEDIUM/LONG）
                   - dialogue: 本镜角色对白（中文，无对白为空串；与旁白分离，不要重复旁白内容）
                   - dialogueCharacterName: 对白角色名（必须与 characters 中的 name 一致，无对白为空串）

                画面感禁令：
                - 禁止空泛描述（beautiful scene / nice view / 温馨的画面 等），一律落到具体物件、光线、色彩
                - 除尾卡外禁止无背景纯色镜头；同一场戏角色服装状态保持一致

                范例镜（高质量标杆）：
                sceneDescription: 林晚（黑长直、红色大衣被雨水打湿）蹲在巷口逗一只橘猫，身后是霓虹招牌林立的窄巷，青石板路面积水倒映着暖黄与品红的灯光，雨丝在逆光中发亮。
                imagePrompt: 1girl, black long hair, wet red coat, crouching, feeding an orange cat, narrow alley with neon signs, wet cobblestone reflections, warm yellow and magenta lights, backlit raindrops, dusk, cinematic composition, anime coloring, best quality

                注意：imagePrompt 是喂给图像生成模型的，必须是高质量的英文视觉描述，不要包含文字/对白内容。
                每个镜头的 imagePrompt 应保持视觉风格一致。
                """;

        // 素材清单注入（spec §5.2）：基础 prompt + 清单 + templateGuidance（模块 H 共存）
        if (assetCatalog != null && !assetCatalog.isBlank()) {
            systemPrompt = systemPrompt + """

                    可用素材库（用户已备参考图，优先使用）：
                    """ + assetCatalog + """
                    指令：优先用库内人物当主角（角色名与库一致）、库内场景当背景；库不够时可原创补充。
                    """;
        }
        if (templateGuidance != null && !templateGuidance.isBlank()) {
            systemPrompt = systemPrompt + "\n" + templateGuidance;
        }
```

其余（userMessage/converter/调用/日志）逐字不变。

- [ ] **Step 4: 实现 StoryScriptService.buildAssetCatalog**

`generateAndSaveScript(idea, templateGuidance)` 中 L84 改为：

```java
        String assetCatalog = buildAssetCatalog(userId);
        Storyboard storyboard = videoScriptService.generateScript(idea, templateGuidance, assetCatalog);
```

私有方法（spec §5.2 token 三道闸）：

```java
    /** 素材清单注入（spec §5.2）：≤30 条（id 倒序≈createdAt 倒序）/tags 截 40/行≤60；空库/异常返 null 零注入 */
    private String buildAssetCatalog(Long userId) {
        try {
            List<MediaAsset> assets = mediaAssetRepository.findByUserIdOrderByIdDesc(userId).stream()
                    .filter(a -> "IMAGE".equals(a.getKind())
                            && ("CHARACTER".equals(a.effectiveCategory()) || "SCENE".equals(a.effectiveCategory())))
                    .limit(30)
                    .toList();
            if (assets.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (MediaAsset a : assets) {
                String tags = a.getTags() == null ? "" : a.getTags();
                if (tags.length() > 40) {
                    tags = tags.substring(0, 40);
                }
                String line = ("CHARACTER".equals(a.effectiveCategory()) ? "[人物] " : "[场景] ")
                        + a.getName() + "：" + tags;
                if (line.length() > 60) {
                    line = line.substring(0, 60);
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("素材清单加载失败，降级无清单模式: {}", e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 5: 跑绿 + 存量回归**

Run: `mvn test -Dtest=VideoScriptServicePromptTest,StoryScriptServiceCatalogTest,StoryScriptService*Test,VideoScriptService*Test -q`
Expected: 新 7 绿 + 存量全绿（两参重载兼容，VideoTemplateService/VideoAgentToolFactory 调用点零改动）

- [ ] **Step 6: 暂存（不提交）**

```powershell
git add src/main/java/com/github/ylyan2015/springaidemo/video/VideoScriptService.java src/main/java/com/github/ylyan2015/springaidemo/service/StoryScriptService.java src/test/java/com/github/ylyan2015/springaidemo/video/VideoScriptServicePromptTest.java src/test/java/com/github/ylyan2015/springaidemo/service/StoryScriptServiceCatalogTest.java
```

#### Task 13 执行时实测修正（2026-11-18，先红后绿 + 22 条变异复证 + 两轮审查的产出，后人改此块时勿退回）

**计划缺陷四条（照抄会红或带洞）：**

1. **Step 1 的 `stubClient` 根本跑不起来**：`RETURNS_DEEP_STUBS` + `verify(client.prompt()).system(cap.capture())` 会把深桩中间层的 stub 调用计入 verify（TooManyActualInvocations），而 `when(ms.getChatClient()).thenReturn(stubClient(...))` 是嵌套 stubbing（UnfinishedStubbingException）。仓内已有正确 idiom：`AssetMatchServiceTest.stubLlm`（Task 4 审查就记过一次同一坑）——分层 mock `ChatClientRequestSpec`/`CallResponseSpec`，返回 spec 供 verify。
2. **Step 5 的「存量全绿」遗漏三个存量测试的 stub**：mock 的方法签名从 2 参变 3 参后，`StoryScriptServiceCharactersLinkTest`（两处）与 `StoryScriptServiceDialogueLinkTest`（一处）的 `generateScript("idea", null)` 不再命中→ storyboard 为 null→NPE。生产侧调用点确实零改动（全仓只有 `generateAndSaveScript` 走三参），但测试 stub 必须同步签名。
3. **Step 1 的 `forEach(l -> l.length() <= 60)` 是恒真断言**（该载荷行长 36 字符，删掉截断也不红）；30 条上限只断言行数、不钉「入选的是哪 30 条」。→ 新增 `longNameLineCutToExactly60Chars`、`tagsTruncatedTo40Chars`（含 `assertEquals(48, len)`）、`startsWith(角色0)` + 不含角色30/39 的入选身份断言。
4. **Step 4 的 `substring(0, 40|60)` 会把代理对劈成半个字符喂进模型**（UTF-16 code unit 下刀），且 `a.getName()` 未净化：null 拼出字面 `"null"`、名/标签里的 `\n` 伪造多一行（30 行上限按 `lines().count()` 算）。→ 抽 `truncate(value, max)`（`charAt(max)` 为低代理时回退一位）与 `oneLine(value)`（ `[\p{Cntrl}\p{Zs}\s]+` 折叠，与 `BooruSearchService.limit` 同口径）。

**跨 Task 契约 / Ruling：**

1. **Ruling**：参考图谓词本轮动手前已有 **4 份手写副本**（`AssetMatchService`、`StoryScriptService.updateShot`、`buildAssetCatalog`、`ReferenceImageResolver`）。抽出 `MediaAsset.isReferenceAsset()` 四处共用：spec §2.1 与 §5.2 的闭环前提就是同一谓词，副本漂移后 prompt 里承诺的角色可能在匹配阶段被另一套口径排除且无人能测到。变异 AE（去掉 IMAGE 约束）同时打红清单与匹配用例，钉的就是「口径同源」。
2. `buildAssetCatalog` 的 `catch (Exception)` 同时也吞 `mediaAssetRepository == null` 的 NPE（存量测试构造路径）；`getCurrentUserId()` 的「用户未登录」在 try 之外上抛，401 语义不会被降级吞掉（复审 3f 已逐行核）。`log.warn` 必带异常对象（对齐 `AssetMatchService` 审查 M-5 口径）。
3. 截断次序是确定行为不是丢数据：先截 tags（40）→ 拼行 → 再按 60 砍整行；长名场景下行闸会再裁掉 tags 尾巴（行宽是硬上限）。

**审查与复证计数**：round 1 = 0 Critical / 2 Important / 5 Minor（文本无断言、代理对截断、恒真 forEach、入选身份未钉、降级无栈、谓词四副本、null repo 无显式用例）→ fix round 1 全修 → 复审 round 2 **CLOSED**，另抓两条：Important-A（`length == codePoints().count()` 在失败态照样绿——孤立代理在 codePoints() 里也是一个点，换 `assertFalse(isSurrogate(末位))`）与 Minor-B（本轮新行为「名也走 oneLine」无人守 null 名）。最终 22 条变异（R,S,Y,U,W,T,X,Z,AA,AB,AC,AE,AF,B1–B7）全部由「该管的那条」打红；S5 门禁 v4 = **562 tests / 0 failures / 0 errors / 12 skipped / BUILD SUCCESS**。

**FLAKY 记账（非本计划职责）**：门禁 v3 的 `VideoComposeServiceTimeoutTest.hangingProcessIsKilledAfterTimeout` 报 `JUnitException: Failed to close extension context`——Windows 上被 kill 的 cmd 仍持有 temp 目录下 `cmd-*.log` 句柄，`@TempDir` 清理失败（不是断言失败）。单跑 2 次全绿、门禁 v4 全绿。被测类 `VideoComposeService.java` 属并行切片且尚未暂存，不在本 Task 修复范围；后人看到这一红先重跑再怀疑自己的改动。

---

### Task 14: 全链路 e2e 驱动 + 验收收尾

**Files:**
- Create: `tools/e2e-asset-library.js`
- Modify: `docs/phase5-整集试产验收记录.md`（追加素材库验收新节）

**Interfaces:**
- Consumes: 全部前序 Task；已核实端点：`POST /api/video/scripts/generate {idea}`、`POST /api/video/scripts/{sid}/asset-matches`、`PUT /api/video/scripts/{sid}/shots/{shotId}`、`POST /api/video/images/shot/{sid}/{shotId}`、`GET /api/video/images/generators`、`PUT /api/video/images/generator?type=`、`POST /api/video/export/hd`；认证 idiom 逐字镜像 tools/e2e-commentary.js（RSA-OAEP 加密+cookie jar）
- Produces: 全链路验证脚本；验收文档新节（常规回归+Track B e2e+gated 结果+目视对照图路径）

- [ ] **Step 1: 写 e2e 驱动**

```javascript
// 素材库与画面感增强（S1→S5）全链路验证脚本
// 前置：应用已启动 --auth.captcha.debug-text=true；可选 video.assets.reference-import-dir 指向 char_/scene_ 素材目录；
//       ComfyUI 存活且 ipadapter 模板已校准则走真实生图，否则生图/导出段 gate 跳过（数据链路仍全验）
// 用法：node tools/e2e-asset-library.js [baseUrl]
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8080';
let cookie = '';

async function req(method, p, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + p, {
        method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual'
    });
    const setCookie = resp.headers.get('set-cookie');
    if (setCookie) cookie = setCookie.split(';')[0];
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* non-JSON */ }
    return { status: resp.status, json, text };
}

function rsaEncrypt(publicKeyBase64, plain) {
    const der = Buffer.from(publicKeyBase64, 'base64');
    const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    return crypto.publicEncrypt({
        key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256'
    }, Buffer.from(plain, 'utf8')).toString('base64');
}

function check(name, ok, detail) {
    console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''));
    if (!ok) process.exitCode = 1;
}

// 1×1 透明 PNG（上传入库用，不依赖外部文件）
const PNG_1x1 = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

(async () => {
    // 1. 认证（镜像 e2e-commentary.js）
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'e2e_asset_' + Date.now() % 100000;
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 页面渲染：素材库面板 + 匹配 chip 标记
    const page = await req('GET', '/video-studio');
    const markers = ['assetPanel', 'matchAssetsForScript', 'booruSearch', 'scanRefDir'];
    const missing = markers.filter(m => !page.text.includes(m));
    check('video-studio page markers', page.status === 200 && missing.length === 0,
        missing.length ? 'missing=' + missing.join(',') : 'all present');

    // 3. 上传入库（CHARACTER，multipart）
    const fd = new FormData();
    fd.append('file', new Blob([PNG_1x1], { type: 'image/png' }), 'char_林晚_正面全身.png');
    fd.append('name', '林晚');
    fd.append('category', 'CHARACTER');
    fd.append('tags', '黑长直,红大衣');
    const upResp = await fetch(BASE + '/api/video/assets', {
        method: 'POST', headers: { Cookie: cookie }, body: fd
    });
    const up = await upResp.json();
    check('upload CHARACTER asset', upResp.status === 200 && up.success && up.data.category === 'CHARACTER',
        'assetId=' + (up.data && up.data.id));
    const charAsset = up.data;

    // 4. 扫描参考目录（未配置时返回空不报错；配置时验证 char_/scene_ 解析）
    const scan = await req('POST', '/api/video/assets/scan-reference', {});
    check('scan-reference endpoint', scan.status === 200 && scan.json.success,
        'ingested=' + ((scan.json.data || []).length));
    const sceneAsset = (scan.json.data || []).find(a => a.category === 'SCENE');

    // 5. category 过滤：INSERT 存量不混入参考清单
    const list = await req('GET', '/api/video/assets?category=CHARACTER');
    check('list category filter', list.status === 200 && (list.json.data || []).every(a => a.category === 'CHARACTER'),
        'count=' + (list.json.data || []).length);

    // 6. 生成脚本（LLM 真实调用；验证素材清单注入后角色名命中——软断言，LLM 不保证）
    const gen = await req('POST', '/api/video/scripts/generate', {
        idea: '林晚在黄昏雨巷里喂流浪猫的治愈短片，人物背景具体、光影丰富'
    });
    const script = gen.json && gen.json.data;
    check('generate script', gen.status === 200 && script && script.id && (script.shots || []).length > 0,
        'scriptId=' + (script && script.id) + ' shots=' + (script && script.shots && script.shots.length));
    if (!script || !script.id) { console.log(gen.text.slice(0, 500)); return; }
    const usedLibName = JSON.stringify(script).includes('林晚');
    console.log((usedLibName ? 'INFO' : 'WARN') + ' | 脚本是否用了库内角色名林晚（软断言）=' + usedLibName);
    const shot = script.shots[0];

    // 7. 一键匹配 → 推荐不落库 → 确认绑定
    const match = await req('POST', `/api/video/scripts/${script.id}/asset-matches`, {});
    check('asset-matches', match.status === 200 && match.json.success && match.json.data.source,
        'source=' + (match.json.data && match.json.data.source));
    const before = await req('GET', '/api/video/scripts/' + script.id);
    const beforeShot = ((before.json.data || {}).shots || []).find(s => s.id === shot.id);
    check('recommendation not persisted', !beforeShot || !beforeShot.referenceAssetIds
        || beforeShot.referenceAssetIds.length === 0, '推荐阅后即焚');
    const bindIds = [charAsset.id].concat(sceneAsset ? [sceneAsset.id] : []);
    const bind = await req('PUT', `/api/video/scripts/${script.id}/shots/${shot.id}`, { referenceAssetIds: bindIds });
    check('confirm binding', bind.status === 200 && bind.json.success, 'ids=' + bindIds.join(','));
    const after = await req('GET', '/api/video/scripts/' + script.id);
    const afterShot = ((after.json.data || {}).shots || []).find(s => s.id === shot.id);
    const boundIds = (afterShot && afterShot.referenceAssetIds) || [];
    check('binding persisted', boundIds.includes(charAsset.id), 'bound=' + JSON.stringify(boundIds));

    // 8. 生图段（gate：ComfyUI 存活才走真实 IP-Adapter；否则跳过不阻断数据链路验收）
    const gens = await req('GET', '/api/video/images/generators');
    const comfy = ((gens.json && gens.json.data) || []).find(g => g.type === 'comfyui');
    if (comfy && comfy.available) {
        await req('PUT', '/api/video/images/generator?type=comfyui');
        const img = await req('POST', `/api/video/images/shot/${script.id}/${shot.id}`, {});
        check('comfyui image with refs', img.status === 200 && img.json.success
            && img.json.data.imageStatus === 'READY', 'url=' + (img.json.data && img.json.data.imageUrl));
        // 9. 导出段（仅生图成功时；成片归档 uploads/final/ 由后端保障，断言导出成功）
        if (img.json && img.json.success) {
            const exp = await req('POST', '/api/video/export/hd', {
                scriptId: script.id, aspectRatio: '16:9', subtitle: false, withLogo: false
            });
            check('export after refs generation', exp.status === 200 && exp.json.success,
                exp.json && !exp.json.success ? 'msg=' + exp.json.message : '');
        }
    } else {
        console.log('SKIP | comfyui 不可用（未启动/模板未校准），生图+导出段 gate 跳过');
    }

    console.log('DONE | scriptId=' + script.id);
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
```

已核实：脚本详情 `GET /api/video/scripts/{id}`（StoryScriptController L170）；updateShot 为 `PUT /{id}/shots/{shotId}`（L367）；referenceAssetIds 输出已在 Task 5 Step 2 补装配点。

- [ ] **Step 2: 跑 e2e**

前置：启动应用（`--auth.captcha.debug-text=true`，LLM 可用）。
Run: `node tools/e2e-asset-library.js`
Expected: 数据链路段全 PASS；生图/导出段按机器状态 PASS 或 SKIP；无 FAIL

- [ ] **Step 3: Track B 回归硬门槛**

Run: `node tools/e2e-commentary.js`
Expected: 全 PASS（存量行为逐字不变的终极证据）；红即停，修复后重跑

- [ ] **Step 4: 验收文档新节**

docs/phase5-整集试产验收记录.md 追加「素材库与画面感增强（S1→S5）验收（2026-11-17）」节，记录：
- 各切片新增测试数与全量回归结果（mvn clean package 绿）
- Track B e2e 复跑结果
- gated 冒烟执行状态：IpAdapterRealSmokeTest（-Dipadapter=true）结果 + target/ipadapter-smoke/ 目视对照结论；BooruRealSmokeTest（-Dbooru=true）结果；未执行项明示原因（模板未校准/无外网）
- e2e-asset-library.js 输出摘要

- [ ] **Step 5: 暂存（不提交）+ 最终门槛**

```powershell
git add tools/e2e-asset-library.js "docs/phase5-整集试产验收记录.md"
mvn clean package -q
```

Expected: BUILD SUCCESS 全量绿；全部暂存不提交，向用户汇报完成状态与待办（模板校准/真实冒烟若尚未执行）

---


#### Task 14 执行时实测修正（2026-11-18，两轮真实全链路跑通 + 最终全分支审查的产出）

**写脚本前先核实的契约（全部为真，后人改端点/字段时这几条要同步）：**

1. 页面 idiom 实名：`openAssetPanel` / `loadAssetPanel` / `uploadRefAsset` / `scanRefDir` / `deleteRefAsset` / `booruSearch` / `toggleBooruSelect` / `booruIngestSelected`；计划里的 4 个 marker 全部存在，另补 5 个（含 `esc` 的 `&quot;`，面板 JS 被 Thymeleaf 解实体就会在这一条露出来）。
2. `POST /api/video/assets/scan-reference` **无 @RequestBody**——带 `{}` 是冗余但无害；`POST /api/video/assets/scan`（Track B）同理。
3. `PUT /api/video/scripts/{id}/shots/{shotId}` 的 `data` 是 **shot map**，不是 script map；`referenceAssetIds` 在 `data` 顶层且是**数组**（`StoryScriptService.shotToMap` L717 用 `parseCharacterIds` 转出）。按 `data.shots.find(...)` 取会得 `undefined`，于是 `indexOf(伪造id) < 0` **恒真**——本计划第四类自欺在 e2e 里第三次复发。
4. `GET /api/video/images/generators` 的数组项含 `type/name/available/active`；`PUT /generator?type=` 需登录；`POST /shot/{scriptId}/{shotId}` 的 `data.imageStatus` 可用。
5. `POST /api/video/export/hd` 认 `scriptId/resolution/aspectRatio/bgmId/subtitle/withLogo/subtitleLanguage/upscale/interpolate/sfx`。
6. 认证 idiom（镜像 `tools/e2e-commentary.js`）：`/api/auth/public-key` → RSA-OAEP(SHA-256) 加密密码 → `/api/auth/register`（需 `--auth.captcha.debug-text=true` 才回明文验证码），cookie jar 手工保存 `set-cookie` 首段。

**计划脚本缺陷两条（照抄必红）：**

1. **只给第一镜生图就调导出必红**：导出闸要求**全镜 READY**，`msg=以下镜头尚未就绪（需生成图片或绑定视频素材）: 镜头 2…5`。正解是逐镜循环 `POST /images/shot/...`（顺带得到天然对照：镜 1 带绑定参考图走 IP-Adapter，其余镜同链路无参考图）。
2. **伪造 id 剔除的断言路径写错会得到恒真**（见上面契约 3）。

**Track B 回归硬门槛的前置条件**：`e2e-commentary.js` 的 `scan ingest` 需要 `video.commentary.import-dir` 指向含 `epNN_标签_名称.mp4/png` 的目录；默认 yml 是空串 → 该步必红（属前置条件缺失，不是存量回归）。本轮在 gitignore 的工作区目录临时造 1 mp4 + 1 png 后 **12 项全 PASS**。

**最终全分支审查（staged vs `1963d2a`，57 files / +8485 / −79）= APPROVED，0 Critical / 0 Important / 1 Minor**：六条跨切片不变量（SSRF 红线经 `setUrl(` 全库穷举、三入口闸口径、谓词单一口径 4 调用点、Track B 逐字不变、prompt 注入双端口径、测试判别力）五条完全成立、第六条抓到一处装饰性 `verifyNoInteractions(storage)`（preview 路径从不引用该 bean）→ 已改为断言异常消息 `startsWith("下载响应异常")`，并用变异（删掉「非 200 一律拒」的 throw）复证该用例独红、恢复无残留。审查者另给一条流程建议：CI 增加 nightly gated job（`-Dbooru=true -Dipadapter=true`），否则真实链路证据会随时间失效。

**Ruling - SDD 工作区不删**。技能约定「最终审查通过即删本计划工作目录」，但本分支的产出至今**只暂存未提交**（计划 Step 5 明文要求），`progress.md` 台账是唯一能穿过上下文压缩的完成记录与决策档案；删它等于删账本。留待用户提交后一并清理。

**FLAKY 复现率实测**：`VideoComposeServiceTimeoutTest.hangingProcessIsKilledAfterTimeout` 在本机 6 次全量门禁中红 3 次（同一 `@TempDir` 清理失败：被 kill 的 cmd 仍持有 `cmd-*.log` 句柄）；单跑该类 2 次全绿。红时先重跑再怀疑自己的改动。

## 依赖与执行顺序

S1（Task 1-3）→ S2（Task 4-5）→ S3（Task 6-9，Task 9 模板校准由用户并行）→ S4（Task 10-12）→ S5（Task 13-14）。每切片末 `mvn clean package` 全绿才进下一切片；每 Task 末暂存不提交。