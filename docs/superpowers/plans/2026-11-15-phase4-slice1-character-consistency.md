# Phase 4 切片 1：角色卡与图像一致性 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 video-studio 增加角色卡（人设特征表+定妆参考图+声线）与参考图生图能力，使同一角色跨镜头保持形象一致。

**Architecture:** 新增 `StoryCharacter` 实体与 CRUD 服务；脚本生成 LLM 单次输出"先人设后分镜"；`ShotPromptAssembler` 作为唯一 prompt 装配点（风格前缀+角色特征表+镜头描述+景别/运镜）；`ImageGenerator` 以 default 方法扩展参考图能力（现有 6 实现零改动），新增 `Wan27ImageGenerator`（DashScope wan2.7-image，≤9 参考图）经工厂 List 注入自动收录；参考图传递走"公网 URL → base64 data URI → 纯文本降级"回退链。

**Tech Stack:** Java 17 / Spring Boot 3.4 / Spring AI 1.0 / Spring Data JPA（H2 ddl-auto:update）/ java.net.http.HttpClient / Edge TTS 声线枚举 / Thymeleaf 原生 JS 前端。

**Spec:** `docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md` §4（切片 1）、§9（横切红线）、§12（验证约定）。

## Global Constraints

- 分层约定：entity/repository/service/controller；video 子包放 generator/quality/subtitle/agent；新 Bean 空 Key/空配置 = 不可用，干净环境（无 DASHSCOPE_API_KEY）全上下文启动必须绿。
- Key 一律环境变量注入（`DASHSCOPE_API_KEY`），yml git 跟踪禁止写真实 Key。
- 长 LLM 循环禁 `@Transactional`；逐次 `repository.save` 独立事务（沿用 `generateAndSaveScript` 注释 idiom）。
- 归属校验 idiom：`scriptRepository.findByIdAndUserId(scriptId, userId)`；`getCurrentUserId()` 从 SecurityContextHolder 取（匿名抛"用户未登录"）。HTTP 层 `/api/video/**` permitAll 沿用，登录校验在服务层。
- shot Map 装配点：规范源 `StoryScriptService.shotToMap` + 独立副本 `ImageGenerationController.shotToMap` 必须同步新增键；`VideoExportService.toComposeMap` 本切片不消费新字段故不改（显式决策）；`VideoAgentToolFactory` 经 shotToMap 自动获得；前端 `video-studio.html` 同步。
- prompt 装配唯一落点 `ShotPromptAssembler`，禁止多处各自拼接。
- 控制器响应 idiom：`ResponseEntity<Map<String,Object>>`，`success`/`data` 键（沿用 VideoAgentController）；异常抛 RuntimeException 交 GlobalExceptionHandler。
- TDD 先红后绿；每任务结束 `mvn test` 全量绿门槛；**不主动 git commit**（用户全局指示暂不提交）——"Commit" 步骤一律执行为 `git add` 暂存 + 汇报，等待用户提交指令。
- 文档同步：README.md / README-en.md / API_TEST.md / API_TEST-en.md 四份（任务 11）。
- 联网真实冒烟默认跳过：`-Dwan27=true` 开关（`Boolean.getBoolean("wan27")`，沿用 `-Dtts=true` idiom）。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Create | `entity/StoryCharacter.java` | 角色卡实体 |
| Create | `repository/StoryCharacterRepository.java` | 角色卡查询 |
| Create | `service/StoryCharacterService.java` | CRUD/归属/参考图上传/声线表/characterToMap |
| Create | `controller/StoryCharacterController.java` | `/api/video/characters` |
| Create | `video/CharacterDesign.java` | LLM 人设输出 DTO |
| Create | `video/ShotPromptAssembler.java` | 唯一 prompt 装配点 |
| Create | `video/generator/ReferenceImageResolver.java` | 角色→参考图 URL/dataURI 回退链 |
| Create | `video/generator/Wan27ImageGenerator.java` | wan2.7-image 参考图生图 |
| Modify | `entity/StoryScript.java` | +style |
| Modify | `entity/StoryShot.java` | +characterIds/shotSize |
| Modify | `video/Storyboard.java` / `video/Shot.java` | +characters / +characterNames/shotSize |
| Modify | `video/VideoScriptService.java` | System Prompt 人设要求 |
| Modify | `service/StoryScriptService.java` | 人设持久化/关联/Map 新键/updateStyle |
| Modify | `controller/ImageGenerationController.java` | shotToMap 副本新键 |
| Modify | `video/generator/ImageGenerator.java` | default 参考图方法 |
| Modify | `config/ImageGeneratorConfig.java` | wan27 Bean |
| Modify | `service/ImageGenerationService.java` | 装配点替换+参考图接线 |
| Modify | `service/ImagePromptOptimizerService.java` | 批量优化注入角色上下文 |
| Modify | `video/agent/AgentToolRequests.java` / `VideoAgentToolFactory.java` / `SpecialistAgentFactory.java` | generateCharacters 工具 |
| Modify | `resources/application.yml` | image.wan27 配置块 |
| Modify | `templates/video-studio.html` | 角色卡面板+脚本级风格+镜头角色/景别编辑 |
| Create | 测试 8 个（见各任务） | TDD |

---

### Task 1：实体与 Map 装配点扩展

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/entity/StoryCharacter.java`
- Create: `src/main/java/com/github/ylyan2015/springaidemo/repository/StoryCharacterRepository.java`
- Modify: `entity/StoryScript.java`、`entity/StoryShot.java`、`service/StoryScriptService.java`、`controller/ImageGenerationController.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/service/StoryScriptServiceCharacterFieldsTest.java`

**Interfaces:**
- Produces: `StoryCharacter`（字段见下）、`StoryCharacterRepository.findByScriptIdOrderByIdAsc(Long)` / `findByIdAndScriptId(Long,Long)`、`StoryScript.getStyle()/setStyle(String)`、`StoryShot.getCharacterIds()/setCharacterIds(String)` / `getShotSize()/setShotSize(String)`、`StoryScriptService.updateStyle(Long,String)`、shotToMap 新键 `characterIds`/`shotSize`、scriptToMap 新键 `style`/`characters`。

- [ ] **Step 1：写失败测试** `StoryScriptServiceCharacterFieldsTest`

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceCharacterFieldsTest {

    private StoryScriptService newService(StoryCharacterRepository charRepo) {
        return new StoryScriptService(mock(StoryScriptRepository.class), mock(StoryShotRepository.class),
                mock(UserRepository.class), mock(VideoScriptService.class), charRepo);
    }

    @Test
    void shotToMapExposesCharacterIdsAndShotSize() {
        StoryShot shot = new StoryShot();
        shot.setCharacterIds("1,2");
        shot.setShotSize("CLOSE");
        Map<String, Object> map = newService(mock(StoryCharacterRepository.class)).shotToMap(shot);
        assertEquals("1,2", map.get("characterIds"));
        assertEquals("CLOSE", map.get("shotSize"));
    }

    @Test
    void scriptToMapExposesStyleAndCharacters() {
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryCharacter c = new StoryCharacter();
        c.setName("林晚");
        c.setFeatureTable("black long straight hair, amber eyes, white shirt");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(c));
        StoryScript script = new StoryScript();
        script.setId(7L);
        script.setStyle("ANIME");
        Map<String, Object> map = newService(charRepo).scriptToMap(script);
        assertEquals("ANIME", map.get("style"));
        List<?> chars = (List<?>) map.get("characters");
        assertEquals(1, chars.size());
        assertEquals("林晚", ((Map<?, ?>) chars.get(0)).get("name"));
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=StoryScriptServiceCharacterFieldsTest` Expected: 编译失败（构造器 5 参/新 getter 不存在）。

