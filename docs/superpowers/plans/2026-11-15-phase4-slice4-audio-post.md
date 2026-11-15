# Phase 4 切片 4：多角色配音与后期增强 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对白/旁白分离 + 每角色独立声线 TTS；音效库分层混音；Real-ESRGAN 超分 + RIFE 补帧可选导出增强（课程⑥视频放大）。

**Architecture:** Shot 增 dialogue/dialogueCharacterId；合成入参增对白行与声线，`generateTtsAudio` 改为按行（TtsLine=text+voice）合成、镜内拼接、镜间 apad+concat 沿用；字幕文本含对白行；SFX 复刻 BGM 预置模式（lavfi 合成+扫描+LLM 推荐），混音分层人声100%/BGM15%/SFX30%；超分补帧为导出后处理 pass（抽帧→realesrgan→rife→重编码保音轨），配置路径空=跳过、失败 warn 跳过不阻断导出；导出新参数走 `exportHdWithPost` 新重载，存量 exportHd/Agent 工具签名不变。

**Tech Stack:** Java 17 / Edge TTS（--voice 参数化）/ FFmpeg（adelay/amix/抽帧重编码）/ Real-ESRGAN ncnn-vulkan / RIFE ncnn-vulkan（本地可执行，配置路径模式同 ffmpeg-path）。

**Spec:** `docs/superpowers/specs/2026-11-15-phase4-manga-drama-design.md` §7、§9、§12。前置：切片 1（角色卡 voice）、2、3 已完成。

## Global Constraints

- 沿用全部红线；暂存不提交；四份双语文档。
- 默认零变化：无对白时 TTS 行为与现状逐字一致（单声线 YunxiNeural 旁白）；sfx/upscale/interpolate 默认关。
- 声线来源：对白=角色卡 voice；旁白=zh-CN-YunxiNeural（沿用）。
- 混音 normalize=0（防人声减半，ffmpeg≥4.4）；时长型参数用实际时长（xfade 陷阱）。
- 增强 pass 失败=warn+跳过（导出仍成功），未配置=静默跳过。
- 口型同步 4-OPT 不在本期。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| Modify | `entity/StoryShot.java` / `video/Shot.java` / `video/VideoScriptService.java` / `service/StoryScriptService.java` | 对白字段/生成/关联 |
| Create | `video/TtsLine.java` | (text, voice) record |
| Modify | `video/VideoComposeService.java` | 按行 TTS/镜内拼接/SFX 混音/增强 pass/ComposeOptions 扩展 |
| Modify | `video/subtitle/SubtitleService.java` | resolveNarrations 含对白 |
| Modify | `service/VideoExportService.java` / `controller/VideoExportController.java` | toComposeMap 对白键/声线解析/exportHdWithPost/参数解析 |
| Create | `entity/SfxTrack.java` / `repository/SfxTrackRepository.java` / `service/SfxService.java` / `tools/generate-preset-sfx.ps1` | 音效库 |
| Modify | yml / video-studio.html / 四份文档 | 配置/前端/文档 |

---

### Task 1：对白字段与脚本生成关联

**Files:** Modify StoryShot/Shot/VideoScriptService/StoryScriptService；Test `service/StoryScriptServiceDialogueLinkTest.java`

**Interfaces:**
- Produces: StoryShot `dialogue` TEXT / `dialogueCharacterId` BIGINT；Shot DTO `dialogue`/`dialogueCharacterName`；System Prompt 增"dialogue: 本镜角色对白（中文，无对白为空串）+ dialogueCharacterName"；持久化按切片 1 name→id 映射写 dialogueCharacterId（未匹配 warn 置 null）；shotToMap 增 dialogue/dialogueCharacterId；updateShot 增两键。

- [ ] **Step 1：写失败测试** `StoryScriptServiceDialogueLinkTest`（完整类）

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

class StoryScriptServiceDialogueLinkTest {

