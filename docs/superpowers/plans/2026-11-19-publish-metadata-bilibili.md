# 发布元数据（B 站标题/简介/标签/分区）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本计划当前状态：仅规划，未开工。** 用户 2026-11-19 明确「先制定计划不开工改代码」。

**Goal:** 把「标题 / 简介 / 标签 / 分区 / 声明」这类发布元数据做成可存、可派生、可导出的一等公民，
让每条成片都能直接产出投稿页可粘贴的发布包。

**Architecture:** 三层，零平台 API 对接。① `PublishSpec` 常量表锁 B 站硬规格（字数/条数/tid）；
② `PublishMetaDeriver` 从**已有确定性数据**（script.idea 事实、StoryShot.insertAttribution 出处台账、
合成日志的镜边界）拼装简介与标签，LLM 只负责标题候选与开场钩子，且被事实护栏夹住；
③ `publish_meta` 表按 (script,user,platform) 唯一存人工值，导出成功后落一份 `*-发布包.txt` 并随 ZIP 附带。

**Tech Stack:** JDK 17 / Spring Boot 3.4.1 / Spring AI 1.0.0（`modelService.getChatClient()` idiom）/
JPA `ddl-auto: update`（无迁移脚本，新表由 Hibernate 建）/ JUnit 5 + Mockito / Thymeleaf 单页
`commentary-studio.html`。

**Spec:** 无独立 spec 文件——本计划内嵌「规格事实」一节即权威来源（含出处与复核日期）。
内容基准与人工稿参照：`docs/story/FX/发布元数据-久留美.md`（Task 4/9 的期望输出对照）；
文案调性沿用既有三平台方案 `docs/story/FX/kurumi-commentary-domestic.md`。
版权策略前提见记忆「评论类动漫视频脚本的版权风险控制决策」（引用≤16% + 逐处出处标注 + 原创角色为主体）。

---

## 0. 现状盘点（代码证据，决定这是新增而非小修）

| 位置 | 现状 |
|---|---|
| `entity/StoryScript.java` | 只有 `title(200)` / `theme(500)` / `idea` / `format` / `aspectRatio`，**无**面向平台的简介、标签、分区、声明字段 |
| `entity/VideoExport.java` | 记录分辨率/画幅/时长/BGM/字幕/`coverUrl`，**无**任何发布文案字段 |
| `video/PlatformPreset.java` | 只有 `displayName/aspectRatio/resolution/width/height/maxDurationSeconds/note`，**无**标题字数、标签条数、简介字数、分区候选 |
| `service/VideoExportService.exportForPlatform` | 仅产出 `durationWarning`（时长预警），文案侧为空 |
| `templates/commentary-studio.html` / `video-studio.html` | 无发布面板；`tags` 只出现在 `MediaAsset`（素材标签）与 booru 检索语境 |
| 唯一沾边的能力 | `StoryShot.insertAttribution`（逐镜出处文本，护栏 `checkInsertRatio` 用它）、`MediaAsset.tags`、`renderVideoCover(url,t,title)` |

结论：**简介里的【素材出处】一节可以直接由 `insertAttribution` 台账派生**——这是 B 站《投稿须知》的硬性要求，
也是本项目版权策略的落点，属于「数据已有、出口缺失」，性价比最高。

---

## Global Constraints（每个 Task 都隐含遵守）

**规格事实（复核日期 2026-11-19，出处见括号）**
- 标题 ≤ **80** 字符；尽量中文；禁「画质/音质测试」「求支持」「无意义/引战」类表述（B 站投稿规范）
- 标签 ≤ **10** 个；策略上填 9 留 1 给观众补 tag（社区通行，非硬规）
- 简介 ≤ **2000** 字（动画区等部分分区），**部分分区仅 250 字** → 简介字数随分区变（知乎 2021 提问 + 实测计数器）
- 分区：`动漫杂谈` **tid=253**（2022-09-27 动画区新增二级分区，定义为「以谈话形式对 ACGN 文化圈的鉴赏、吐槽、
  评点、解说、推荐、科普」）；备选 `影视杂谈` **tid=182**。灰度期投稿页可能隐藏二级分区，可用地址参数 `?tid=253` 指定
- 简介**必须**标注所使用素材（音乐/图片/视频）的原作者或原素材地址（B 站《投稿须知》）
- 封面：与主体内容一致或为视频截图，禁动图，单图 ≥ 640×188；16:10（1146×717）为社区推荐比例
- AI 内容须勾选「含AI生成内容」声明

**工程红线**
1. **事实单一来源**：数字/日期/人名只允许来自 `StoryScript.idea`、`MediaAsset` 台账、`insertAttribution`；
   LLM 的 prompt 里必须写死「不得新增或改写任何数字」，且产出后做**数字集合校验**（LLM 文本出现的数字必须 ⊆ 事实集）。