- [ ] **Step 3：实现实体与仓库**

`StoryCharacter.java`（getter/setter 单行 idiom 同 StoryShot）：

```java
package com.github.ylyan2015.springaidemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/** 角色卡：人设特征表 + 定妆参考图 + 声线（Phase 4 切片1） */
@Entity
@Table(name = "story_characters")
public class StoryCharacter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    @JsonIgnore
    private StoryScript script;
    @Column(name = "name", length = 100, nullable = false)
    private String name;
    /** 固定特征表（英文）：发型/瞳色/服装/体型，出图拼 prompt 头部 */
    @Column(name = "feature_table", columnDefinition = "TEXT")
    private String featureTable;
    @Column(name = "personality", length = 500)
    private String personality;
    @Column(name = "reference_image_file_id", length = 200)
    private String referenceImageFileId;
    @Column(name = "reference_image_url", length = 1024)
    private String referenceImageUrl;
    @Column(name = "voice", length = 100)
    private String voice = "zh-CN-XiaoxiaoNeural";
    public StoryCharacter() {}
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public StoryScript getScript() { return script; }
    public void setScript(StoryScript script) { this.script = script; }
    public Long getScriptId() { return script != null ? script.getId() : null; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFeatureTable() { return featureTable; }
    public void setFeatureTable(String featureTable) { this.featureTable = featureTable; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getReferenceImageFileId() { return referenceImageFileId; }
    public void setReferenceImageFileId(String referenceImageFileId) { this.referenceImageFileId = referenceImageFileId; }
    public String getReferenceImageUrl() { return referenceImageUrl; }
    public void setReferenceImageUrl(String referenceImageUrl) { this.referenceImageUrl = referenceImageUrl; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
}
```

`StoryCharacterRepository.java`：

```java
package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StoryCharacterRepository extends JpaRepository<StoryCharacter, Long> {
    List<StoryCharacter> findByScriptIdOrderByIdAsc(Long scriptId);
    Optional<StoryCharacter> findByIdAndScriptId(Long id, Long scriptId);
}
```

`StoryScript` 增列（放在 status 字段后）：`@Column(name = "style", length = 50) private String style;` + 单行 getter/setter。
`StoryShot` 增列（放在 qualityScore 后）：`@Column(name = "character_ids", length = 200) private String characterIds;`、`@Column(name = "shot_size", length = 20) private String shotSize = "MEDIUM";` + 单行 getter/setter。

- [ ] **Step 4：StoryScriptService 扩展** 构造器增第 5 参 `StoryCharacterRepository characterRepository`（存字段）。`shotToMap` 增 `map.put("characterIds", shot.getCharacterIds()); map.put("shotSize", shot.getShotSize());`。`scriptToMap` 增 `map.put("style", script.getStyle()); map.put("characters", characterRepository.findByScriptIdOrderByIdAsc(script.getId()).stream().map(StoryCharacterService::characterToMap).toList());`（characterToMap 静态方法在 Task 2 创建；本任务先在该 service 内建私有静态 `characterToMap` 并于 Task 2 迁移为 StoryCharacterService 公开静态——为避免往返，**本任务直接创建 `StoryCharacterService.characterToMap` 静态方法骨架**，Task 2 补齐其余方法）。`updateShot` 增两个 `updates.containsKey` 分支（characterIds/shotSize，String 强转）。新增：

```java
/** 脚本级整体画风锁（Phase 4 切片1） */
@Transactional
public StoryScript updateStyle(Long scriptId, String style) {
    StoryScript script = getScript(scriptId);
    script.setStyle(style);
    script.setUpdatedAt(LocalDateTime.now());
    return scriptRepository.save(script);
}
```

`ImageGenerationController.shotToMap`（L144 副本）同步增两键。

- [ ] **Step 5：跑测试确认绿** Run: `mvn test -Dtest=StoryScriptServiceCharacterFieldsTest` Expected: PASS。
- [ ] **Step 6：全量回归** Run: `mvn test` Expected: 全绿（构造器变更仅 Spring 装配，测试均 mock）。
- [ ] **Step 7：暂存（不提交）** `git add src/main/java/com/github/ylyan2015/springaidemo/entity/StoryCharacter.java src/main/java/com/github/ylyan2015/springaidemo/repository/StoryCharacterRepository.java src/main/java/com/github/ylyan2015/springaidemo/entity/StoryScript.java src/main/java/com/github/ylyan2015/springaidemo/entity/StoryShot.java src/main/java/com/github/ylyan2015/springaidemo/service/StoryScriptService.java src/main/java/com/github/ylyan2015/springaidemo/controller/ImageGenerationController.java src/test/java/com/github/ylyan2015/springaidemo/service/StoryScriptServiceCharacterFieldsTest.java`

---

### Task 2：角色卡服务与控制器

**Files:**
- Create: `service/StoryCharacterService.java`、`controller/StoryCharacterController.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/service/StoryCharacterServiceTest.java`

**Interfaces:**
- Consumes: Task 1 实体/仓库。
- Produces: `StoryCharacterService`：`listByScript(Long)`、`create(Long, StoryCharacter)`、`update(Long, Long, Map<String,Object>)`、`delete(Long, Long)`、`uploadReferenceImage(Long, Long, InputStream, String, String)`、`listVoices()`、`static characterToMap(StoryCharacter)`；控制器 `/api/video/characters`（GET?scriptId / POST?scriptId / PUT/{id}?scriptId / DELETE/{id}?scriptId / POST/{id}/reference-image?scriptId / GET /voices）。