    @Test
    void linksDialogueCharacterAndWarnsUnmatched() {
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
        cd.setName("林晚");
        sb.setCharacters(List.of(cd));
        Shot shot = new Shot();
        shot.setSceneDescription("s"); shot.setImagePrompt("p");
        shot.setDialogue("你终于来了。");
        shot.setDialogueCharacterName("林晚");
        Shot shot2 = new Shot();
        shot2.setSceneDescription("s2"); shot2.setImagePrompt("p2");
        shot2.setDialogue("你是谁？");
        shot2.setDialogueCharacterName("不存在");
        sb.setShots(List.of(shot, shot2));
        when(videoScriptService.generateScript("idea", null)).thenReturn(sb);
        when(charRepo.save(any())).thenAnswer(inv -> { StoryCharacter c = inv.getArgument(0); c.setId(11L); return c; });
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryScript saved = service.generateAndSaveScript("idea");
        assertEquals("你终于来了。", saved.getShots().get(0).getDialogue());
        assertEquals(11L, saved.getShots().get(0).getDialogueCharacterId());
        assertNull(saved.getShots().get(1).getDialogueCharacterId());
        SecurityContextHolder.clearContext();
    }
}
```
- [ ] **Step 2：跑红**。**Step 3：实现**。**Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 2：多声线 TTS + 字幕含对白

**Files:** Create `video/TtsLine.java`；Modify VideoComposeService/SubtitleService/VideoExportService；Test `video/VideoComposeMultiVoiceTest.java`、`video/subtitle/SubtitleServiceDialogueTest.java`

**Interfaces:**
- Consumes: Task 1；toComposeMap 增 `dialogue`/`dialogueCharacterId`；doExport 注入 StoryCharacterRepository 解析 `dialogueVoice` 放入 Map（角色不存在→null→旁白声线）。
- Produces: `TtsLine(String text, String voice)` record；compose 内 `List<List<TtsLine>> linesPerShot`（对白行 voice=dialogueVoice 非空?否则 Yunxi；旁白行 voice=Yunxi）；包私有 `static List<String> buildTtsCommand(String pythonCommand, TtsLine line, Path mp3)`；`generateTtsAudio(List<List<TtsLine>>, List<Integer>, Path)`：每行合成→镜内多行 ffmpeg concat→镜间沿用 concatAudioWithSilence；`hasNarration`=任一行非空；SubtitleService.resolveNarrations 文本=对白+" "+旁白（对白空则仅旁白）。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoComposeMultiVoiceTest {

    @Test
    void ttsCommandUsesLineVoice() {
        List<String> cmd = VideoComposeService.buildTtsCommand("python",
                new TtsLine("你终于来了。", "zh-CN-XiaoxiaoNeural"), Path.of("a.mp3"));
        int i = cmd.indexOf("--voice");
        assertEquals("zh-CN-XiaoxiaoNeural", cmd.get(i + 1));
        assertTrue(cmd.contains("--text"));
    }

    @Test
    void defaultNarrationVoiceUnchanged() {
        List<String> cmd = VideoComposeService.buildTtsCommand("python",
                new TtsLine("旁白", null), Path.of("a.mp3"));
        assertEquals("zh-CN-YunxiNeural", cmd.get(cmd.indexOf("--voice") + 1));
    }
}
```

```java
package com.github.ylyan2015.springaidemo.video.subtitle;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SubtitleServiceDialogueTest {

    @Test
    void cuesIncludeDialogueBeforeNarration() {
        SubtitleService service = new SubtitleService(null, null);
        StoryShot shot = new StoryShot();
        shot.setShotOrder(1);
        shot.setDurationSeconds(5);
        shot.setDialogue("你终于来了。");
        shot.setNarration("她轻声说。");
        List<SubtitleCue> cues = service.buildCues(
                service.resolveNarrationsForTest(List.of(shot), "zh"), List.of(5), 0.8);
        assertTrue(cues.get(0).text().contains("你终于来了。"));
        assertTrue(cues.get(0).text().contains("她轻声说。"));
    }
}
```

（`resolveNarrationsForTest` 为包私有 seam 委托私有 resolveNarrations；**SubtitleCue 是 record，accessor 为 `text()`**。）

- [ ] **Step 2：跑红**。**Step 3：实现**（buildTtsCommand：voice null/blank→YunxiNeural；镜内 concat 命令 `ffmpeg -y -i l0.mp3 -i l1.mp3 -filter_complex [0:a][1:a]concat=n=2:v=0:a=1[out] -map [out] shot_i.mp3`；单行直用；空镜 null 沿用）。**存量构造点同步**：VideoExportService 构造器增 StoryCharacterRepository（7→8 参），`VideoExportServiceZipTest` L52 构造末尾增 `Mockito.mock(StoryCharacterRepository.class)`。
- [ ] **Step 4：跑绿 + 全量**（含存量 VideoComposeFfmpegTest TTS 用例 `-Dtts=true` 回归）。**Step 5：暂存**。

---

### Task 3：音效库与分层混音