2. **时间轴唯一口径**：简介时间戳的镜起点 = `Σ(effectiveDurations[0..k-1] − xfade)`，与字幕/音轨床同源
   （本仓库 2026-11-19 刚因「两套轴」踩过口播漂移，见 `docs/story/FX/过夜产出-2026-11-19.md` §4-8）。
   派生器**不得**自写公式，必须消费 Task 3 落库的 `shotStartsCsv`。
3. 引用占比口径要在发布包里注明：in-app 把「带 insertAttribution 的整镜」计为引用，外部拼接工具按「实际插入秒数」计，
   两者数值不同（FX 实测 48.9% vs 15.2%），别在简介里写死一个可能自相矛盾的百分比。
4. 测试判据必须经**变异复证**（项目测试规范）；禁用恒真断言。
5. 每个 Task 末 `git commit`（本会话既有约定，非 Phase 5 时期的「暂存不提交」）。
6. PowerShell 5.1：`.ps1/.bat` 纯 ASCII；命令用 `;` 分隔而非 `&&`。

---

## File Structure

| 文件 | 职责 | Task |
|---|---|---|
| Create `video/publish/PublishSpec.java` | B 站硬规格常量 + 校验入口（纯数据，无依赖） | 1 |
| Create `entity/PublishMeta.java` + `repository/PublishMetaRepository.java` | 发布元数据持久化（按 script+user+platform 唯一） | 2 |
| Create `service/PublishMetaService.java` | upsert + 规格校验 + 归属校验 | 2 |
| Modify `video/VideoComposeService.java`（result 加 `shotStarts`） | 输出镜边界，做时间轴单一真源 | 3 |
| Modify `entity/VideoExport.java` + `service/VideoExportService.java` | 落 `shot_starts_csv`，导出记录携带边界 | 3 |
| Create `service/PublishMetaDeriver.java` | 确定性拼装简介/标签/时间轴/出处（零 LLM） | 4 |
| Create `service/PublishMetaLlmService.java` | 标题候选 ×3 + 开场钩子，含事实护栏与降级 | 5 |
| Create `controller/PublishMetaController.java` | 4 个端点（spec/draft/save/generate-titles） | 6 |
| Modify `templates/commentary-studio.html` | 发布面板（计数器、标签 chip、一键复制整包） | 7 |
| Modify `service/VideoExportService.java` + `LocalStorageService`（发布包文件） | 导出成功落 `*-发布包.txt`，ZIP 附带 | 8 |
| Modify `API_TEST.md` / `README.md` | 端点与字段文档 | 9 |
| Create `tools/e2e-publish-meta.js` | 端到端：存草稿 → 派生 → 校验超限 → 导出发布包 | 9 |

---

## Task 1: B 站发布规格表（PublishSpec）

**Files:**
- Create: `src/main/java/com/github/ylyan2015/springaidemo/video/publish/PublishSpec.java`
- Test: `src/test/java/com/github/ylyan2015/springaidemo/video/publish/PublishSpecTest.java`

**Interfaces:**
- Consumes: 无（叶子组件）
- Produces: `PublishSpec.bilibili()` → `record PublishSpec(int titleMax, int tagMax, int descMax,
  List<Category> categories, List<String> declarationOptions, String coverNote)`；
  `Category(String name, int tid, int descMax)`（`descMax` 按分区变：动画区 2000、部分分区 250）；
  `Category category(String name)` 按名查分区（未知名抛 `IllegalArgumentException`）；
  `static List<String> normalizeTags(List<String> raw)`；
  `PublishSpec.validate(title, tags, desc, tid)` → `List<Violation>`，
  `Violation(String field, String code, String message)`，code ∈ `TITLE_TOO_LONG/TAG_TOO_MANY/DESC_TOO_LONG/TAG_BLANK/TAG_DUPLICATE`

- [ ] **Step 1: 写失败测试**（锁死数值，改一个就得改测试）

```java
@Test void bilibiliHardLimitsMatchPlatformRules() {
    PublishSpec s = PublishSpec.bilibili();
    assertEquals(80, s.titleMax());
    assertEquals(10, s.tagMax());
    assertEquals(2000, s.descMax());          // 动画区档
    assertEquals(253, s.category("动漫杂谈").tid());
    assertEquals(182, s.category("影视杂谈").tid());
    assertTrue(s.declarationOptions().contains("含AI生成内容"));
}
@Test void violationOnlyWhenOverLimit() {
    PublishSpec s = PublishSpec.bilibili();
    assertTrue(s.validate("x".repeat(80), List.of("a"), "y".repeat(2000), 253).isEmpty());
    assertEquals("TITLE_TOO_LONG", s.validate("x".repeat(81), List.of("a"), "", 253).get(0).code());
    assertEquals("DESC_TOO_LONG", s.validate("t", List.of("a"), "y".repeat(2001), 253).get(0).code());
}
@Test void tagNormalisationDropsBlankAndDuplicate() {
    // 大小写/全半角归一后去重：现稿「fx久留美」与「FX战士久留美」不冲突，但「解说」「解说」必须去重
    assertEquals(List.of("解说", "爆仓"),
        PublishSpec.normalizeTags(List.of(" 解说 ", "", "爆仓", "解说")));
}
```

