# Phase 5 评论类漫剧自动化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让工程支持「上传/批量入库动漫截图与短片段 → 解说驱动自动对齐 → 一键合成数分钟评论类成片」，并先用脚本级自动化发出《钱坑漫评01》。

**Architecture:** 切片 0 为产品外 Node 脚本（JSON→FFmpeg overlay 自动拼接）立即发片；产品内新增 **独立评论工作台页面 `/commentary-studio`**（用户决策 2026-11-15：与导演工作台 video-studio 分离），后端复用 StoryScript/StoryShot（+format 分离、+insertAttribution 引用标记）、新增 MediaAsset 素材库、合成自动对齐（TTS 预探测+短片段 loop）、逐镜出处水印与引用占比护栏、文件夹批量入库与异步导出。复用既有混剪合成/上传/存储/异步 idiom，不造新轮子。

**Tech Stack:** Spring Boot 3.4 / Java 17 / JPA H2(ddl-auto:update) / FFmpeg+ffprobe / edge-tts / Node 24（工具脚本）/ Thymeleaf 原生 JS 前端。

**Spec:** docs/story/FX/kurumi-commentary-domestic.md（评论类脚本与插入策略）；可行性分析见会话 2026-11-15（混剪/静音/上传/ffprobe/水印/异步六项代码实证）。

## Global Constraints

- 基线以执行起点全量 `mvn test` 实测为准（含已暂存 ComfyUI 切片新增测试）；每切片结束全量绿后才进下一切片。
- 无 Flyway：新列一律可空；新表 ddl-auto 自动创建。
- 存储一律走 StorageService（upload/getAccessUrl/download，local/oss/minio/fastdfs）；multipart 上限 100MB 已配置。
- FFmpeg/ffprobe 路径取配置（video.ffmpeg-path/video.ffprobe-path）；长任务 ProcessBuilder 必须 redirectOutput(DISCARD) 或落盘先 waitFor。
- **页面分离**：评论类 UI 全部在 `templates/commentary-studio.html`；`video-studio.html` 仅加导航链接；导演列表默认不含 commentary（服务层默认过滤保证，前端零改动）。两页主 script 块均保持 `th:inline="none"`；SecurityConfig permitAll 保持含 "/error" 并新增 "/commentary-studio"。
- 版权护栏产品化：插入镜（insertAttribution 非空）时长占比 ≤ `video.commentary.max-insert-ratio`（默认 0.2），超占比导出拒绝；插入镜必须带出处水印。
- 插入镜原声默认丢弃（合成仅 map 视频标签，现状即如此，不得引入输入音频 map）。
- **不主动 git commit**：每切片末仅 `git add` 暂存，提交等用户指令。
- PowerShell 5.1 用 `;` 分隔命令；mvn 后台跑 + GetTerminalOutput；禁止 mvn 与 SearchReplace 并行同文件。
- 真实 FFmpeg 冒烟用 `-D` 开关 gated（沿用 -Dtts/-Drestyle idiom，新增 -Dcommentary=true）。

---

### Task 0: 脚本级自动拼接（方向 4，先行发片）

**Files:**
- Create: `tools/auto-commentary-concat.js`
- Create: `tools/test-auto-commentary-concat.js`
- Modify: `docs/story/FX/kurumi-commentary-domestic.md`（§4 增加脚本用法）

**Interfaces:**
- Consumes: `docs/story/FX/kurumi-commentary-domestic.json`（shots[].durationSeconds/insert{id,seconds,attribution}）
- Produces: 命令行 `node tools/auto-commentary-concat.js --script <json> --main <mp4> --assets <dir> --out <mp4> [--dry-run]`；素材约定文件名 `insert-<id>.mp4|png`；缺失素材跳过并 warn（该镜保留主体画面）。

- [ ] **Step 1: 写失败测试（时间轴数学+命令结构）**

```js
// tools/test-auto-commentary-concat.js
const assert = require('assert');
const { shotStarts, buildCommand } = require('./auto-commentary-concat.js');
const shots = [
  { durationSeconds: 8, insert: null },
  { durationSeconds: 8, insert: { id: 'A', seconds: 3, attribution: '第1话 · 评论引用' } },
  { durationSeconds: 10, insert: { id: 'B', seconds: 3, attribution: '第4话 · 评论引用' } }
];
// xfade=0.8：start_i = sum(dur[0..i-1]) - i*0.8
assert.deepStrictEqual(shotStarts(shots), [0, 7.2, 14.4]);
const cmd = buildCommand({
  main: 'main.mp4', out: 'out.mp4', width: 1280, height: 720,
  inserts: [{ id: 'A', file: 'insert-A.mp4', start: 7.2, seconds: 3, attribution: '第1话 · 评论引用', isImage: false }]
});
const s = cmd.join(' ');
assert.ok(s.includes("overlay=enable='between(t,7.20,10.20)'"));
assert.ok(s.includes('drawtext='));
assert.ok(s.includes('-c:a copy')); // 主体音轨（解说/BGM/字幕烧录已完成）不动
assert.ok(!s.includes('insert-B')); // 未提供素材不进入命令
console.log('TEST_OK');
```

- [ ] **Step 2: 跑测试确认失败**

Run: `node tools/test-auto-commentary-concat.js`
Expected: FAIL（Cannot find module './auto-commentary-concat.js'）