**Files:** Create `tools/generate-preset-sfx.ps1`、SfxTrack/SfxTrackRepository/SfxService；Modify VideoComposeService（ComposeOptions+sfxTracks、mixSfxIntoVideo）/VideoExportService（sfx 推荐接线）/yml；Test `service/SfxServiceTest.java`、`video/VideoComposeSfxMixTest.java`

**Interfaces:**
- Produces: 预置 4 音效 `data/sfx/{wind__soft_breeze,rain__light_rain,footsteps__slow_walk,heartbeat__tense_pulse}.mp3`（ps1 lavfi 合成，idiom 同 generate-preset-bgm.ps1：wind=`anoisesrc=color=pink:amplitude=0.25:d=6,lowpass=f=400`；rain=`anoisesrc=color:white:amplitude=0.2:d=6,highpass=f=1000`；footsteps=`aevalsrc='0.3*sin(2*PI*90*t)*exp(-mod(t,0.6)*8)':d=6`；heartbeat=`aevalsrc='0.4*sin(2*PI*55*t)*exp(-mod(t,0.8)*6)':d=6`）；SfxService.scanLibrary（@PostConstruct，category__name 模式）/`recommendForScript(List<StoryShot>)`→`Map<Long shotId, String category>`（LLM 单次批量，失败关键词回退：雨→rain/风→wind/走→footsteps/紧张→heartbeat，无匹配 null）；`getSfxFilePath(category)`；ComposeOptions `sfxTracks(List<SfxSpec>)`，`SfxSpec(Path path, double startSeconds)` record 嵌套；`mixSfxIntoVideo(video, tracks, totalDuration, hasAudio, workDir)`：每轨 `[i:a]volume=0.3,adelay=ms|ms[sfxi]`，与既有音轨 amix normalize=0 duration=first；无既有音轨时首轨作基。

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoComposeSfxMixTest {

    @Test
    void sfxFilterHasVolumeAndDelay() {
        String filter = VideoComposeService.buildSfxFilter(
                List.of(new VideoComposeService.SfxSpec(Path.of("rain.mp3"), 2.5)), 1, true);
        assertTrue(filter.contains("volume=0.3"));
        assertTrue(filter.contains("adelay=2500|2500"));
        assertTrue(filter.contains("normalize=0"));
    }

    @Test
    void noTracksReturnsNull() {
        assertNull(VideoComposeService.buildSfxFilter(List.of(), 0, true));
    }
}
```

（buildSfxFilter 包私有 static：输入索引从 1 起（0=视频音轨），hasAudio=false 时首轨不加 amix 直接 [sfx0] 映射；amix inputs=1+n。）

`SfxServiceTest`（完整类）：

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.SfxTrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SfxServiceTest {

    @Test
    void keywordFallbackWhenLlmUnavailable() {
        SfxTrackRepository repo = mock(SfxTrackRepository.class);
        SfxService service = new SfxService(repo, null); // ModelService null → 关键词回退
        ReflectionTestUtils.setField(service, "libraryPath", "./data/sfx");
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setSceneDescription("细雨落在青石巷，她撑伞缓缓走过");
        Map<Long, String> rec = service.recommendForScript(List.of(shot));
        assertEquals("rain", rec.get(3L));
    }

    @Test
    void noKeywordMatchYieldsNull() {
        SfxService service = new SfxService(mock(SfxTrackRepository.class), null);
        StoryShot shot = new StoryShot();
        shot.setId(4L);
        shot.setSceneDescription("安静的房间");
        assertNull(service.recommendForScript(List.of(shot)).get(4L));
    }
}
```

- [ ] **Step 2：跑红**。**Step 3：实现** + SfxService（构造器 `(SfxTrackRepository, ModelService)`；扫描 idiom 复制 BgmService；recommendForScript：modelService 非空时 LLM 单次批量 prompt 要求 JSON {shotId:category}，解析失败/null 时关键词回退：雨→rain/风→wind/走→footsteps/紧张→heartbeat，无匹配 null）+ doExport：`video.sfx.enabled`（默认 false，字段名 sfxEnabled）开启且 PostFlags.sfx 时 recommend→specs（startSeconds=镜头累计起始，沿用 xfade 累计公式）→options.sfxTracks；compose 在 BGM 后调 mixSfx（hasAudio=hasNarration||hasBgm）。**存量构造点同步**：VideoExportService 构造器增 SfxService（8→9 参），ZipTest L52 再增 `Mockito.mock(SfxService.class)`。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 4：超分与补帧导出增强

**Files:** Modify VideoComposeService（ComposeOptions.upscale/interpolate + enhance pass）/yml；Test `video/VideoComposeEnhanceTest.java`