- [ ] **Step 2: 跑测试确认失败**（`mvn -q -o test -Dtest=PublishSpecTest` → 编译期「找不到符号 PublishSpec」＝红）
- [ ] **Step 3: 实现** record + 静态工厂 + `normalizeTags`（`trim` → 折叠空白 → 去重保序 → 丢弃空串）；
  每个数值旁写行内注释标出处与复核日期（防日后规则漂移无人知情）
- [ ] **Step 4: 跑测试确认绿**；**Step 5: 变异复证**（`titleMax` 改 81、`normalizeTags` 去掉重逻辑，
  至少各杀 1 条用例）后回滚；**Step 6: commit** `feat(publish): B 站发布规格表与违规码`

---

## Task 2: publish_meta 表 + upsert 校验服务

**Files:**
- Create: `entity/PublishMeta.java`, `repository/PublishMetaRepository.java`, `service/PublishMetaService.java`
- Test: `src/test/java/.../service/PublishMetaServiceTest.java`

**Interfaces:**
- Consumes: `PublishSpec.validate/normalizeTags`；`StoryScriptService.getScript(scriptId)`（归属判定）
- Produces: `PublishMeta save(Long scriptId, String platform, Map<String,Object> form)`（超规格抛
  `IllegalArgumentException`，message 拼 violation 列表）；`Optional<PublishMeta> find(scriptId, platform)`；
  字段：`id, scriptId, userId, platform, title(200), description(TEXT), tags(CSV), categoryName, categoryTid,
  declaration, collection, pinnedComment(TEXT), timestampsCsv(TEXT), source(MANUAL|GENERATED), createdAt, updatedAt`

- [ ] **Step 1: 写失败测试**

```java
@Test void upsertKeepsSingleRowPerScriptAndPlatform() { /* save 两次 → repository.count()==1，title 取第二次 */ }
@Test void overLimitSaveRejectedWithFieldNames() {
    // 81 字标题必须失败，且 message 含 TITLE_TOO_LONG（前端据此定位输入框）
    var e = assertThrows(IllegalArgumentException.class, () -> service.save(1L, "BILIBILI", form));
    assertTrue(e.getMessage().contains("TITLE_TOO_LONG"), e.getMessage());
}
@Test void otherUsersScriptCannotBeSaved() { /* 非归属 userId → 拒绝，绝不静默落库 */ }
@Test void tagsStoredNormalised() { /* 存进去已是去重去空形态 */ }
```

- [ ] **Step 2: 红** → **Step 3: 实现**（`ddl-auto: update` 自动建表，不写 SQL 脚本；
  `@PreUpdate` 刷 `updatedAt`，与 `StoryScript` idiom 一致）→ **Step 4: 绿**
- [ ] **Step 5: 变异复证**（把归属校验注释掉，`otherUsersScriptCannotBeSaved` 必须失败）
- [ ] **Step 6: commit** `feat(publish): publish_meta 实体与规格校验服务`

---

## Task 3: 合成结果输出镜边界（时间轴单一真源）

**Files:**
- Modify: `video/VideoComposeService.java`（compose result）、`entity/VideoExport.java`、`service/VideoExportService.java`
- Test: `src/test/java/.../video/VideoComposeShotTimelineTest.java`

**Interfaces:**
- Consumes: 已有 `effectiveDurations`、`XFADE_DURATION`、`audioBedDurations(...)`
- Produces: compose result 新增 `"shotStarts": List<Double>`（每镜在成片时间轴上的起点，
  等于音轨床起点，与字幕 cue 同一轴）；`VideoExport` 新增列 `shot_starts_csv`（`"0.00,3.20,7.40,..."`，
  `%.2f` 逗号连接），`toComposeMap`/导出响应透传

- [ ] **Step 1: 写失败测试**

```java
@Test void shotStartsEqualCumulativeCompressedBeds() {
    // EFF=[4,5,4,6] → starts=[0, 3.2, 7.4, 10.6]：与音轨床起点同式，末镜不例外
    List<Double> starts = VideoComposeService.shotStarts(List.of(4, 5, 4, 6), 0.8);
    assertEquals(List.of(0.0, 3.2, 7.4, 10.6), starts);
}
@Test void lastShotStartPlusFullDurationEqualsVideoLength() {
    List<Double> starts = VideoComposeService.shotStarts(List.of(4, 5, 4, 6), 0.8);
    double total = starts.get(3) + 6;                       // 末镜床不扣 xfade
    assertEquals(4 + 5 + 4 + 6 - 3 * 0.8, total, 1e-9);     // == exactDuration
}
@Test void singleShotStartsAtZero() { assertEquals(List.of(0.0), VideoComposeService.shotStarts(List.of(8), 0.8)); }
```