- [ ] **Step 1：写失败测试** `StoryCharacterServiceTest`

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryCharacterServiceTest {

    private StoryCharacterRepository charRepo;
    private StoryScriptRepository scriptRepo;
    private UserRepository userRepo;
    private StorageService storage;
    private StoryCharacterService service;

    @BeforeEach
    void setUp() {
        charRepo = mock(StoryCharacterRepository.class);
        scriptRepo = mock(StoryScriptRepository.class);
        userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        service = new StoryCharacterService(charRepo, scriptRepo, userRepo, storage);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void createRejectsBlankName() {
        StoryCharacter c = new StoryCharacter();
        c.setName("  ");
        assertThrows(RuntimeException.class, () -> service.create(7L, c));
    }

    @Test
    void createDefaultsVoiceAndLinksScript() {
        StoryCharacter c = new StoryCharacter();
        c.setName("林晚");
        when(charRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryCharacter saved = service.create(7L, c);
        assertEquals("zh-CN-XiaoxiaoNeural", saved.getVoice());
        assertEquals(7L, saved.getScriptId());
    }

    @Test
    void deleteOtherUsersCharacterRejected() {
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.delete(7L, 99L));
    }

    @Test
    void uploadReferenceImageStoresAndSetsUrl() {
        StoryCharacter c = new StoryCharacter(); c.setId(5L); c.setName("林晚");
        when(charRepo.findByIdAndScriptId(5L, 7L)).thenReturn(Optional.of(c));
        when(storage.upload(any(), anyString(), anyString())).thenReturn("fid-1");
        when(storage.getAccessUrl("fid-1")).thenReturn("/images/fid-1.png");
        when(charRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryCharacter updated = service.uploadReferenceImage(7L, 5L,
                new ByteArrayInputStream(new byte[]{1}), "ref.png", "image/png");
        assertEquals("fid-1", updated.getReferenceImageFileId());
        assertEquals("/images/fid-1.png", updated.getReferenceImageUrl());
    }

    @Test
    void uploadRejectsNonImage() {
        assertThrows(RuntimeException.class, () -> service.uploadReferenceImage(7L, 5L,
                new ByteArrayInputStream(new byte[]{1}), "a.txt", "text/plain"));
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=StoryCharacterServiceTest` Expected: 编译失败（类不存在）。

- [ ] **Step 3：实现服务** `StoryCharacterService`（@Service；构造器四参同测试；`getCurrentUserId()` idiom 复制 StoryScriptService；`requireScript(scriptId)` = findByIdAndUserId orElseThrow）：

```java
public List<StoryCharacter> listByScript(Long scriptId) {
    requireScript(scriptId);
    return characterRepository.findByScriptIdOrderByIdAsc(scriptId);
}

public StoryCharacter create(Long scriptId, StoryCharacter input) {
    StoryScript script = requireScript(scriptId);
    if (input.getName() == null || input.getName().isBlank()) throw new RuntimeException("角色名不能为空");
    input.setScript(script);
    if (input.getVoice() == null || input.getVoice().isBlank()) input.setVoice("zh-CN-XiaoxiaoNeural");
    return characterRepository.save(input);
}

public StoryCharacter update(Long scriptId, Long characterId, Map<String, Object> updates) {
    requireScript(scriptId);
    StoryCharacter c = characterRepository.findByIdAndScriptId(characterId, scriptId)
            .orElseThrow(() -> new RuntimeException("角色不存在: " + characterId));
    if (updates.containsKey("name")) c.setName((String) updates.get("name"));
    if (updates.containsKey("featureTable")) c.setFeatureTable((String) updates.get("featureTable"));
    if (updates.containsKey("personality")) c.setPersonality((String) updates.get("personality"));
    if (updates.containsKey("voice")) c.setVoice((String) updates.get("voice"));
    return characterRepository.save(c);
}

@Transactional
public void delete(Long scriptId, Long characterId) {
    requireScript(scriptId);
    StoryCharacter c = characterRepository.findByIdAndScriptId(characterId, scriptId)
            .orElseThrow(() -> new RuntimeException("角色不存在: " + characterId));
    characterRepository.delete(c);
}

public StoryCharacter uploadReferenceImage(Long scriptId, Long characterId,
        InputStream in, String fileName, String contentType) {
    requireScript(scriptId);
    if (contentType == null || !contentType.startsWith("image/")) throw new RuntimeException("仅支持图片文件");
    StoryCharacter c = characterRepository.findByIdAndScriptId(characterId, scriptId)
            .orElseThrow(() -> new RuntimeException("角色不存在: " + characterId));
    String fileId = storageService.upload(in, fileName, contentType);
    c.setReferenceImageFileId(fileId);
    c.setReferenceImageUrl(storageService.getAccessUrl(fileId));
    return characterRepository.save(c);
}

public List<Map<String, String>> listVoices() {
    return VOICES.stream().map(v -> Map.of("name", v)).toList();
}
private static final List<String> VOICES = List.of("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural",
        "zh-CN-YunyangNeural", "zh-CN-XiaoyiNeural", "zh-CN-XiaohanNeural", "zh-CN-YunjianNeural");

public static Map<String, Object> characterToMap(StoryCharacter c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.getId()); m.put("scriptId", c.getScriptId()); m.put("name", c.getName());
    m.put("featureTable", c.getFeatureTable()); m.put("personality", c.getPersonality());
    m.put("referenceImageFileId", c.getReferenceImageFileId());
    m.put("referenceImageUrl", c.getReferenceImageUrl()); m.put("voice", c.getVoice());
    return m;
}
```

（Task 1 中若建了私有 characterToMap 骨架，此处删除改用本公开静态。）

- [ ] **Step 4：实现控制器** `StoryCharacterController`（@RestController @RequestMapping("/api/video/characters")；构造器注入 StoryCharacterService；响应 idiom `Map.of("success", true, "data", ...)` 用 LinkedHashMap 保序；multipart 用 `@RequestParam("file") MultipartFile file`，`file.getInputStream()/getOriginalFilename()/getContentType()`；GET /voices 无需 scriptId）。端点签名：

```java
@GetMapping public ResponseEntity<Map<String, Object>> list(@RequestParam Long scriptId)
@PostMapping public ResponseEntity<Map<String, Object>> create(@RequestParam Long scriptId, @RequestBody StoryCharacter input)
@PutMapping("/{id}") public ResponseEntity<Map<String, Object>> update(@RequestParam Long scriptId, @PathVariable Long id, @RequestBody Map<String, Object> updates)
@DeleteMapping("/{id}") public ResponseEntity<Map<String, Object>> delete(@RequestParam Long scriptId, @PathVariable Long id)
@PostMapping("/{id}/reference-image") public ResponseEntity<Map<String, Object>> upload(@RequestParam Long scriptId, @PathVariable Long id, @RequestParam("file") MultipartFile file) throws Exception
@GetMapping("/voices") public ResponseEntity<Map<String, Object>> voices()
```

- [ ] **Step 5：跑测试确认绿** Run: `mvn test -Dtest=StoryCharacterServiceTest` Expected: 5 PASS。
- [ ] **Step 6：全量回归** Run: `mvn test` Expected: 全绿。
- [ ] **Step 7：暂存（不提交）** `git add` 本任务 4 文件。

---

### Task 3：脚本生成"先人设后分镜"与持久化关联

**Files:**
- Create: `video/CharacterDesign.java`
- Modify: `video/Storyboard.java`、`video/Shot.java`、`video/VideoScriptService.java`、`service/StoryScriptService.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/service/StoryScriptServiceCharactersLinkTest.java`

**Interfaces:**
- Consumes: Task 1/2。
- Produces: `Storyboard.getCharacters()/setCharacters(List<CharacterDesign>)`；`Shot.getCharacterNames()/setCharacterNames(List<String>)`、`getShotSize()/setShotSize(String)`；`CharacterDesign`（name/featureTable/personality/suggestedVoice 四字段 Bean）；持久化后 shot.characterIds 为逗号分隔 id。

- [ ] **Step 1：写失败测试** `StoryScriptServiceCharactersLinkTest`

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceCharactersLinkTest {

    @Test
    void linksCharacterNamesToIdsAndWarnsUnmatched() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        VideoScriptService videoScriptService = mock(VideoScriptService.class);
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryScriptService service = new StoryScriptService(scriptRepo, shotRepo, userRepo, videoScriptService, charRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        Storyboard sb = new Storyboard();
        sb.setTitle("t"); sb.setTheme("th");
        CharacterDesign cd = new CharacterDesign();
        cd.setName("林晚"); cd.setFeatureTable("black hair"); cd.setSuggestedVoice("zh-CN-XiaoxiaoNeural");
        sb.setCharacters(List.of(cd));
        Shot shot = new Shot();
        shot.setSceneDescription("s"); shot.setImagePrompt("p");
        shot.setCharacterNames(List.of("林晚", "不存在"));
        shot.setShotSize("CLOSE");
        sb.setShots(List.of(shot));
        when(videoScriptService.generateScript("idea", null)).thenReturn(sb);
        when(charRepo.save(any())).thenAnswer(inv -> { StoryCharacter c = inv.getArgument(0); c.setId(11L); return c; });
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryScript saved = service.generateAndSaveScript("idea");
        StoryShot savedShot = saved.getShots().get(0);
        assertEquals("11", savedShot.getCharacterIds());
        assertEquals("CLOSE", savedShot.getShotSize());
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=StoryScriptServiceCharactersLinkTest` Expected: 编译失败（DTO 新字段不存在）。

- [ ] **Step 3：DTO 扩展** `CharacterDesign` 四字段 Bean（单行 getter/setter idiom）。`Storyboard` 增 `private List<CharacterDesign> characters;` + getter/setter。`Shot` 增 `private List<String> characterNames; private String shotSize;` + getter/setter。

- [ ] **Step 4：VideoScriptService System Prompt 追加**（在"注意：imagePrompt…"段后追加）：

```text
4. 人物设计（characters 数组，先人设后分镜）：
   - name: 角色名（中文）
   - featureTable: 固定外观特征表（英文：发型/瞳色/服装/体型，跨镜头不变）
   - personality: 性格/情绪关键词（中文）
   - suggestedVoice: 建议声线（zh-CN-XiaoxiaoNeural / zh-CN-YunxiNeural / zh-CN-YunyangNeural 等）
5. 每个镜头额外包含：
   - characterNames: 本镜出场角色名数组（须与 characters 的 name 一致）
   - shotSize: 景别（CLOSE=特写 / MEDIUM=中景 / LONG=远景）
一个分镜对应一个镜头；角色特征表是后续所有出图的一致性锚点，务必具体稳定。
```

- [ ] **Step 5：StoryScriptService 持久化关联** 新增私有 helper 并在 `generateAndSaveScript` 与 `saveFromStoryboard` 的镜头循环前调用：

```java
/** 持久化人设并建立 name→id 映射（Phase 4 切片1） */
private Map<String, Long> persistCharacters(StoryScript script, Storyboard storyboard) {
    Map<String, Long> ids = new LinkedHashMap<>();
    if (storyboard.getCharacters() != null) {
        for (CharacterDesign cd : storyboard.getCharacters()) {
            if (cd.getName() == null || cd.getName().isBlank()) continue;
            StoryCharacter c = new StoryCharacter();
            c.setScript(script);
            c.setName(cd.getName());
            c.setFeatureTable(cd.getFeatureTable());
            c.setPersonality(cd.getPersonality());
            if (cd.getSuggestedVoice() != null && !cd.getSuggestedVoice().isBlank()) c.setVoice(cd.getSuggestedVoice());
            StoryCharacter saved = characterRepository.save(c);
            ids.put(saved.getName(), saved.getId());
        }
    }
    return ids;
}
```

镜头循环内增：`shot.setShotSize(shotDto.getShotSize() != null ? shotDto.getShotSize() : "MEDIUM");` 与

```java
if (shotDto.getCharacterNames() != null) {
    List<String> matched = new ArrayList<>();
    for (String n : shotDto.getCharacterNames()) {
        Long cid = characterIds.get(n);
        if (cid != null) matched.add(String.valueOf(cid));
        else log.warn("Shot {} references unknown character '{}', skipped", i + 1, n);
    }
    shot.setCharacterIds(String.join(",", matched));
}
```

`toStoryboard` 反向映射：characters 经 characterRepository 加载转 CharacterDesign；shot dto 的 characterNames 由 characterIds 拆分查 name；shotSize 直传。

- [ ] **Step 6：跑测试确认绿 + 全量** Run: `mvn test -Dtest=StoryScriptServiceCharactersLinkTest` 然后 `mvn test` Expected: 全绿。
- [ ] **Step 7：暂存（不提交）** `git add` 本任务 6 文件。

---

### Task 4：ShotPromptAssembler 唯一装配点

**Files:**
- Create: `video/ShotPromptAssembler.java`
- Modify: `service/ImagePromptOptimizerService.java`（批量优化注入角色上下文）
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/ShotPromptAssemblerTest.java`

**Interfaces:**
- Consumes: Task 1 实体。
- Produces: `@Component ShotPromptAssembler`：`String assemble(StoryShot shot, VisualStyle style, List<StoryCharacter> characters)`、`String characterContext(List<StoryCharacter> characters)`。装配顺序：`stylePrefix + ", " + characterContext + base + ", " + shotSizeTerm + (cameraMovement 非空时 ", camera: " + cameraMovement)`；base = imagePrompt 空则 sceneDescription，双空抛 RuntimeException（沿用现 composePrompt 语义）。景别映射 CLOSE→close-up / MEDIUM→medium shot / LONG→long shot（null/未知→medium shot）。

- [ ] **Step 1：写失败测试** `ShotPromptAssemblerTest`

```java
package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShotPromptAssemblerTest {

    private final ShotPromptAssembler assembler = new ShotPromptAssembler();

    @Test
    void orderStyleThenCharactersThenBaseThenShotSize() {
        StoryShot shot = new StoryShot();
        shot.setImagePrompt("a girl standing in rain");
        shot.setShotSize("CLOSE");
        shot.setCameraMovement("推进");
        StoryCharacter c = new StoryCharacter();
        c.setName("林晚");
        c.setFeatureTable("black long hair, amber eyes");
        String p = assembler.assemble(shot, VisualStyle.ANIME, List.of(c));
        assertTrue(p.startsWith(VisualStyle.ANIME.getPromptPrefix()));
        int iChar = p.indexOf("Character 林晚: black long hair, amber eyes.");
        int iBase = p.indexOf("a girl standing in rain");
        int iSize = p.indexOf("close-up");
        assertTrue(iChar > 0 && iBase > iChar && iSize > iBase);
        assertTrue(p.contains("camera: 推进"));
    }

    @Test
    void emptyCharactersDegradesToStylePlusBase() {
        StoryShot shot = new StoryShot();
        shot.setImagePrompt("base");
        String p = assembler.assemble(shot, VisualStyle.ANIME, List.of());
        assertEquals(VisualStyle.ANIME.getPromptPrefix() + ", base, medium shot", p);
    }

    @Test
    void blankPromptFallsBackToSceneDescription() {
        StoryShot shot = new StoryShot();
        shot.setSceneDescription("场景");
        assertTrue(assembler.assemble(shot, VisualStyle.ANIME, List.of()).contains("场景"));
    }

    @Test
    void bothBlankThrows() {
        assertThrows(RuntimeException.class,
                () -> assembler.assemble(new StoryShot(), VisualStyle.ANIME, List.of()));
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=ShotPromptAssemblerTest` Expected: 编译失败。

- [ ] **Step 3：实现装配器**

```java
package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

/** 唯一 prompt 装配点（Phase 4 切片1）：风格前缀 + 角色特征表 + 镜头描述 + 景别/运镜 */
@Component
public class ShotPromptAssembler {

    public String assemble(StoryShot shot, VisualStyle style, List<StoryCharacter> characters) {
        String base = shot.getImagePrompt();
        if (base == null || base.isBlank()) base = shot.getSceneDescription();
        if (base == null || base.isBlank())
            throw new RuntimeException("镜头 " + shot.getShotOrder() + " 没有可用的提示词或场景描述");
        StringBuilder sb = new StringBuilder(style.getPromptPrefix());
        String ctx = characterContext(characters);
        if (!ctx.isEmpty()) sb.append(", ").append(ctx);
        sb.append(", ").append(base).append(", ").append(shotSizeTerm(shot.getShotSize()));
        if (shot.getCameraMovement() != null && !shot.getCameraMovement().isBlank())
            sb.append(", camera: ").append(shot.getCameraMovement());
        return sb.toString();
    }

    public String characterContext(List<StoryCharacter> characters) {
        if (characters == null || characters.isEmpty()) return "";
        return characters.stream()
                .filter(c -> c.getFeatureTable() != null && !c.getFeatureTable().isBlank())
                .map(c -> "Character " + c.getName() + ": " + c.getFeatureTable() + ".")
                .collect(Collectors.joining(" "));
    }

    private String shotSizeTerm(String shotSize) {
        if ("CLOSE".equalsIgnoreCase(shotSize)) return "close-up";
        if ("LONG".equalsIgnoreCase(shotSize)) return "long shot";
        return "medium shot";
    }
}
```

- [ ] **Step 4：优化器注入角色上下文** `ImagePromptOptimizerService` 构造器增 `StoryCharacterRepository`（**存量测试无直接构造点，均 mock，零回归**）；`enhanceScriptShots` 循环内 `buildUserMessage` 前取 `List<StoryCharacter> chars = charactersForShot(shot)`（按 characterIds 拆分过滤仓库列表，仓库列表循环外一次加载），`buildUserMessage(description, visualStyle)` 增第三参 `String characterContext`，user message 追加"角色一致性锚点（必须保持）：%s"（空则不追加）；单条 `enhancePrompt` 不变。
- [ ] **Step 5：跑测试确认绿 + 全量** Run: `mvn test -Dtest=ShotPromptAssemblerTest` 然后 `mvn test`。
- [ ] **Step 6：暂存（不提交）**。

---

### Task 5：ImageGenerator 参考图 default 扩展（向后兼容）

**Files:**
- Modify: `video/generator/ImageGenerator.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/generator/ImageGeneratorDefaultMethodTest.java`

**Interfaces:**
- Produces: `default GeneratedImage generateWithReferences(String prompt, String negativePrompt, int width, int height, List<String> referenceImages)`（默认委托 generate，忽略 refs）；`default boolean supportsReferences()`（默认 false）。现有 6 实现零改动。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageGeneratorDefaultMethodTest {

    static class RecordingGenerator implements ImageGenerator {
        String lastPrompt;
        @Override public GeneratedImage generate(String prompt, String neg, int w, int h) {
            lastPrompt = prompt;
            return new GeneratedImage("url", "fid", "rec", w, h);
        }
        @Override public String getType() { return "rec"; }
        @Override public String getName() { return "rec"; }
        @Override public boolean isAvailable() { return true; }
    }

    @Test
    void defaultDelegatesAndIgnoresReferences() {
        RecordingGenerator g = new RecordingGenerator();
        GeneratedImage img = g.generateWithReferences("p", null, 1280, 720, List.of("ref1"));
        assertEquals("p", g.lastPrompt);
        assertEquals("url", img.getImageUrl());
        assertFalse(g.supportsReferences());
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=ImageGeneratorDefaultMethodTest` Expected: 编译失败（方法不存在）。
- [ ] **Step 3：接口增 default 方法**（import java.util.List）：

```java
/** 参考图生图（Phase 4 切片1）；默认实现忽略参考图，旧生成器零改动 */
default GeneratedImage generateWithReferences(String prompt, String negativePrompt,
        int width, int height, List<String> referenceImages) {
    return generate(prompt, negativePrompt, width, height);
}

/** 是否支持参考图（角色一致性） */
default boolean supportsReferences() { return false; }
```

- [ ] **Step 4：跑测试确认绿 + 全量** Run: `mvn test -Dtest=ImageGeneratorDefaultMethodTest` 然后 `mvn test`。
- [ ] **Step 5：暂存（不提交）**。

---

### Task 6：ReferenceImageResolver 回退链

**Files:**
- Create: `video/generator/ReferenceImageResolver.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/generator/ReferenceImageResolverTest.java`

**Interfaces:**
- Consumes: Task 1 仓库、StorageService（`getStorageType()/getAccessUrl()/download()`）。
- Produces: `@Component ReferenceImageResolver(StoryCharacterRepository, StorageService)`：`List<StoryCharacter> charactersForShot(StoryShot)`（按 characterIds 逗号拆分过滤，保持镜头声明顺序；ids 空→空列表）；`List<String> resolve(List<StoryCharacter>)`：非 local 存储→`getAccessUrl(fileId)`；local→`data:image/{png|jpeg};base64,...`（download 读字节，mime 按 fileId 后缀 .jpg/.jpeg→jpeg 其余 png）；单角色异常 warn 跳过不阻断；无参考图角色跳过。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceImageResolverTest {

    private StoryCharacter character(Long id, String fileId) {
        StoryCharacter c = new StoryCharacter();
        c.setId(id);
        c.setReferenceImageFileId(fileId);
        return c;
    }

    @Test
    void localStorageYieldsBase64DataUri() {
        StoryCharacterRepository repo = mock(StoryCharacterRepository.class);
        StorageService storage = mock(StorageService.class);
        when(storage.getStorageType()).thenReturn("local");
        when(storage.download("f1")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        ReferenceImageResolver resolver = new ReferenceImageResolver(repo, storage);
        List<String> refs = resolver.resolve(List.of(character(1L, "f1")));
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("data:image/png;base64,"));
    }

    @Test
    void minioStorageYieldsAccessUrl() {
        StoryCharacterRepository repo = mock(StoryCharacterRepository.class);
        StorageService storage = mock(StorageService.class);
        when(storage.getStorageType()).thenReturn("minio");
        when(storage.getAccessUrl("f1")).thenReturn("http://minio/f1");
        ReferenceImageResolver resolver = new ReferenceImageResolver(repo, storage);
        assertEquals(List.of("http://minio/f1"), resolver.resolve(List.of(character(1L, "f1"))));
    }

    @Test
    void downloadFailureSkipsCharacter() {
        StoryCharacterRepository repo = mock(StoryCharacterRepository.class);
        StorageService storage = mock(StorageService.class);
        when(storage.getStorageType()).thenReturn("local");
        when(storage.download("f1")).thenThrow(new RuntimeException("io"));
        ReferenceImageResolver resolver = new ReferenceImageResolver(repo, storage);
        assertTrue(resolver.resolve(List.of(character(1L, "f1"))).isEmpty());
    }

    @Test
    void charactersForShotFiltersByIds() {
        StoryCharacterRepository repo = mock(StoryCharacterRepository.class);
        when(repo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(character(1L, null), character(2L, null)));
        ReferenceImageResolver resolver = new ReferenceImageResolver(repo, mock(StorageService.class));
        StoryShot shot = new StoryShot();
        shot.setScriptId(7L);
        shot.setCharacterIds("2");
        assertEquals(List.of(2L), resolver.charactersForShot(shot).stream().map(StoryCharacter::getId).toList());
    }
}
```

- [ ] **Step 2：跑测试确认红** Run: `mvn test -Dtest=ReferenceImageResolverTest`。
- [ ] **Step 3：实现** 按 Interfaces 描述实现（resolve 内 try/catch 单角色；charactersForShot 内 ids 解析 `Arrays.stream(ids.split(",")).map(String::trim).filter(!blank).map(Long::valueOf)`，非法数字 warn 跳过）。
- [ ] **Step 4：跑测试确认绿 + 全量**。
- [ ] **Step 5：暂存（不提交）**。

---

### Task 7：Wan27ImageGenerator + 注册 + 配置（含协议核对）

**Files:**
- Create: `video/generator/Wan27ImageGenerator.java`
- Modify: `config/ImageGeneratorConfig.java`、`resources/application.yml`
- Test: `video/generator/Wan27ImageGeneratorTest.java`、`video/generator/Wan27RegistrationTest.java`

**Interfaces:**
- Consumes: Task 5 接口、ImageSizeMapper.map/parse、StorageService、Spring ObjectMapper。
- Produces: type=`wan27`；构造器 `(StorageService, ObjectMapper, HttpClient, String baseUrl, String apiKey, String model, int timeoutSeconds)`；包私有 `buildRequestBody(prompt, negative, size, refs)` 与 `parseImage(json)` 供测试。

- [ ] **Step 1：协议核对（先于编码）** WebSearch `wan2.7-image API 参考 百炼 多图参考`，WebFetch 命中的 help.aliyun.com 官方文档页，核对并记录到本计划执行记录：① 端点路径（预期 `/api/v1/services/aigc/multimodal-generation/generation`，与 wan2.6-t2i 同族）；② `input.messages[0].content` 的 image/text 条目字段名；③ 是否接受 base64 data URI（决定 Task 6 local 分支是否需改为跳过+warn，若不支持则改 resolver 一行并补测试）；④ 尺寸约束（预期总像素/宽高比约束同 wan2.6 族，推荐表 1696x960/960x1696/1280x1280/1472x1104/1104x1472）；⑤ 响应 `output.choices[0].message.content[0].image`。**若与下述代码不符，以官方文档为准调整 builder/parser 与测试，并在执行记录注明差异**。
- [ ] **Step 2：写失败测试** `Wan27ImageGeneratorTest`

```java
package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Wan27ImageGeneratorTest {

    private Wan27ImageGenerator newGen(HttpClient http, StorageService storage) {
        return new Wan27ImageGenerator(storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com", "sk-test", "wan2.7-image", 120);
    }

    @Test
    void buildRequestBodyTruncatesRefsToNineAndAppendsNegative() {
        Wan27ImageGenerator gen = newGen(mock(HttpClient.class), mock(StorageService.class));
        List<String> refs = IntStream.range(0, 12).mapToObj(i -> "http://r/" + i).toList();
        String body = gen.buildRequestBody("p", "bad hands", "1280x1280", refs);
        assertTrue(body.contains("http://r/8"));
        assertFalse(body.contains("http://r/9"));
        assertTrue(body.contains("Avoid the following: bad hands"));
        assertTrue(body.contains("\"size\":\"1280x1280\""));
    }

    @Test
    void parseImageReadsChoicesContent() {
        Wan27ImageGenerator gen = newGen(mock(HttpClient.class), mock(StorageService.class));
        String json = "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"http://img/1.png\"}]}}]}}";
        assertEquals("http://img/1.png", gen.parseImage(json));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateTransfersToStorage() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"http://img/1.png\"}]}}]}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
        // 下载图片字节：第二次 send 返回字节流响应
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{9});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn((HttpResponse) dl);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("fid-9");
        when(storage.getAccessUrl("fid-9")).thenReturn("/images/fid-9.png");
        GeneratedImage img = newGen(http, storage).generateWithReferences("p", null, 1280, 720, List.of("http://ref"));
        assertEquals("/images/fid-9.png", img.getImageUrl());
        assertEquals("fid-9", img.getFileId());
        assertEquals("wan27", img.getGeneratorType());
    }

    @Test
    void emptyKeyUnavailable() {
        Wan27ImageGenerator gen = new Wan27ImageGenerator(mock(StorageService.class), new ObjectMapper(),
                mock(HttpClient.class), "https://x", "", "wan2.7-image", 120);
        assertFalse(gen.isAvailable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void non200Throws() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(400);
        when(resp.body()).thenReturn("{\"message\":\"size error\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);
        Wan27ImageGenerator gen = newGen(http, mock(StorageService.class));
        assertThrows(RuntimeException.class, () -> gen.generate("p", null, 1280, 720));
    }
}
```

（accessor 已核实：`GeneratedImage.getGeneratorType()` 存在，断言成立。）

- [ ] **Step 3：跑测试确认红**。
- [ ] **Step 4：实现 Wan27ImageGenerator** 要点：`generate` 委托 `generateWithReferences(..., List.of())`；`supportsReferences()=true`；size = `ImageSizeMapper.map(w, h, RECOMMENDED_SIZES)`；buildRequestBody：`{model, input:{messages:[{role:"user",content:[{"image":ref}×≤9, {"text": prompt + (neg 非空? "\n\nAvoid the following: "+neg : "")]}]}, parameters:{size, n:1}}`（ObjectMapper 序列化）；请求头 `Authorization: Bearer ` + apiKey、`Content-Type: application/json`；超时 `Duration.ofSeconds(timeoutSeconds)`；非 200 抛 RuntimeException 带 body 截断 400；parseImage 用 ObjectMapper readTree 沿 `output.choices[0].message.content` 找首个含 `image` 的条目，缺失抛异常；data URI 解码 Base64，否则 httpClient GET 下载（非 200 抛异常）；转存 idiom 同 OpenAiCompatibleImageGenerator（`wan27_image_` + 时间戳 + `.png`）。
- [ ] **Step 5：注册 Bean + yml** ImageGeneratorConfig 增：

```java
@Bean
public Wan27ImageGenerator wan27ImageGenerator(StorageService storageService, ObjectMapper objectMapper,
        @Value("${spring.ai.dashscope.api-key:}") String apiKey,
        @Value("${image.wan27.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
        @Value("${image.wan27.model:wan2.7-image}") String model,
        @Value("${image.wan27.timeout-seconds:120}") int timeoutSeconds) {
    return new Wan27ImageGenerator(storageService, objectMapper, defaultHttpClient(), baseUrl, apiKey, model, timeoutSeconds);
}
```

application.yml `image:` 下增：

```yaml
  wan27:                               # Phase 4 切片1：wan2.7-image 参考图生图（角色一致性）
    base-url: https://dashscope.aliyuncs.com
    model: wan2.7-image
    timeout-seconds: 120
```

- [ ] **Step 6：注册测试** `Wan27RegistrationTest`（@SpringBootTest 全上下文，沿用 OpenAiCompatibleGeneratorsRegistrationTest  idiom）：断言 `factory.getAllGenerators().containsKey("wan27")`；无 Key 环境 `wan27.isAvailable()==false`；`image.generator=wan27` 属性下 activeType=wan27。
- [ ] **Step 7：跑测试确认绿 + 全量**（含干净启动回归）。
- [ ] **Step 8：暂存（不提交）**。

---

### Task 8：ImageGenerationService 接线（装配点替换 + 参考图传递）

**Files:**
- Modify: `service/ImageGenerationService.java`
- Modify: `src/test/.../service/ImageGenerationServiceQualityTest.java`（L59 构造点）、`ImageGenerationServiceTimeoutCancelTest.java`（L68 构造点）
- Test: `src/test/.../service/ImageGenerationServiceReferencesTest.java`

**Interfaces:**
- Consumes: Task 4 assembler、Task 6 resolver、Task 5 接口。
- Produces: 构造器 8 参（原 6 + `ShotPromptAssembler` + `ReferenceImageResolver`）；`generateWithRetry(prompt, negative, refs)` 与 `generateWithQualityCheck(prompt, negative, refs)` 内部签名增 refs；refs 非空且 `supportsReferences()` 时走 `generateWithReferences`，否则 `generate`。

- [ ] **Step 1：写失败测试** `ImageGenerationServiceReferencesTest`（完整类，setup idiom 同 QualityTest）

```java
package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.generator.*;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGenerationServiceReferencesTest {

    private ImageGeneratorFactory factory;
    private ReferenceImageResolver resolver;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        factory = mock(ImageGeneratorFactory.class);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        ImageQualityService quality = mock(ImageQualityService.class);
        when(quality.isEnabled()).thenReturn(false);
        resolver = mock(ReferenceImageResolver.class);
        service = new ImageGenerationService(factory, scriptRepo, shotRepo, userRepo,
                new ObjectMapper(), quality, new ShotPromptAssembler(), resolver);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImagePrompt("a girl in rain");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void referencesPassedWhenGeneratorSupports() {
        ImageGenerator gen = mock(ImageGenerator.class);
        when(gen.supportsReferences()).thenReturn(true);
        when(gen.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new GeneratedImage("u", "f", "wan27", 1280, 720));
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryCharacter c = new StoryCharacter(); c.setId(1L); c.setReferenceImageFileId("fid");
        when(resolver.charactersForShot(any())).thenReturn(List.of(c));
        when(resolver.resolve(List.of(c))).thenReturn(List.of("data:image/png;base64,AA"));
        service.generateForShot(7L, 3L, null);
        verify(gen).generateWithReferences(anyString(), any(), anyInt(), anyInt(), eq(List.of("data:image/png;base64,AA")));
        verify(gen, never()).generate(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void fallbackToPlainGenerateWhenUnsupported() {
        ImageGenerator gen = mock(ImageGenerator.class);
        when(gen.supportsReferences()).thenReturn(false);
        when(gen.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("u", "f", "dashscope", 1280, 720));
        when(factory.getActiveGenerator()).thenReturn(gen);
        StoryCharacter c = new StoryCharacter(); c.setId(1L); c.setReferenceImageFileId("fid");
        when(resolver.charactersForShot(any())).thenReturn(List.of(c));
        when(resolver.resolve(List.of(c))).thenReturn(List.of("data:image/png;base64,AA"));
        service.generateForShot(7L, 3L, null);
        verify(gen).generate(anyString(), any(), anyInt(), anyInt());
        verify(gen, never()).generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList());
    }
}
```

- [ ] **Step 2：跑测试确认红**。
- [ ] **Step 3：实现接线** 构造器增两参存字段；`composePrompt` 体替换为 `return promptAssembler.assemble(shot, visualStyle, referenceResolver.charactersForShot(shot));`；`generateForShot` 与 `generateShotWithProgress` 内计算 `List<String> refs = referenceResolver.resolve(referenceResolver.charactersForShot(shot));` 并传入 `generateWithQualityCheck(prompt, negative, refs)`；两内部方法透传 refs；`generateWithRetry` 分支：

```java
if (!refs.isEmpty() && generator.supportsReferences()) {
    return generator.generateWithReferences(prompt, negativePrompt,
            parseWidth(defaultSize), parseHeight(defaultSize), refs);
}
return generator.generate(prompt, negativePrompt, parseWidth(defaultSize), parseHeight(defaultSize));
```

- [ ] **Step 4：更新存量测试构造点** 两处 `new ImageGenerationService(...)` 末尾追加 `new ShotPromptAssembler(), resolverMock`，其中 `resolverMock = mock(ReferenceImageResolver.class)` 且 `when(resolverMock.charactersForShot(any())).thenReturn(List.of()); when(resolverMock.resolve(any())).thenReturn(List.of());`（保持存量断言不变：refs 空走 generate）。
- [ ] **Step 5：跑测试确认绿 + 全量** Run: `mvn test` Expected: 全绿。
- [ ] **Step 6：暂存（不提交）**。

---

### Task 9：Agent generateCharacters 工具

**Files:**
- Modify: `video/agent/AgentToolRequests.java`、`VideoAgentToolFactory.java`、`SpecialistAgentFactory.java`
- Modify: `src/test/.../controller/VideoAgentControllerTest.java`（L60-73：6→7）、`src/test/.../video/agent/VideoAgentToolFactoryTest.java`（L80-81：6→7）、`SpecialistAgentFactoryTest.java`（L84：编剧子集增 generateCharacters）
- Test: `VideoAgentToolFactoryTest` 新增用例

**Interfaces:**
- Consumes: Task 3 持久化（scriptToMap 含 characters）。
- Produces: 工具 `generateCharacters(idea)` → `{success, scriptId, characters}`；编剧专家工具子集 = generateStoryboard/enhancePrompt/generateCharacters；createTools() 共 7 工具。

- [ ] **Step 1：写失败测试** `VideoAgentToolFactoryTest` 增：

```java
@Test
void generateCharactersReturnsScriptIdAndCharacters() {
    StoryScript script = new StoryScript();
    script.setId(9L);
    when(storyScriptService.generateAndSaveScript("idea")).thenReturn(script);
    Map<String, Object> scriptMap = new LinkedHashMap<>();
    scriptMap.put("characters", List.of(Map.of("name", "林晚", "featureTable", "black hair")));
    when(storyScriptService.scriptToMap(script)).thenReturn(scriptMap);
    JsonNode out = call("generateCharacters", "{\"idea\":\"idea\"}");
    assertTrue(out.get("success").asBoolean());
    assertEquals(9, out.get("scriptId").asInt());
    assertEquals("林晚", out.get("characters").get(0).get("name").asText());
}
```

（helper 已核实：`call(name, inputJson)` 返回 JsonNode，直接复用。）

- [ ] **Step 2：跑测试确认红**。
- [ ] **Step 3：实现** AgentToolRequests 增 record：

```java
/** 生成角色卡+分镜（先人设后分镜；Phase 4 切片1） */
public record GenerateCharacters(
        @ToolParam(description = "创意想法/主题描述；生成角色卡（特征表/声线）与引用角色的分镜脚本") String idea) {}
```

VideoAgentToolFactory.createTools() 在 recommendBgm 后追加第 7 个 builder（name=generateCharacters，description="生成角色卡与分镜脚本（先人设后分镜）：返回 scriptId 与 characters 数组（name/featureTable/voice），用于角色一致性出图"）；方法体：

```java
private Map<String, Object> generateCharacters(AgentToolRequests.GenerateCharacters req) {
    if (req.idea() == null || req.idea().isBlank()) throw new IllegalArgumentException("idea 不能为空");
    StoryScript script = storyScriptService.generateAndSaveScript(req.idea());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("success", true);
    out.put("scriptId", script.getId());
    out.put("characters", storyScriptService.scriptToMap(script).get("characters"));
    return out;
}
```

SpecialistAgentFactory：`SPECIALIST_TOOLS` screenwriter 增 `"generateCharacters"`；screenwriter prompt 增句"漫剧项目用 generateCharacters 先产出角色卡再出分镜，汇报角色名单。"。

- [ ] **Step 4：更新存量断言** VideoAgentControllerTest：方法名 `listToolsWithRealFactoryReturnsSixTools`→`...SevenTools`，`assertEquals(6, ...)`→7；VideoAgentToolFactoryTest：方法名 `registersSixToolsWithUniqueNames`→`registersSevenToolsWithUniqueNames`，L80-81 两行 6→7；SpecialistAgentFactoryTest L84 `List.of("generateStoryboard", "enhancePrompt")`→`List.of("generateStoryboard", "enhancePrompt", "generateCharacters")`（子集顺序=createTools 顺序）。
- [ ] **Step 5：跑测试确认绿 + 全量**。
- [ ] **Step 6：暂存（不提交）**。

---

### Task 10：前端角色卡面板 + 脚本级风格 + 镜头角色/景别

**Files:**
- Modify: `controller/StoryScriptController.java`（增 PUT /{id}/style）、`templates/video-studio.html`

**Interfaces:**
- Consumes: Task 1 updateStyle、Task 2 控制器、shotToMap 新键。
- Produces: 角色卡面板（增删改/定妆图上传/声线下拉）、styleSelect 绑定脚本级风格、镜头编辑表单增 shotSize 与角色复选。

- [ ] **Step 1：控制器增端点**

```java
@PutMapping("/{id}/style")
public ResponseEntity<Map<String, Object>> updateStyle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
    StoryScript script = storyScriptService.updateStyle(id, (String) body.get("style"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("data", storyScriptService.scriptToMap(script));
    return ResponseEntity.ok(result);
}
```

- [ ] **Step 2：HTML 面板** 在 `<div id="scriptArea"></div>`（L176）前插入：

```html
<div class="input-section" id="characterPanel" style="display:none;">
    <h2>角色卡（人物一致性）</h2>
    <div id="characterList"></div>
    <div style="display:flex;gap:8px;margin-top:8px;flex-wrap:wrap">
        <input id="charNameInput" placeholder="角色名" style="background:#0f0f1a;border:1px solid #333;border-radius:6px;padding:8px 10px;color:#fff;font-size:13px">
        <select id="charVoiceSelect" style="background:#0f0f1a;border:1px solid #333;border-radius:6px;padding:8px 10px;color:#fff;font-size:13px"></select>
        <button class="btn btn-sm" onclick="createCharacter()">保存角色卡</button>
    </div>
    <textarea id="charFeatureInput" placeholder="固定特征表（英文）：发型/瞳色/服装/体型，如 black long straight hair, amber eyes, white shirt, slim" rows="2" style="width:100%;margin-top:8px;background:#0f0f1a;border:1px solid #333;border-radius:6px;padding:8px 10px;color:#fff;font-size:13px"></textarea>
</div>
```

`<select id="styleSelect"></select>`（L147）改为 `<select id="styleSelect" onchange="onStyleChange()"></select>`。

- [ ] **Step 3：JS 角色卡函数** 在"渲染"节之前插入完整函数块：`currentCharacters/editingCharacterId` 状态 + `loadCharacters(scriptId)`（GET ?scriptId，完成后 renderCharacters() 且 currentScript 非空时 renderScript()——刷新镜头编辑表单的角色复选框）+ `renderCharacters()`（列表行：参考图缩略/名字/声线/特征表摘要/编辑/定妆图上传 hidden file input/删除）+ `createCharacter()`（editingCharacterId 非空走 PUT 否则 POST，body={name,featureTable,voice}，成功后清空表单并 loadCharacters）+ `startEditCharacter(id)` + `deleteCharacter(id)` + `uploadCharacterRef(id,input)`（FormData POST /{id}/reference-image?scriptId=）+ `loadVoices()`（GET /voices 填充 charVoiceSelect）+ `onStyleChange()`（PUT /scripts/{id}/style {style}，成功更新 currentScript.style）。全部沿用现有 `api/post/put/esc/toast` helper 与深色内联样式 idiom。
- [ ] **Step 4：钩子接线** `loadScriptDetail`（L376）：`renderScript();` 前增 `if (data.data.style) document.getElementById('styleSelect').value = data.data.style;`，其后增 `loadCharacters(id);`。页面初始化处（首次调用 `loadScriptList()` 的同一位置）增 `loadVoices();`。
- [ ] **Step 5：镜头编辑表单** `shotCardHtml` 编辑分支在 `edit-camera` 行（L517）后增：

```js
h += '<label>景别</label><select id="edit-size-' + shot.id + '"><option value="CLOSE"' + (shot.shotSize === 'CLOSE' ? ' selected' : '') + '>特写</option><option value="MEDIUM"' + (!shot.shotSize || shot.shotSize === 'MEDIUM' ? ' selected' : '') + '>中景</option><option value="LONG"' + (shot.shotSize === 'LONG' ? ' selected' : '') + '>远景</option></select>';
h += '<label>出场角色</label><div>' + (currentCharacters.length ? currentCharacters.map(c => '<label style="margin-right:10px"><input type="checkbox" class="edit-char-' + shot.id + '" value="' + c.id + '"' + ((shot.characterIds || '').split(',').includes(String(c.id)) ? ' checked' : '') + '> ' + esc(c.name) + '</label>').join('') : '<span style="color:#888">无角色卡</span>') + '</div>';
```

`saveShotEdit` body 增两键：`shotSize: document.getElementById('edit-size-' + shotId).value, characterIds: Array.from(document.querySelectorAll('.edit-char-' + shotId + ':checked')).map(i => i.value).join(',')`。

- [ ] **Step 6：JS 语法验证** Run（PowerShell）:

```powershell
$c = Get-Content src/main/resources/templates/video-studio.html -Raw; [regex]::Matches($c, '(?s)<script>(.*?)</script>') | ForEach-Object { $_.Groups[1].Value } | Set-Content target/studio-js.js; node --check target/studio-js.js
```

Expected: 无输出（语法 OK）。
- [ ] **Step 7：浏览器冒烟（chrome-devtools MCP，沿用 zhipu_verify 测试账号）** 登录→/video-studio→新建脚本（LLM 返回 characters）→角色面板可见→编辑特征表→上传定妆图→镜头编辑勾选角色/景别保存→styleSelect 切换 ANIME 后刷新仍保持。无 DASHSCOPE Key 时出图冒烟跳过并记录。
- [ ] **Step 8：暂存（不提交）**。

---

### Task 11：文档同步 + 全量验证 + 真实冒烟

**Files:**
- Modify: `README.md`、`README-en.md`、`API_TEST.md`、`API_TEST-en.md`、`VIDEO_STUDIO_PLAN.md`
- Test: `src/test/.../video/generator/Wan27RealSmokeTest.java`

- [ ] **Step 1：四份文档同步** README 中英文特性节增 bullet：角色卡（人设特征表/定妆参考图/声线）、脚本级画风锁、wan2.7-image 参考图生图（角色一致性，≤9 参考图，local 存储 base64 回退）、编剧专家 generateCharacters；配置节增 `image.wan27.*` 与 `image.generator: wan27` 说明（复用 DASHSCOPE_API_KEY，空 Key 不可用）；API_TEST 中英文增 `/api/video/characters` 六端点表 + `PUT /api/video/scripts/{id}/style` + 请求/响应示例。
- [ ] **Step 2：VIDEO_STUDIO_PLAN 增 Phase 4 指针节**：

```markdown
## 五、Phase 4 — 漫剧质量升级（规划）
> 设计规格：docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md（2026-11-15 确认；云端 API 路线；优先级切片 1→5）。
> 切片1 实施计划：docs/superpowers/plans/2026-11-15-phase4-slice1-character-consistency.md。
```

- [ ] **Step 3：真实冒烟测试** `Wan27RealSmokeTest`（@SpringBootTest；`Assumptions.assumeTrue(Boolean.getBoolean("wan27"))` + Key 非空；流程：① gen.generate 生成角色定妆图；② storage.download 读字节拼 data URI；③ 同一参考图 + 两个不同场景 prompt 调 generateWithReferences；④ 产物字节写 `target/smoke-wan27-shot1.png`/`shot2.png` 并断言存在；⑤ 日志输出路径供目视一致性核验）。Run: `mvn test -Dtest=Wan27RealSmokeTest -Dwan27=true`（需 DASHSCOPE_API_KEY）；默认 `mvn test` 跳过。
- [ ] **Step 4：全量门槛** Run: `mvn test` Expected: 全绿（含干净环境启动回归）。
- [ ] **Step 5：启动冒烟** 后台启动应用；`curl http://localhost:8080/api/video/characters/voices` 期望 `{"success":true,...}`；H2 自动建表 `story_characters` 与新增列无报错（日志核验）。
- [ ] **Step 6：暂存全部（不提交）** `git add -A src docs`（排除 data/、target/、uploads/ 运行产物），汇报变更清单与测试结果，**等待用户提交指令**。

---

## 自检记录（writing-plans self-review）

**Spec 覆盖**：§4.1 数据模型→Task 1/2；§4.2 脚本生成与装配→Task 3/4；§4.3 参考图生成→Task 5/6/7/8；§4.4 API 与前端→Task 2/10；§4.5 Agent→Task 9；§4.6 测试与验收→各任务 TDD + Task 11；§4.7 文档→Task 11；§4.8 协议核对→Task 7 Step 1。§9 红线→Global Constraints。无遗漏。

**类型一致性**：构造器变更链（StoryScriptService 5 参、ImageGenerationService 8 参）在 Task 1/8 与存量测试更新点（QualityTest L59、TimeoutCancelTest L68）一致；工具数 6→7 的三处存量断言已枚举；`characterToMap` 静态归属 StoryCharacterService（Task 1 骨架→Task 2 正式）无循环依赖；`getGeneratorType()` 已核实。

**占位符扫描**：Task 8 测试已补全为完整类；Task 9 复用已核实的 `call(name, inputJson)` helper；其余步骤均含可执行代码/命令。