**Interfaces:**
- Produces: 配置 `video.realesrgan-path:""` / `video.realesrgan-model:realesrgan-x4plus-anime` / `video.rife-path:""` / `video.rife-model:""`（rife 模型目录）；ComposeOptions `upscale(boolean)/interpolate(boolean)` fluent + `isUpscale()/isInterpolate()` getter；包私有 `static List<List<String>> buildEnhanceCommands(String ffmpeg, String realesrgan, String rgModel, String rife, String rifeModel, Path src, Path workDir, int fps, boolean hasAudio)` 返回命令序列；compose 在 store 前：flags 且对应 path 非空→执行序列，任一命令失败 warn 跳过整个增强（返回原 finalVideo）。

命令序列（upscale+interpolate 全开）：
1. `ffmpeg -y -i src.mp4 -q:v 2 workDir/frames/f_%05d.png`
2. `realesrgan -i frames -o up -s 2 -n rgModel`
3. `rife -i up -o interp -m rifeModel`（仅 interpolate；否则跳过）
4. `ffmpeg -y -framerate 48 -i interp/f_%05d.png -i src.mp4 -map 0:v -map 1:a? -c:v libx264 -crf <options.crf> -pix_fmt yuv420p -c:a aac -shortest enhanced.mp4`（hasAudio=false 时去掉 -map 1:a? 与 -c:a；仅 upscale 时 framerate=原 fps、输入 up/）

- [ ] **Step 1：写失败测试**

```java
package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoComposeEnhanceTest {

    @Test
    void fullChainCommandsOrderAndAudioMap() {
        List<List<String>> cmds = VideoComposeService.buildEnhanceCommands("ffmpeg", "realesrgan",
                "realesrgan-x4plus-anime", "rife", "/models/rife-v4.6", Path.of("src.mp4"),
                Path.of("w"), 30, true);
        assertEquals(4, cmds.size());
        assertTrue(cmds.get(1).contains("-n"));
        assertTrue(cmds.get(2).contains("-m"));
        assertTrue(cmds.get(3).contains("-framerate"));
        assertTrue(cmds.get(3).contains("48"));
        assertTrue(cmds.get(3).contains("1:a?"));
    }

    @Test
    void upscaleOnlySkipsRifeAndKeepsFps() {
        List<List<String>> cmds = VideoComposeService.buildEnhanceCommands("ffmpeg", "realesrgan",
                "m", "", "", Path.of("src.mp4"), Path.of("w"), 30, false);
        assertEquals(3, cmds.size());
        assertTrue(cmds.get(2).contains("30"));
        assertFalse(cmds.get(2).contains("1:a?"));
    }
}
```

- [ ] **Step 2：跑红**。**Step 3：实现**（executeCommand 复用；失败 catch→warn+原视频；probe 不强制，命令失败即跳过）。
- [ ] **Step 4：跑绿 + 全量**。**Step 5：暂存**。

---

### Task 5：导出接线 + 前端

**Files:** Modify VideoExportService（exportHdWithPost + PostFlags record）/VideoExportController（executeExport 解析 upscale/interpolate/sfx 可选参数）/video-studio.html；Test `service/VideoExportServicePostFlagsTest.java`

**Interfaces:**
- Produces: `record PostFlags(boolean upscale, boolean interpolate, boolean sfx)`；`exportHdWithPost(scriptId, resolution, aspect, bgmId, subtitle, withLogo, lang, PostFlags)`；存量 exportHd/exportPreview 委托 PostFlags(false,false,false)（**Agent 工具与平台导出零改动**）；doExport 增 PostFlags 参→options.upscale/interpolate + sfx 推荐开关。

- [ ] **Step 1：写失败测试** `VideoExportServicePostFlagsTest`（完整类）