- [ ] **Step 2: 红** → **Step 3: 实现** `static List<Double> shotStarts(List<Integer> effective, double xfade)`，
  在 compose 里 `result.put("shotStarts", shotStarts(effectiveDurations, XFADE_DURATION))`；
  `VideoExportService` 把 list 序列化进 `shot_starts_csv` → **Step 4: 绿**
- [ ] **Step 5: 变异复证**（末镜误扣 xfade 必须被第 2 条用例杀）→ **Step 6: commit**
  `feat(video): 合成结果输出镜边界，供发布元数据时间戳复用`

> 注：本 Task 与刚修完的音轨床是同一套轴。若有人想再写一遍 `Σ−k·0.8`，评审应直接拒绝。

---

## Task 4: 确定性派生器 PublishMetaDeriver（零 LLM）

**Files:**
- Create: `service/PublishMetaDeriver.java`
- Test: `src/test/java/.../service/PublishMetaDeriverTest.java`

**Interfaces:**
- Consumes: `StoryScript(idea/theme/title/format)`、`List<StoryShot>(narration/dialogue/insertAttribution)`、
  `VideoExport(shotStartsCsv/durationSeconds/coverUrl/hasSubtitle)`、`PublishSpec.bilibili()`
- Produces: `Map<String,Object> derive(Long scriptId, String platform)` →
  `{title, description, tags[], categoryName, categoryTid, declaration, timestamps[], sourceSections[]}`

**简介分节顺序（固定，缺一节即为 bug）**
1. 钩子 2 行（`theme` 首句 + 反差句，模板拼接不涉 LLM）
2. `【本片事实】` —— 从 `idea` 抽取的条目：按行切，仅收含数字或日期单位的行（`亿|万|%|日元|美元|年|月|日|账户|熔断`），
   原文照抄不改写（改写就产生幻觉风险）
3. `【素材出处】` —— `insertAttribution` 去重保序 + 版权行（`©…` 来自台账，不新造）
4. `【AI 使用说明】` —— 固定文案（画面/动态/配音/卡片四类）
5. `【不构成投资建议】` —— 固定文案
6. `【时间轴】` —— `shotStartsCsv` → `mm:ss` + 该镜旁白首 12 字
7. 系列引导 —— `collection` 名 + 下期钩子（取自尾镜 narration）

- [ ] **Step 1: 写失败测试**

```java
@Test void factsSectionCopiesIdeaNumbersVerbatim() {
    // idea 含「峰值亏一千四百万」与「-501,195」→ 事实节必须原样含这些串，且不吞掉整段 idea
    assertTrue(desc.contains("-501,195"));
    assertTrue(desc.indexOf("【本片事实】") < desc.indexOf("【素材出处】"));
}
@Test void attributionSectionDedupsAndKeepsOrder() {
    // 三镜共用同一出处 → 只出现一次，顺序按 shotOrder
    assertEquals(1, countOccurrences(desc, "fxkurumi-info.com"));
}
@Test void timestampsUseStoredShotStarts() {
    // starts=[0,3.2,7.4,10.6] → 00:00 / 00:03 / 00:07 / 00:10；四舍五入到秒
    assertEquals(List.of("00:00", "00:03", "00:07", "00:10"), out.timestamps());
}
@Test void missingShotStartsMarksApproxInsteadOfSilentWrong() {
    // 老导出记录没有 starts → 用名义时长近似，并置 approx=true 让前端提示复核
    assertTrue(out.get("approxTimestamps") instanceof Boolean b && b);
}
@Test void descriptionWithinBilibiliLimitAndSectionsComplete() {
    assertTrue(PublishSpec.bilibili().validate(t, tags, desc, 253).isEmpty());
    for (String h : List.of("【本片事实】", "【素材出处】", "【AI 使用说明】", "【不构成投资建议】", "【时间轴】"))
        assertTrue(desc.contains(h), "缺节: " + h);
}
@Test void deriverHasNoModelDependency() {
    // 结构护栏：派生器构造参数里不得出现 ModelService/ChatClient（零 LLM 由构造面保证）。
    // 不断言参数顺序/个数，避免“加一个依赖就假失败”，只卡“不得出现模型类”。
    for (Class<?> p : PublishMetaDeriver.class.getDeclaredConstructors()[0].getParameterTypes()) {
        assertNotEquals(ModelService.class, p, "派生器不得接模型服务");
        assertNotEquals(ChatClient.class, p, "派生器不得接模型服务");
    }
}
```

- [ ] **Step 2: 红** → **Step 3: 实现**（纯字符串装配，`mm:ss` 用 `(int)Math.floorMod(Math.round(t),60)` 语义核对）
  → **Step 4: 绿**
- [ ] **Step 5: 变异复证**：① 出处去重改成不去重 → 杀 dedup；② 时间戳改用名义时长 → 杀 timestamps；
  ③ 删掉【素材出处】节 → 杀 sections 完整性