- [ ] **Step 3: 实现脚本**

```js
// tools/auto-commentary-concat.js
const fs = require('fs'); const path = require('path');
const { execFileSync } = require('child_process');
const XFADE = 0.8;
function shotStarts(shots) {
  let acc = 0; return shots.map((s, i) => { const st = acc - i * XFADE; acc += s.durationSeconds; return Math.max(0, +st.toFixed(2)); });
}
function esc(t) { return t.replace(/\\/g, '\\\\').replace(/:/g, '\\:').replace(/'/g, "\\'"); }
function buildCommand({ main, out, width, height, inserts }) {
  const cmd = ['ffmpeg', '-y', '-i', main];
  const filters = []; let prev = '[0:v]';
  inserts.forEach((ins, k) => {
    if (ins.isImage) cmd.push('-loop', '1');
    cmd.push('-i', ins.file);
    const idx = k + 1;
    filters.push(`[${idx}:v]trim=duration=${ins.seconds},setpts=PTS-STARTPTS,fps=30,` +
      `scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height},` +
      `drawtext=text='${esc(ins.attribution)}':fontsize=${Math.max(16, height / 30)}:fontcolor=white@0.85:` +
      `x=w-tw-${Math.round(height / 40)}:y=h-th-${Math.round(height / 40)}[ins${k}]`);
    const end = +(ins.start + ins.seconds).toFixed(2);
    const label = k === inserts.length - 1 ? '[vout]' : `[ov${k}]`;
    filters.push(`${prev}[ins${k}]overlay=enable='between(t,${ins.start.toFixed(2)},${end.toFixed(2)})'${label}`);
    prev = label;
  });
  if (inserts.length) {
    cmd.push('-filter_complex', filters.join(';'), '-map', '[vout]', '-map', '0:a');
  } else {
    cmd.push('-map', '0:v', '-map', '0:a');
  }
  cmd.push('-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', '-c:a', 'copy', '-movflags', '+faststart', out);
  return cmd;
}
function main() {
  const a = process.argv.slice(2); const get = k => a[a.indexOf(k) + 1];
  const script = JSON.parse(fs.readFileSync(get('--script'), 'utf8'));
  const assets = get('--assets'); const dry = a.includes('--dry-run');
  const starts = shotStarts(script.shots);
  const inserts = [];
  script.shots.forEach((s, i) => {
    if (!s.insert) return;
    const mp4 = path.join(assets, `insert-${s.insert.id}.mp4`);
    const png = path.join(assets, `insert-${s.insert.id}.png`);
    const file = fs.existsSync(mp4) ? mp4 : fs.existsSync(png) ? png : null;
    if (!file) { console.warn(`WARN insert ${s.insert.id} missing, skip`); return; }
    inserts.push({ id: s.insert.id, file, start: starts[i], seconds: s.insert.seconds,
      attribution: s.insert.attribution, isImage: file.endsWith('.png') });
  });
  const cmd = buildCommand({ main: get('--main'), out: get('--out'),
    width: script.aspectRatio === '9:16' ? 720 : 1280, height: script.aspectRatio === '9:16' ? 1280 : 720, inserts });
  if (dry) { console.log(cmd.join(' ')); return; }
  execFileSync(cmd[0], cmd.slice(1), { stdio: ['ignore', 'ignore', 'inherit'] });
  console.log('CONCAT_OK ' + get('--out'));
}
if (require.main === module) main();
module.exports = { shotStarts, buildCommand };
```

- [ ] **Step 4: 跑测试确认通过**

Run: `node tools/test-auto-commentary-concat.js`
Expected: TEST_OK

- [ ] **Step 5: 合成冒烟（lavfi 造主体+插入素材，验证 overlay 与时长）**

Run: `ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=24:size=1280x720:rate=30 -f lavfi -i sine=frequency=440:duration=24 -c:v libx264 -c:a aac target/cc-main.mp4 ; ffmpeg -y -loglevel error -f lavfi -i testsrc2=duration=3:size=1280x720:rate=30 -c:v libx264 target/insert-A.mp4 ; node tools/auto-commentary-concat.js --script docs/story/FX/kurumi-commentary-domestic.json --main target/cc-main.mp4 --assets target --out target/cc-out.mp4`
Expected: CONCAT_OK；`ffprobe -v error -show_entries format=duration -of csv=p=0 target/cc-out.mp4` ≈ 24（overlay 不改变总时长）；仅 insert-A 存在其余 WARN skip。

- [ ] **Step 6: 文档用法 + 暂存**

在 kurumi-commentary-domestic.md §4 追加：主体由评论工作台导出后，`node tools/auto-commentary-concat.js --script ... --main <主体mp4> --assets <截图目录> --out <成片mp4>`；截图命名 insert-A.mp4 … insert-E.mp4（或 .png）。
Run: `git add tools/auto-commentary-concat.js tools/test-auto-commentary-concat.js docs/story/FX/kurumi-commentary-domestic.md`

---

### Task 1: MediaAsset 素材库（上传+入库裁剪+探测，纯后端）

**Files:**
- Create: `entity/MediaAsset.java`, `repository/MediaAssetRepository.java`, `service/MediaAssetService.java`, `controller/MediaAssetController.java`
- Test: `service/MediaAssetServiceTest.java`