```java
package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoExportServicePostFlagsTest {

    private VideoComposeService compose;
    private SfxService sfx;
    private VideoExportService service;

    @BeforeEach
    void setUp() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        VideoExportRepository exportRepo = mock(VideoExportRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        compose = mock(VideoComposeService.class);
        sfx = mock(SfxService.class);
        service = new VideoExportService(scriptRepo, shotRepo, exportRepo, userRepo,
                compose, mock(BgmService.class), mock(SubtitleService.class),
                mock(StoryCharacterRepository.class), sfx);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageStatus("READY"); shot.setImageUrl("/images/a.png"); shot.setDurationSeconds(5);
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(compose.compose(anyList(), any())).thenReturn(Map.of("videoUrl", "/images/v.mp4",
                "fileSizeBytes", 1L, "totalDuration", 5));
        when(exportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "sfxEnabled", true);
        when(sfx.recommendForScript(anyList())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void withPostPassesFlagsToComposeOptions() {
        service.exportHdWithPost(7L, "720p", "16:9", null, false, false, "zh",
                new VideoExportService.PostFlags(true, false, true));
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        assertTrue(cap.getValue().isUpscale());
        assertFalse(cap.getValue().isInterpolate());
        verify(sfx).recommendForScript(anyList());
    }

    @Test
    void legacyExportHdDefaultsAllFlagsFalse() {
        service.exportHd(7L, "720p", "16:9", null, false, false, "zh");
        ArgumentCaptor<VideoComposeService.ComposeOptions> cap =
                ArgumentCaptor.forClass(VideoComposeService.ComposeOptions.class);
        verify(compose).compose(anyList(), cap.capture());
        assertFalse(cap.getValue().isUpscale());
        assertFalse(cap.getValue().isInterpolate());
        verify(sfx, never()).recommendForScript(anyList());
    }
}
```
- [ ] **Step 2：跑红**。**Step 3：实现** + 控制器解析（Boolean.TRUE.equals(map.get(...))）+ 前端：shot 编辑增对白 textarea + 对白角色 select（currentCharacters）；action-bar 增"超分"/"补帧"/"音效"三 checkbox（currentUpscale/currentInterpolate/currentSfx），exportVideo() body 增三参；导出历史徽章增 hasUpscale 等（toMap 增键可选，决策：不增历史记录键，YAGNI）。
- [ ] **Step 4：JS 语法验证 + 浏览器冒烟**（双角色对白脚本→-Dtts=true 听检两声线；无 realesrgan 配置时勾选超分导出仍成功）。
- [ ] **Step 5：暂存**。

---

### Task 6：文档 + 验证

- [ ] **Step 1：四份文档** 多声线/音效/超分补帧特性、配置节（video.sfx.enabled、video.realesrgan-*、video.rife-*）、导出参数表、FAQ（增强工具下载指引 ncnn-vulkan release、CPU 耗时预期、Docker 不预装）。
- [ ] **Step 2：ps1 实跑** 生成 4 音效入 data/sfx（本机 FFmpeg 已具备）。
- [ ] **Step 3：全量门槛 + `-Dtts=true` 多声线真跑**。**Step 4：暂存全部，汇报，等待提交指令**。

---

## 自检记录

**Spec 覆盖**：§7.1 对白/多声线→T1/T2；§7.2 音效→T3；§7.3 超分补帧→T4/T5；§7.4 测试→各任务；§7.5 口型 4-OPT 不做。
**类型一致性**：TtsLine/SfxSpec/PostFlags 全计划统一；VideoExportService 构造器 7→8（T2）→9（T3），ZipTest L52 两处同步已枚举；exportHd 旧签名委托不变→Agent/平台零改动；buildTtsCommand/buildSfxFilter/buildEnhanceCommands 均包私有 static seam；SubtitleCue record accessor `text()`；字幕含对白与 TTS 行顺序一致（对白在前）。
**占位符扫描**：全部测试完整；ps1 四条 lavfi 命令具体；无 TBD。

---

## 执行记录（2026-11-15）

T1-T6 全部完成，全量 288 绿（4 跳过为真实冒烟）；预置 4 音效已实跑生成入 data/sfx；四份双语文档 + VIDEO_STUDIO_PLAN 已同步；全部暂存未提交。

**执行偏差修正**：
- ps1 lavfi：本机新版 FFmpeg anoisesrc 的 `s`=seed、采样率用 `r`，且无声道选项（默认 mono）；`c` 是 color 别名。已改 `r=44100` 并去声道参；aevalsrc 保留 `s=44100:channel_layout=mono`。

**CodeReview 修复（0C+2M+1m）**：
- M1 补帧帧率：rife 帧数翻倍，硬编码 48fps 致 30fps 源成品 0.8 倍慢动作/被 -shortest 截断→改 `fps * 2`（30→60），测试断言 48→60，文档/前端/规格同步。
- M2 SFX 混音：无既有音轨多轨时 amix duration=first 截掉后续音效（实测复现）→hasAudio?first:longest（外层 -t 兜底），补回归用例。
- m3 归属一致性：addShot/batchUpdateShots 未过滤 dialogueCharacterId→抽 filterOwnedDialogueCharacterId 三入口共用，补 StoryScriptServiceDialogueOwnershipTest 2 用例。