- [ ] **Step 6: commit** `feat(publish): 从既有台账确定性派生发布简介与标签`

---

## Task 5: LLM 标题候选与开场钩子（带事实护栏 + 降级）

**Files:**
- Create: `service/PublishMetaLlmService.java`
- Test: `src/test/java/.../service/PublishMetaLlmServiceTest.java`

**Interfaces:**
- Consumes: `Task 4` 的 `derive()` 结果（作为唯一事实源塞进 prompt）、`PublishSpec.titleMax`、
  `modelService.getChatClient().prompt().user(...).call().content()`（现有 idiom，见 `AssetMatchService`）
- Produces: `List<String> generateTitles(Long scriptId, String platform)` → 最多 3 条、每条 ≤80 字符；
  `String generateHook(Long scriptId, String platform)` → 简介首 2 行；LLM 异常/超限返回
  `titlesFromTemplate()` 并 `log.warn`
  > **执行期修正（T5）**：原文写的是「`collection + 期数 + theme` 首句」，但 `StoryScript` 既没有
  > `collection` 也没有期数字段（`collection` 只在 `PublishMeta` 且为用户手填），凭空拼个期数编号
  > 正是本功能要防的行为。兜底标题定为现有 `script.title`（按码点截断），钩子兜底为 `theme` 首句。

**护栏（四条都要有测试）**
1. 字数：>80 直接丢弃，不截断（截断会造出半句话）
2. 禁用词表：`画质|音质|测试|求支持|第一次投稿|必看|震惊`命中即丢
3. **数字量级校验**：先把候选与事实文本都做空白归一，再把「数字 + 紧跟的量级单位（万亿千百%％）」
   作为整体比对，事实集里没有的量级组合即丢——这是防「LLM 把 1400 万写成 1400 亿」的唯一硬手段
   > **执行期修正（T5）**：本节原写的是「每个数字串必须 ∈ 事实集」，那个口径拦不住单位劫持——
   > 「1400 亿」里的 1400 本身在集内，照样放行，而本节声称要防的恰恰是这个形态。
   > 现已改为「数字 + 单位整体比对 + 空白归一」，`magnitudeUnitSwapIsDropped` 守住这条。
4. **集数编号豁免**：「钱坑漫评01：」「第 3 期」是连载序号不是对外声明，否则用户的连载标题会次次被误杀；
   豁免只认两种封闭形态（数字紧跟冒号 / 第 N 期集季），其余一律 fail-closed

- [ ] **Step 1: 写失败测试**（mock ChatClient 返回受控文本）

```java
@Test void overLongCandidateIsDroppedNotTruncated() { /* 返回 4 条含 1 条 85 字 → 结果 3 条且无 85 字 */ }
@Test void bannedWordCandidateIsDropped() { /* 「【必看】久留美爆仓」被丢 */ }
@Test void fabricatedNumberCandidateIsDropped() {
    // 事实集里没有 1.4 亿 → 该候选必须出局
    stubReturn("钱坑漫评01：作者一年亏 1.4 亿");
    assertTrue(service.generateTitles(738L, "BILIBILI").stream().noneMatch(s -> s.contains("1.4")));
}
@Test void llmFailureFallsBackToTemplateTitle() {
    doThrow(new RuntimeException("timeout")).when(...);
    assertEquals(1, service.generateTitles(738L, "BILIBILI").size());   // 模板兜底，不抛
}
```

- [ ] **Step 2: 红** → **Step 3: 实现**（prompt 里显式写「只允许复述以下事实中的数字」并附事实集）
  → **Step 4: 绿** → **Step 5: 变异复证**（去掉数字校验，`fabricatedNumberCandidateIsDropped` 必须失败）
- [ ] **Step 6: commit** `feat(publish): LLM 标题候选与数字事实护栏`

---

## Task 6: REST 端点

**Files:**
- Create: `controller/PublishMetaController.java`
- Test: `src/test/java/.../controller/PublishMetaControllerTest.java`

**Interfaces（`@RequestMapping("/api/video/publish")`）**
- `GET /spec?platform=BILIBILI` → 规格表（前端计数器与下拉数据源，含 `categoryTid` 与 `?tid=` 提示文案）
- `GET /{scriptId}/draft?platform=BILIBILI` → Task 4 派生 + Task 5 标题（`?llm=false` 可关，省钱开关）
- `PUT /{scriptId}/meta` → Task 2 `save`，violation 以 400 + 结构化 `violations[]` 返回
- `GET /{scriptId}/meta?platform=BILIBILI` → 已存值（无则返回 draft）

- [ ] **Step 1: 写失败测试**（MockMvc standalone + mock service，沿用 `SecurityConfigIntegrationTest` 的鉴权口径：
  HTTP 层 permitAll、服务层 `getCurrentUserId()` 校验——**不要**在 controller 里新加 `@PreAuthorize`）