**Interfaces:**
- Produces: `MediaAssetService.upload(MultipartFile file, String name, String tags, Double trimStart, Double trimEnd, Long userId) -> MediaAsset`；`ingestPath(Path p, String name, String tags, Long userId)`（Task 6 复用）；包私有 seam `trimVideo(Path src, double start, double end, Path dst)`、`probe(Path)` 委托 VideoComposeService.probeDurationSeconds。
- Consumes: StorageService.upload(InputStream,fileName,contentType)→fileId / getAccessUrl(fileId)→url / download；VideoComposeService.probeDurationSeconds。

- [ ] **Step 1: 写失败测试**

```java
// MediaAssetServiceTest 关键用例（Mockito mock StorageService/VideoComposeService）
@Test void videoUploadProbesAndStoresDuration() { /* upload 返回 fileId、getAccessUrl 返回 url；probe=7 → asset.durationSeconds=7, kind=VIDEO */ }
@Test void trimParamsProduceTrimmedAsset() { /* trimStart=10,trimEnd=13 → trimVideo 被调用；probe(trimmed)=3 → duration=3 */ }
@Test void imageUploadSkipsProbe() { /* kind=IMAGE, duration null, trimVideo 零交互 */ }
@Test void foreignUserCannotDelete() { /* userId 不符抛 IllegalArgumentException */ }
```

- [ ] **Step 2: 跑测试确认失败** — Run: `mvn test -Dtest=MediaAssetServiceTest`；Expected: 编译失败（类不存在）。
- [ ] **Step 3: 实现实体/仓库/服务/控制器**

实体字段：id、userId(nullable=false)、name(200)、kind(10)、fileId(500)、url(1000)、durationSeconds(Integer 可空)、tags(500)、sourceNote(500)、createdAt；@PrePersist。
服务要点：contentType 以 video/* 判 VIDEO 否则 IMAGE；storageService.upload 得 fileId、getAccessUrl 得 url；VIDEO 先 probe 原片；trimStart/trimEnd 非空时 `trimVideo`（ffmpeg -ss start -to end -i src -c:v libx264 -crf 18 -an dst，DISCARD stdout）后对 dst probe 并 upload 替换；控制器 POST /api/video/assets（multipart file+name+tags+trimStart+trimEnd）、GET /api/video/assets、DELETE /{id}；登录校验沿用服务层 idiom。
- [ ] **Step 4: 跑测试确认通过** — Run: `mvn test -Dtest=MediaAssetServiceTest`；Expected: 4 绿。
- [ ] **Step 5: 全量回归 + 暂存** — Run: `mvn test`；Expected: 基线+4 绿；`git add` 本切片文件。

---

### Task 2: 评论独立页 + 脚本 format 分离 + JSON 导入

**Files:**
- Modify: `entity/StoryScript.java`（+format）、`entity/StoryShot.java`（+insertAttribution）
- Modify: `service/StoryScriptService.java`（listScripts(format)/importCommentary/updateFormat/updateShot 白名单/scriptToMap/shotToMap）
- Modify: `controller/StoryScriptController.java`（GET list +format 参、POST /commentary/import、saveScript  honoring format、scriptToListMap +format、mapToStoryShot +insertAttribution）
- Modify: `controller/PageController.java`（+/commentary-studio）、`config/SecurityConfig.java`（permitAll +"/commentary-studio"）
- Create: `templates/commentary-studio.html`
- Modify: `templates/video-studio.html`（仅 header 加 `<a th:href="@{/commentary-studio}">评论工作台</a>` 导航链接）
- Test: `service/StoryScriptCommentaryFormatTest.java`、`controller/CommentaryPageRenderTest.java`

**Interfaces:**
- Produces: `StoryScript.getFormat/setFormat`（null=导演，"commentary"=评论）；`StoryShot.getInsertAttribution/setInsertAttribution`（非空=引用镜）；`listScripts(String format)`（"commentary"→仅评论；null/其他→排除评论；无参 listScripts() 委托 null）；`importCommentary(Map json) -> StoryScript`；`updateFormat(scriptId, format)`；POST `/api/video/scripts/commentary/import`；GET `/api/video/scripts?format=commentary`；页面 `/commentary-studio`。
- Consumes: 既有 getCurrentUserId()（SecurityContextHolder+userRepository.findByUsername）、updateShot 白名单 idiom、POST /api/video/images/shot/{scriptId}/{shotId}、POST /api/video/export/hd（body: scriptId/aspectRatio/bgmId/subtitle/subtitleLanguage/withLogo/upscale/interpolate/sfx）、GET /api/video/exports/{scriptId}、POST /{id}/shots（平铺镜头字段+position）。

- [ ] **Step 1: 写失败测试**

```java
// service/StoryScriptCommentaryFormatTest.java
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:commentaryfmt"})
class StoryScriptCommentaryFormatTest {
  @Autowired StoryScriptService service;
  @Autowired UserRepository userRepository;
  @BeforeEach void login() {
    User u = new User(); u.setUsername("fmt-user"); u.setPassword("x");
    userRepository.save(u);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "fmt-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }
  @AfterEach void logout() { SecurityContextHolder.clearContext(); }

  @Test void importCreatesCommentaryScriptWithAttribution() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("title", "钱坑漫评01");
    json.put("shots", List.of(
        Map.of("durationSeconds", 8, "narration", "n1"),
        Map.of("durationSeconds", 6, "narration", "n2",
            "insert", Map.of("id", "A", "seconds", 3, "attribution", "来源：第1话 · 评论引用"))));
    StoryScript s = service.importCommentary(json);
    assertEquals("commentary", s.getFormat());
    assertEquals(2, s.getShots().size());
    assertEquals("来源：第1话 · 评论引用", s.getShots().get(1).getInsertAttribution());
    assertNull(s.getShots().get(0).getInsertAttribution());
  }
  @Test void listSeparatesCommentaryFromDirector() {
    service.importCommentary(Map.of("title", "c", "shots", List.of(Map.of("durationSeconds", 5))));
    Storyboard sb = new Storyboard(); sb.setTitle("d"); sb.setShots(List.of());
    service.saveFromStoryboard(null, sb, "");
    assertEquals(1, service.listScripts("commentary").size());
    assertEquals(1, service.listScripts().size());
    assertEquals("d", service.listScripts().get(0).getTitle());
  }
  @Test void importRejectsEmptyShots() {
    assertThrows(IllegalArgumentException.class, () -> service.importCommentary(Map.of("title", "x")));
  }
  @Test void updateShotPersistsAttribution() {
    StoryScript s = service.importCommentary(Map.of("title", "c", "shots", List.of(Map.of("durationSeconds", 5))));
    Long shotId = s.getShots().get(0).getId();
    service.updateShot(s.getId(), shotId, Map.of("insertAttribution", "来源：第4话"));
    assertEquals("来源：第4话", service.getScript(s.getId()).getShots().get(0).getInsertAttribution());
  }
}
```

```java
// controller/CommentaryPageRenderTest.java（沿用 VideoStudioPageRenderTest idiom）
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:commentarypage"})
@AutoConfigureMockMvc
class CommentaryPageRenderTest {
  @Autowired MockMvc mockMvc;
  @Test void authenticatedPageRendersCoreControls() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("SPRING_SECURITY_CONTEXT",
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken("page-tester", null, null)));
    mockMvc.perform(get("/commentary-studio").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("commentaryScriptList")))
        .andExpect(content().string(containsString("function importCommentaryJson")))
        .andExpect(content().string(containsString("function saveShot")))
        .andExpect(content().string(containsString("cExportBtn")));
  }
  @Test void anonymousRedirectsToLogin() throws Exception {
    mockMvc.perform(get("/commentary-studio")).andExpect(status().is3xxRedirection());
  }
}
```

- [ ] **Step 2: 跑确认失败** — `mvn test -Dtest=StoryScriptCommentaryFormatTest,CommentaryPageRenderTest`；Expected: 编译失败。
- [ ] **Step 3: 实现后端分离+导入**

实体（可空列，ddl-auto 自动加）：
```java
// StoryScript
/** Phase 5：脚本形态 null=导演工作台，commentary=评论工作台（列表分离） */
@Column(name = "format", length = 20)
private String format;
public String getFormat() { return format; }
public void setFormat(String format) { this.format = format; }
// StoryShot
/** Phase 5：引用镜出处水印（非空=引用镜，受占比护栏与 drawtext 约束） */
@Column(name = "insert_attribution", length = 200)
private String insertAttribution;
public String getInsertAttribution() { return insertAttribution; }
public void setInsertAttribution(String insertAttribution) { this.insertAttribution = insertAttribution; }
```

服务：
```java
public List<StoryScript> listScripts() { return listScripts(null); }