```java
@Test void putReturns400WithViolationCodes() { /* 81 字标题 → 400 且 body 含 TITLE_TOO_LONG */ }
@Test void draftWithoutLlmSkipsModelCall() { /* ?llm=false → verify(modelService, never()) */ }
@Test void anonymousDraftStillRequiresLoginAtServiceLayer() { /* 未登录 → 业务失败而非 500 */ }
```
- [ ] **Step 2–6:** 红 → 实现 → 绿 → 变异复证（把 `llm=false` 分支写反必须被杀）→ commit
  `feat(publish): 发布元数据 REST 端点`

---

## Task 7: 工作台发布面板

**Files:**
- Modify: `src/main/resources/templates/commentary-studio.html`
- Test: 手工 + `tools/e2e-publish-meta.js`（Playwright/Puppeteer 沿用现有 e2e idiom）

**面板要素（对应投稿页字段，一屏可复制）**
标题（实时 `n/80` + 禁用词提示）｜标签 chip（`n/10`，重复即灰）｜简介（`n/2000`，按 `【节】` 折叠）｜
分区下拉（动漫杂谈 253 / 影视杂谈 182 + 「灰度期可用 `?tid=253`」提示）｜声明（默认「含AI生成内容」)｜
合集名｜置顶评论｜**「复制整包」按钮**（纯文本，顺序=标题/分区/标签/简介/置顶评论）

- [ ] **Step 1:** 先写 e2e 断言脚本（红：面板不存在）
- [ ] **Step 2:** 加 DOM + `loadPublishPanel(scriptId)`；注意本页 `esc()` 必须用于所有服务端串（该页历史上出过属性位注入红线）
- [ ] **Step 3:** e2e 绿：填 81 字标题 → 计数器变红且禁用提交；点「派生」→ 简介出现 5 个 `【节】`
- [ ] **Step 4:** 变异复证不适用（前端），改做**手工目视** + 截图存档
- [ ] **Step 5: commit** `feat(studio): 发布元数据面板`

---

## Task 8: 导出联动 —— 发布包落盘与 ZIP 附带

**Files:**
- Modify: `service/VideoExportService.java`（导出成功后 upsert draft）、`storage/LocalStorageService.java`
  （`uploadPublishPack`）、`service/VideoExportService.writeBatchZip`
- Test: `src/test/java/.../service/VideoExportPublishPackTest.java`

**行为**
- HD 导出 SUCCESS 后：若 `publish_meta` 无人工值（`source=MANUAL`）则写入派生稿（`source=GENERATED`）；
  **已有人工值只补空字段，绝不覆盖**（用户手改的标题被机器刷掉是最恼人的 bug）
- 落 `uploads/final/<清洗标题>-发布包.txt`（复用 `uploadFinal` 的标题清洗与重名时间戳规则）
- `writeBatchZip` 每条记录附带 `NNN_发布包.txt`；`manifest.txt` 增列「标题/标签数/简介字数/分区」

- [ ] **Step 1: 写失败测试**
```java
@Test void manualTitleSurvivesRegeneration() { /* 先 save(MANUAL, "人工标题")，再导出 → 库里仍是人工标题 */ }
@Test void generatedPackContainsFiveSectionsAndTagsWithinLimit() { /* 文件文本断言 */ }
@Test void zipEntryIncludesPublishPack() { /* prepareBatchZip → ZIP 条目名含 _发布包.txt */ }
```
- [ ] **Step 2–6:** 红 → 实现 → 绿 → 变异复证（把「只补空」写成「总是覆盖」必须被杀）→ commit
  `feat(publish): 导出联动发布包落盘与打包`

---

## Task 9: #738 端到端核验与文档回填

**Files:**
- Create: `tools/e2e-publish-meta.js`
- Modify: `API_TEST.md`（新端点 4 条）、`README.md`（功能段一句话 + 指向本计划）、
  `docs/story/FX/发布元数据-久留美.md`（追加「机器派生对照」节）

- [ ] **Step 1:** 对 #738 跑 `GET /publish/738/draft?platform=BILIBILI`，与人工稿逐节 diff，
  差异按「机器缺什么」列成表（预期缺口：`【本片事实】` 的条目粒度、封面文案）
- [ ] **Step 2:** 跑 `PUT` 存人工稿（§1-§3 内容），再触发一次导出，确认发布包落盘且未覆盖人工标题
- [ ] **Step 3:** 全量回归 `mvn -o test` 绿；变异复证清单逐条记录在计划末尾「执行记录」
- [ ] **Step 4:** 文档回填 + commit `docs(publish): 端点说明与 #738 派生对照`

---

## 不做的事（YAGNI，本期明确排除）

- 不对接 B 站开放平台 API 自动投稿/改稿（登录态、风控、审核回调成本远超收益，人工粘贴即可）
- 不做播放/点赞数据回填与标题 A/B 复盘（需要平台 API，另立项目）
- 不做抖音/小红书/快手/视频号/YT Shorts 规格与文案调性（用户已选「先只做 B 站」；
  要加时只需在 `PublishSpec` 添工厂方法，Task 1 的结构已为此留好）
- 不做多语言简介（字幕已有 en/bilingual，简介不需要）
- 不做简介富文本/Markdown 渲染（B 站简介是纯文本域）
- 不给 `video-studio.html`（导演工作台）加发布面板——发布是评论类工作台的收尾动作

## 风险与复核点

1. **平台规则会变**：分区灰度（二级分区下线/`tid` 随机分配）与简介 2000↔250 字档都处在变动期。
   规格值集中在一处 + 行内注释标复核日期，改值只动一个文件；`GET /spec` 让前端跟随，不硬编码在 JS 里。
2. **数字幻觉**：LLM 只碰标题/钩子，且过数字集合校验；简介事实节走原文照抄。评审时看到「让模型总结事实」要拒绝。
3. **第二套时间轴**：Task 3 是本计划的强依赖，先做。若跳过而让派生器自算名义累计，就会重演 §4-8 的漂移事故。
4. **引用占比口径**：in-app 整镜口径与外部工具插入秒数口径数值差 3 倍，发布包里的百分比必须标明口径来源，
   否则与版权说明自相矛盾。
5. **H2/PostgreSQL 双 profile**：`ddl-auto: update` 下新表两库都能建，但 `tags`/`timestampsCsv` 用 CSV 而非
   `@ElementCollection`（后者在 PG 上易踩懒加载与序列差异）。

## 执行顺序建议

Task 1 → 2 → 3 可并行（互不依赖）；4 依赖 1+2+3；5 依赖 4；6 依赖 2+4+5；7 依赖 6；8 依赖 2+4+6；9 收尾。
预估：1–4 是纯函数与实体（半天），5 需调 prompt 与护栏（半天），6–7（1 天），8（半天），9（半天）。

## 执行记录（逐 Task 回填变异复证结果）

| Task | 交付 | commit | 变异复证（改坏什么 → 哪条用例独红） |
|---|---|---|---|
| 1 | `PublishSpec` | 5f0d92f | 去重失效→杀 2；`descLimit` 忽略分区→杀 1；`titleMax` 改 81→杀 2 |
| 2 | 实体/仓储/校验服务 | 8057332 | 去归属校验→杀 1；`violations && false`→杀 2；**去掉「未传键保留原值」→存活**，补 `partialUpdateValidatesMergedViewNotOnlySubmittedKeys` 后才杀 |
| 3 | `shotStarts` 单一真源 | ccb7a8f | 退回名义轴→杀 4；`%.2f`→`%.0f`→杀 3 |
| 4 | `PublishMetaDeriver` | ea3bd11 | 出处不去重→杀 1（需先把桩改成两镜共用同一出处）；静默退回名义轴→杀 1（需先把压缩轴桩改成 4.50 这种名义轴算不出的值）；删【素材出处】节→杀 1；去数字门槛→杀 1；`tagMax-1`→`tagMax`→杀 1（需先把候选量灌到 14 个） |
| 5 | `PublishMetaLlmService` | 75f612e | 关数字护栏→杀 3；关禁用词→杀 1；超限改截断→杀 1；**去掉空白归一→杀 1**（证明加强口径承重，原计划写法确为后门）；去集数豁免→杀 1；去模板兜底→杀 4；不剥序号引号→杀 1 |
| 6 | `PublishMetaController` 4 端点 | 6ffcfa5 | llm 开关写反→杀 2；500 分流吞成 400→杀 1；violations 不附→杀 1；`/meta` 忽略已存值→杀 1；`/meta` 回退时烧模型→杀 1。全量 700 绿 |
| 7 | 发布面板 + `tools/e2e-publish-meta.js` | eb1a9e8 | 面板限额改硬编码→杀结构断言；`importCommentary` 不接 `idea`→杀 1。**浏览器实跑抓到真 bug**：`markPublishViolations` 用 `Object.keys(fields)` 把 map key 当元素 id → `$('title')` null → 保存链路抛异常（改 `Object.values`，e2e 补断言）。计划原文「重复标签应 400」断言写反了两级语义→改判为提示级（见 Ruling T7）。全量 702 绿 |
| 8 | 导出联动发布包 + ZIP 附包 | 2f8f2a8 | M1「只补空」改全覆盖→**先存活**（`manualTitleSurvivesRegeneration` 把 `upsertGenerated` 整个 mock 掉了，该规则零判据）→在 `PublishMetaServiceTest` 补 3 条真判据后杀；M2 附属失败上抛→杀 1；M3 去 ZIP 兜底→杀 1；M4 预览也写包→杀 1；M5 去 manifest 发布列→杀 1；M6 去占比口径标注→杀 1；M7 吐掉免登录 userId 入口→杀 1。全量 719 绿 |
| 9 | #738 端到端核验 + 文档回填 | c1f1485（缺陷修复）+ 本提交（文档） | 实跑链：`draft?llm=false` → `PUT` 人工稿 §1-§3 → `POST /export/hd/async`（导出记录 647）→ 发布包落盘 `uploads/final/…-发布包.txt` → `batch-download` ZIP 含 `01_720p_16x9_647_发布包.txt` + manifest 四列 → 人工标题与 `source=MANUAL` 未被刷。逐节 diff 结论写进 `docs/story/FX/发布元数据-久留美.md` §9。**端到端真实请求撞出一个存量缺陷**：`POST /export/batch-download` 因签名写成 `ResponseEntity<?>`（泛型被擦除，`StreamingResponseBodyReturnValueHandler` 不认领返回值）分发阶段 500——该端点自 2026-11-14 写下起从未跑通，service 层单测直接调 `writeBatchZip` 看不见这一层；已修并钉住（`VideoExportBatchZipControllerTest` 4 例，红→绿已复证）。全量 723 绿 |