/** format="commentary" 仅评论；null/其他 排除评论（导演页零改动即分离） */
public List<StoryScript> listScripts(String format) {
  Long userId = getCurrentUserId();
  List<StoryScript> all = scriptRepository.findByUserIdOrderByUpdatedAtDesc(userId);
  if ("commentary".equals(format)) {
    return all.stream().filter(s -> "commentary".equals(s.getFormat())).collect(Collectors.toList());
  }
  return all.stream().filter(s -> !"commentary".equals(s.getFormat())).collect(Collectors.toList());
}

@Transactional
public StoryScript importCommentary(Map<String, Object> json) {
  StoryScript script = new StoryScript();
  script.setUserId(getCurrentUserId());
  script.setFormat("commentary");
  script.setTitle(str(json.get("title"), "未命名漫评"));
  script.setTheme(str(json.get("theme"), ""));
  script.setStyle(str(json.get("style"), null));
  script.setStatus("DRAFT");
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> shots = (List<Map<String, Object>>) json.get("shots");
  if (shots == null || shots.isEmpty()) throw new IllegalArgumentException("shots 不能为空");
  int order = 1;
  for (Map<String, Object> s : shots) {
    StoryShot shot = new StoryShot();
    shot.setShotOrder(order++);
    shot.setDurationSeconds(intVal(s.get("durationSeconds"), 5));
    shot.setNarration(str(s.get("narration"), null));
    shot.setSceneDescription(str(s.get("sceneDescription"), null));
    shot.setImagePrompt(str(s.get("imagePrompt"), null));
    shot.setShotSize(normalizeShotSize(str(s.get("shotSize"), null)));
    shot.setCameraMovement(str(s.get("cameraMovement"), "固定"));
    @SuppressWarnings("unchecked")
    Map<String, Object> ins = (Map<String, Object>) s.get("insert");
    if (ins != null) shot.setInsertAttribution(str(ins.get("attribution"), "评论引用"));
    shot.setImageStatus("PENDING");
    script.addShot(shot);
  }
  return scriptRepository.save(script);
}