执行期撞到的两个行为（不当 bug 修，写进文档）：

1. **时间轴会分两次变样**。#738 的存量导出记录（id=615）在本能力之前生成，`shot_starts_csv` 为空 → 首次 draft 走名义轴且 `approxTimestamps=true`；在新代码下重导一次（id=647）后拿到真实压缩轴、不再标近似。要时间戳直接可贴必须重导。
2. **护栏只保证「不编数字」，不保证「数字仍是真的」**。台账里的企划值「总时长 95-115 秒 / 14-16 个镜头」与成片 84.2s / 17 镜不符，原文照抄反而把过时数字送到观众眼前——发布包尾注固定写了人工核对这句话，【本片事实】节仍需人过一遍。

Step 1 要求的「机器缺什么」逐节 diff 表在人工稿同目录文件 `docs/story/FX/发布元数据-久留美.md` §9；
该目录走 `.git/info/exclude` 本地不入库，所以结论的仓库内版本压缩成下面五条：

- **已对齐**：分区（动漫杂谈 tid=253，与人工结论一致）、创作声明、五节结构与顺序、【素材出处】不漏标。
- **标题**：机器只会照抄脚本原标题，拼不出「钱坑漫评01：…」这类系列前缀（`StoryScript` 无合集/期数字段，也不编）；`llm=true` 才出 3 条候选。
- **标签**：只有 4 个且含三个角色英文名（`Kurumi`/`Mochiko`/`Author`，对中文检索无用）；人工 9 个分五层。题材/形式/差异词是人的判断，本期刻意不内置词表。
- **事实节粒度**：机器把台账里的企划参数也照抄（条目数 12 vs 人工 7），并带出 Markdown 双前缀 `· - 原作：` 与换行截断。
- **不产的**：时间戳是逐镜旁白首句截断（不是人挑的章节标题）、置顶评论与合集留空、封面只下发规范不下建议。

判定：当前能力适合当「初稿 + 防漏清单」，不适合当终稿——与本计划开头的定位一致。

分支整体评审（`CodeReview`，区间 6db23dd..HEAD）只出一条警告，已修（commit 636766e）：

- **W-1｜导出后增删镜头 → 面板 500**。`shot_starts_csv` 存的是「导出当时」的镜数，之后删过镜头则该轴比当前 shots 长，
  `buildDescription` 按下标 `shots.get(i)` 取镜文本直接 `IndexOutOfBoundsException` → `/meta`、`/draft` 被控制器判 500。
  修法：`derive()` 里 `starts.size() != shots.size()` 即视为失真，整条退回名义轴（它永远与当前镜头一一对应）并标 `approxTimestamps`。
  两个方向都算失真（不只防越界）：轴短一截时时间轴会漏掉导出后新加的镜，与「盖住全片」的用途矛盾。
  新增 2 例：`shotStartsLongerThanCurrentShotsIsTreatedAsStaleNotCrashing`（修前独红：Index: 2 Size: 2）、
  `shotStartsShorterThanCurrentShotsAlsoFlagsApprox`（变异复证：把 `!=` 改回 `>` 后仅此条红，证明双向判据承重）。
  全量 725 绿。其余裁决点（附属不阻断出片、人工行只补空、数字护栏、`/meta` 恒不烧模型、`ResponseEntity<StreamingResponseBody>` 泛型钉）评审判定均成立。

执行方式注记：本环境无通用实现型 subagent，逐 Task 采用 inline TDD（红→绿→变异复证→提交），
分支整体评审留给最后一次 `CodeReview`。

台账（含逐条 Ruling）：`.superpowers/sdd/2026-11-19-publish-metadata-bilibili/progress.md`；
#738 实跑证据：同目录下 `draft-738.json` / `pack-738.txt` / `manifest-738.txt` / `export-738.json`。