@Transactional
public StoryScript updateFormat(Long scriptId, String format) {
  StoryScript script = getScript(scriptId);
  script.setFormat(format);
  script.setUpdatedAt(LocalDateTime.now());
  return scriptRepository.save(script);
}

private static String str(Object v, String def) { return v instanceof String s && !s.isBlank() ? s : def; }
private static int intVal(Object v, int def) { return v instanceof Number n ? n.intValue() : def; }
```
updateShot 白名单追加：`if (updates.containsKey("insertAttribution")) shot.setInsertAttribution((String) updates.get("insertAttribution"));`
scriptToMap 追加 `map.put("format", script.getFormat());`；shotToMap 追加 `map.put("insertAttribution", shot.getInsertAttribution());`

控制器：GET list 加 `@RequestParam(required = false) String format` 传 `listScripts(format)`；saveScript 在 save 后 `String format = (String) request.get("format"); if (format != null && !format.isBlank()) { storyScriptService.updateFormat(script.getId(), format); script = storyScriptService.getScript(script.getId()); }`；scriptToListMap 追加 format；mapToStoryShot 追加 `shot.setInsertAttribution((String) data.get("insertAttribution"));`；新增：
```java
@PostMapping("/commentary/import")
public ResponseEntity<Map<String, Object>> importCommentary(@RequestBody Map<String, Object> request) {
  Map<String, Object> result = new LinkedHashMap<>();
  try {
    StoryScript script = storyScriptService.importCommentary(request);
    result.put("success", true);
    result.put("data", scriptToMap(script));
    return ResponseEntity.ok(result);
  } catch (RuntimeException e) {
    result.put("success", false);
    result.put("message", e.getMessage());
    return ResponseEntity.badRequest().body(result);
  }
}
```
PageController 沿用 /video-studio 匿名 redirect:/login idiom 增 `/commentary-studio` 返回 "commentary-studio"；SecurityConfig permitAll 列表 "/video-studio" 旁加 "/commentary-studio"。

- [ ] **Step 4: 实现评论工作台页面**

`templates/commentary-studio.html`（主 script 必须 `th:inline="none"`；内联最小样式，不依赖外部 css）：
```html
<!DOCTYPE html>
<html lang="zh" xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"><title>评论工作台</title>
<style>body{font-family:system-ui;margin:16px}section{border:1px solid #ccc;padding:12px;margin:8px 0}
.shot-card{border:1px solid #ddd;padding:8px;margin:8px 0}label{display:block;margin:4px 0}
textarea,input[type=text]{width:95%}.quote-badge{background:#c0392b;color:#fff;padding:0 6px;border-radius:4px}</style>
</head>
<body>
<header><h1>评论工作台 · 漫评自动化</h1><nav><a th:href="@{/video-studio}">导演工作台</a></nav></header>
<section id="scriptPanel"><h2>评论脚本</h2>
  <input id="newTitle" placeholder="标题"><button onclick="createEmpty()">新建</button>
  <textarea id="importJson" rows="6" placeholder="粘贴评论 JSON（kurumi-commentary-domestic.json schema）"></textarea>
  <input type="file" id="importFile" accept=".json,application/json">
  <button onclick="importCommentaryJson()">导入 JSON</button>
  <ul id="commentaryScriptList"></ul>
</section>
<section id="editorPanel" hidden><h2 id="editorTitle"></h2>
  <div id="shotList"></div>
  <button onclick="addShot()">添加镜头</button>
  <h3>导出</h3>
  <select id="cAspect"><option value="9:16">9:16 抖音/小红书</option><option value="16:9">16:9 B站</option></select>
  <label><input type="checkbox" id="cSubtitle" checked> 字幕</label>
  <label><input type="checkbox" id="cSfx" checked> SFX</label>
  <label><input type="checkbox" id="cUpscale"> 超分</label>
  <label><input type="checkbox" id="cInterpolate"> 补帧</label>
  <button id="cExportBtn" onclick="exportCommentary()">导出高清</button>
  <span id="cExportStatus"></span>
  <ul id="cExportHistory"></ul>
</section>
<script th:inline="none">
const $ = id => document.getElementById(id);
let currentScript = null;
function esc(t) { return String(t == null ? '' : t).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
async function api(url, opt) {
  const r = await fetch(url, opt);
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.success === false) throw new Error(j.message || ('HTTP ' + r.status));
  return j;
}
const post = (u, b) => api(u, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b || {}) });
const put = (u, b) => api(u, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b || {}) });
async function loadCommentaryScripts() {
  const j = await api('/api/video/scripts?format=commentary');
  $('commentaryScriptList').innerHTML = j.data.map(s =>
    '<li><button onclick="openScript(' + s.id + ')">' + esc(s.title) + '</button> ' + (s.shotCount || 0) + ' 镜</li>').join('');
}
async function openScript(id) {
  const j = await api('/api/video/scripts/' + id);
  currentScript = j.data;
  $('editorPanel').hidden = false;
  $('editorTitle').textContent = currentScript.title;
  renderShots(); loadExportHistory();
}
function renderShots() {
  $('shotList').innerHTML = currentScript.shots.map(s =>
    '<div class="shot-card"><b>镜 ' + s.shotOrder + '</b> ' + esc(s.shotSize) + ' '
    + (s.insertAttribution ? '<span class="quote-badge">引用</span>' : '')
    + '<label>解说<textarea id="nar-' + s.id + '" rows="3">' + esc(s.narration) + '</textarea></label>'
    + '<label>时长s<input id="dur-' + s.id + '" type="number" min="1" value="' + s.durationSeconds + '"></label>'
    + '<label>出处水印<input id="attr-' + s.id + '" value="' + esc(s.insertAttribution) + '" placeholder="非空=引用镜，如 来源：第X话"></label>'
    + '<label>主视觉提示词<textarea id="prompt-' + s.id + '" rows="2">' + esc(s.imagePrompt) + '</textarea></label>'
    + (s.imageUrl ? '<img src="' + esc(s.imageUrl) + '" style="max-width:240px">' : '')
    + (s.videoUrl ? '<video src="' + esc(s.videoUrl) + '" controls style="max-width:240px"></video>' : '')
    + '<button onclick="saveShot(' + s.id + ')">保存解说</button>'
    + '<button onclick="savePrompt(' + s.id + ')">保存提示词</button>'
    + '<button onclick="genImage(' + s.id + ')">生成主视觉</button>'
    + '<button onclick="removeShot(' + s.id + ')">删除</button></div>').join('');
}
async function saveShot(id) {
  await put('/api/video/scripts/' + currentScript.id + '/shots/' + id, {
    narration: $('nar-' + id).value, durationSeconds: +$('dur-' + id).value,
    insertAttribution: $('attr-' + id).value });
  await openScript(currentScript.id);
}
async function savePrompt(id) {
  await put('/api/video/scripts/' + currentScript.id + '/shots/' + id, { imagePrompt: $('prompt-' + id).value });
  await openScript(currentScript.id);
}
async function genImage(id) {
  await post('/api/video/images/shot/' + currentScript.id + '/' + id);
  $('cExportStatus').textContent = '主视觉生成中…';
  setTimeout(() => openScript(currentScript.id), 5000);
}
async function addShot() {
  await post('/api/video/scripts/' + currentScript.id + '/shots', { durationSeconds: 5, narration: '' });
  await openScript(currentScript.id);
}
async function removeShot(id) {
  await api('/api/video/scripts/' + currentScript.id + '/shots/' + id, { method: 'DELETE' });
  await openScript(currentScript.id);
}
async function createEmpty() {
  const j = await post('/api/video/scripts/commentary/import',
    { title: $('newTitle').value || '未命名漫评', theme: '', shots: [{ durationSeconds: 5, narration: '' }] });
  await loadCommentaryScripts(); openScript(j.data.id);
}
async function importCommentaryJson() {
  let text = $('importJson').value.trim();
  const f = $('importFile').files[0];
  if (f) text = await f.text();
  if (!text) { alert('粘贴 JSON 或选择文件'); return; }
  const j = await post('/api/video/scripts/commentary/import', JSON.parse(text));
  await loadCommentaryScripts(); openScript(j.data.id);
}
async function exportCommentary() {
  $('cExportBtn').disabled = true; $('cExportStatus').textContent = '导出中…';
  try {
    await post('/api/video/export/hd', { scriptId: currentScript.id, aspectRatio: $('cAspect').value,
      subtitle: $('cSubtitle').checked, subtitleLanguage: 'zh', withLogo: true,
      upscale: $('cUpscale').checked, interpolate: $('cInterpolate').checked, sfx: $('cSfx').checked });
    $('cExportStatus').textContent = '导出完成'; loadExportHistory();
  } catch (e) { $('cExportStatus').textContent = '导出失败: ' + e.message; }
  finally { $('cExportBtn').disabled = false; }
}
async function loadExportHistory() {
  const j = await api('/api/video/exports/' + currentScript.id);
  $('cExportHistory').innerHTML = (j.data || []).map(x =>
    '<li>' + esc(x.status) + (x.videoUrl ? ' <a href="' + esc(x.videoUrl) + '" target="_blank">下载</a>' : '') + '</li>').join('');
}
loadCommentaryScripts();
</script>
</body>
</html>
```
video-studio.html header 导航区加 `<a th:href="@{/commentary-studio}">评论工作台</a>`（仅此一处改动）。

- [ ] **Step 5: 跑确认通过** — `mvn test -Dtest=StoryScriptCommentaryFormatTest,CommentaryPageRenderTest`；Expected: 6 绿。
- [ ] **Step 6: 全量回归 + 浏览器冒烟 + 暂存** — 全量 `mvn test` 绿；启动实例→登录→/commentary-studio 导入 kurumi-commentary-domestic.json→10 镜可见、引用徽章 5 处→改解说保存→/video-studio 列表不含该脚本→停止实例；`git add` 本切片文件。

---

### Task 3: 镜头素材绑定 + 评论页素材库 UI

**Files:**
- Modify: `service/StoryScriptService.java`（bindAsset）、`controller/StoryScriptController.java`（POST /{id}/shots/{shotId}/asset）
- Modify: `templates/commentary-studio.html`（assetPanel+上传 trim 表单+openAssetPicker+renderShots 加「绑定素材」按钮）
- Test: `service/StoryScriptServiceAssetBindTest.java`

**Interfaces:**
- Consumes: MediaAssetRepository（Task 1）；StoryShot.imageUrl/imageFileId/videoUrl/videoFileId/imageStatus/videoStatus/durationSeconds。
- Produces: `bindAsset(scriptId, shotId, assetId, boolean asVideo, Integer durationOverride)`：归属双校验（script 属当前用户、asset 属当前用户）；asVideo → videoUrl/videoFileId/videoStatus=READY 且 durationSeconds=override||asset.durationSeconds；否则 imageUrl/imageFileId/imageStatus=READY（不写 video 字段）。

- [ ] **Step 1: 写失败测试**（绑定回写时长/归属拒绝/IMAGE 不写 video 字段 三用例，mock repo，沿用 Task 2 登录 idiom）。
- [ ] **Step 2: 跑确认失败** — `mvn test -Dtest=StoryScriptServiceAssetBindTest`。
- [ ] **Step 3: 实现服务+端点**（端点 body {assetId, asVideo, durationOverride}；服务 @Transactional，save 后返回 shotToMap）。
- [ ] **Step 4: 评论页 UI**：assetPanel（默认 hidden，header 按钮切换）含上传表单（file/name/tags/trimStart/trimEnd → FormData POST /api/video/assets）、素材列表（kind 徽章+时长+删除按钮）；`openAssetPicker(shotId)` 弹 modal 列素材（checkbox「作为视频片段」决定 asVideo）→ POST asset 端点 → openScript 刷新；renderShots 每卡加 `<button onclick="openAssetPicker(ID)">绑定素材</button>`。保持 th:inline="none"。
- [ ] **Step 5: 跑确认通过** + 全量绿。
- [ ] **Step 6: 浏览器冒烟**（启动→上传 3s lavfi 片段（trim 1-4）→素材库可见时长 3→绑定镜头 asVideo→卡片 video 预览+时长回写）+ `git add`。

---

### Task 4: 合成自动对齐（TTS 预探测 + 短片段 loop）

**Files:**
- Modify: `video/VideoComposeService.java`
- Test: `video/VideoComposeAutoFitTest.java`

**Interfaces:**
- Consumes: generateTtsAudio/concatAudioWithSilence/probeDurationSeconds。
- Produces: 包私有 static seam `videoInputFilter(int idx, int effectiveSec, boolean needsLoop, int w, int h, int fps)`；compose 内 effectiveDurations = max(shot.duration, ceil(probe(shotTtsAudio)))；needsLoop 的真片段输入加 `-stream_loop -1` 且滤镜段含 `trim=duration=effectiveSec`；xfade offsets/audio concat/exactDuration 全部改用 effectiveDurations。

- [ ] **Step 1: 写失败测试**

```java
@Test void shortClipGetsLoopAndTrim() {
  String f = VideoComposeService.videoInputFilter(1, 10, true, 1280, 720, 30);
  assertTrue(f.contains("trim=duration=10"));
}
@Test void longClipNoLoop() {
  String f = VideoComposeService.videoInputFilter(1, 3, false, 1280, 720, 30);
  assertFalse(f.contains("trim=duration="));
}
```
加真实 FFmpeg 用例（-Dcommentary=true gated）：2s 片段+8s 静音音频 → composeMediaForTest 输出 ffprobe=8s 不饿死。
- [ ] **Step 2: 跑确认失败**。
- [ ] **Step 3: 实现**（TTS 生成后逐镜 probe 合并音频得 effective；视频输入循环标志数组传入 composeVideoWithFfmpeg；单镜头 buildSingleVideoClip 同样 needsLoop 分支）。
- [ ] **Step 4: 跑确认通过**；全量绿（存量混剪用例不回归）。
- [ ] **Step 5: `git add`**。

---

### Task 5: 逐镜出处水印 + 引用占比护栏

**Files:**
- Modify: `video/VideoComposeService.java`（ComposeOptions.attributions + 滤镜段 drawtext）、`service/VideoExportService.java`（占比校验）
- Modify: `resources/application.yml`（video.commentary.max-insert-ratio: 0.2）
- Modify: `templates/commentary-studio.html`（编辑器顶部引用占比徽章，客户端预算示；服务端护栏权威）
- Test: `video/VideoComposeAttributionTest.java`、`service/VideoExportInsertGuardTest.java`

**Interfaces:**
- Produces: ComposeOptions.attributions(List<String> 与 shots 平行)；seam `attributionFilter(String text, int h)` 返回 drawtext 段（空串返回 ""）；导出前置校验：insertShots=insertAttribution 非空；ratio=Σdur(insert)/Σdur(all)；ratio>max 抛 IllegalArgumentException("引用占比超限…")。
- Consumes: StoryShot.insertAttribution（Task 2 已建列+shotToMap 已带字段，**枚举全部 map 组装点**确认无断链，沿用 cameraMovement 教训）。

- [ ] **Step 1: 写失败测试**（drawtext 含出处文本/空串无段；ratio 0.25>0.2 拒绝、0.15 放行、无插入镜放行）。
- [ ] **Step 2: 跑确认失败**。
- [ ] **Step 3: 实现**（真片段与静图滤镜段均在 setpts 前插 attributionFilter；VideoExportService 组装 ComposeOptions 时从 shots 填 attributions；doExport 开头校验；页面徽章 `引用占比 X%（上限20%）` 超限时红色提示）。
- [ ] **Step 4: 跑确认通过**；全量绿。
- [ ] **Step 5: `git add`**。

---

### Task 6: 文件夹批量入库 + 异步导出 + 文档同步

**Files:**
- Modify: `service/MediaAssetService.java`（scanImportDir）、`controller/MediaAssetController.java`（POST /api/video/assets/scan）
- Modify: `service/VideoExportService.java`（exportHdAsync + futures + awaitAndGetForTest）、`controller/VideoExportController.java`（POST /export/hd/async、GET /export/jobs/{id}）
- Modify: `resources/application.yml`（video.commentary.import-dir: ""）
- Modify: `templates/commentary-studio.html`（assetPanel「扫描入库」按钮；导出区「后台导出」按钮+jobs 轮询态）
- Test: `service/MediaAssetScanTest.java`、`service/VideoExportAsyncTest.java`

**Interfaces:**
- Produces: 文件名约定 `ep(\d+)_(标签)_(名称).(mp4|png)` 解析 tags="ep04,标签"；scan 仅读配置目录（不接受用户路径，防遍历）；异步导出复用转绘 idiom：SecurityContext 提交捕获/worker set/finally clear、专用单线程池 @PreDestroy、futures 新提交 removeIf(isDone)、包私有 awaitAndGetForTest。
- Consumes: MediaAssetService.ingestPath（Task 1）；exportHdWithPost。

- [ ] **Step 1: 写失败测试**（约定解析/非法文件名跳过/目录未配置返回空；异步提交→await 得 SUCCESS 结果 map；futures 清理用例）。
- [ ] **Step 2: 跑确认失败**。
- [ ] **Step 3: 实现**（同步 /export/hd 保持不动；页面后台导出：POST hd/async 得 jobId → 每 3s GET jobs/{id} 直到 SUCCESS/FAILED → loadExportHistory；轮询终态即停）。
- [ ] **Step 4: 跑确认通过**；全量绿。
- [ ] **Step 5: 浏览器冒烟**（文件夹放 2 个约定命名文件→scan→素材库可见→后台导出 3 分钟级脚本→jobs 轮询到 SUCCESS）+ `git add` + 四双语文档/VIDEO_STUDIO_PLAN 同步 Phase 5 节（评论工作台路由/素材库/护栏/异步导出/工具脚本用法）。

---

## Self-Review 结论

- 覆盖：方向 4=Task 0；方向 1=Task 1-5；方向 2=Task 6 scan；异步长片=Task 6；**页面分离=Task 2**（用户决策）；版权护栏=Task 5。无遗漏。
- 类型一致：format/insertAttribution 于 Task 2 定义、Task 3/5 消费；MediaAsset/attributions/effectiveDurations 命名跨任务统一；xfade=0.8 与现网 XFADE_DURATION 一致；StorageService.upload/getAccessUrl 与接口实证一致；导出 body 键与 video-studio L1168 实证一致。
- 依赖序：Task 2 的 insertAttribution 列先于 Task 5 护栏；Task 1 ingestPath 先于 Task 6 scan；Task 3 绑定先于 Task 4 loop 冒烟（可并行开发但按序执行）。
- 风险预留：AI 打标/自动匹配（方向 3）不在本计划，留后续切片；qwen-vl 视觉端点届时另核。

---

## 执行记录（Task 0 / B0，2026-11-16）

- **红**：`node tools/test-auto-commentary-concat.js` → Cannot find module './auto-commentary-concat.js'。
- **绿**：按计划实现后 TEST_OK（时间轴 xfade=0.8 数学 + overlay/drawtext/-c:a copy 命令结构 + 缺失素材不进命令）。
- **冒烟✅**：lavfi 主体 24s + insert-A 3s → CONCAT_OK，ffprobe=24.000000（overlay 不改总时长）；B-E 缺失 WARN skip 符合预期。
- **计划外修复（冒烟实证，TDD 补环）**：
  1. drawtext 无 fontfile 时本机 fontconfig 缺省配置缺失 → **水印静默不渲染**（抽帧实证）→ 显式 `fontfile='C\:/Windows/Fonts/msyh.ttc'`（微软雅黑中文字形）。
  2. 裸 `C\:` 转义被 filtergraph 图级解析吃掉致选项拆分报错 → **单引号包裹**（两级解析正确形态），测试锁定该字面量。
  3. 冒烟夹具画幅修正：主体须与脚本 aspectRatio 一致（9:16→720×1280），否则插入素材按脚本画幅缩放后水印被裁切（首版 16:9 主体抽帧无水印的根因）。
- **复验**：TEST_OK + CONCAT_OK + 24.000000 + 抽帧目视水印“第1话 · 评论引用”正确渲染。
- **文档**：kurumi-commentary-domestic.md §4.1 用法（含画幅一致约束）；该文件在 .gitignore（docs/story 本地不入库），仅两工具脚本暂存。
- **暂存不提交**。