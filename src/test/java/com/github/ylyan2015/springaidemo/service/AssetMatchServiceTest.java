package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 素材库切片 S2 Task 4：AssetMatchService（spec §2）。
 * 空库短路零 LLM 调用 / LLM JSON top-3 + 非法 id 剔除 / 非法 JSON 与异常降级关键词 /
 * 推荐不落库（verify never save）/ INSERT 素材不进目录 / 目录 100 截断 / tags 双形态（逗号/下划线）关键词兼容。
 */
class AssetMatchServiceTest {

    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StoryShotRepository shotRepo = mock(StoryShotRepository.class);
    StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    ModelService modelService = mock(ModelService.class);
    AssetMatchService service;

    @BeforeEach
    void setUp() {
        service = new AssetMatchService(assetRepo, scriptRepo, shotRepo, charRepo, userRepo,
                modelService, new ObjectMapper());
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of());
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

    private ChatClient.ChatClientRequestSpec stubLlm(String response) {
        // 分层 mock（非 RETURNS_DEEP_STUBS）：深桩链中间层的 stub 调用会计入 verify 导致 TooManyActualInvocations
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(modelService.getChatClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);
        return spec;
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
        stubLlm("{\"matches\":[{\"shotId\":3,\"assetIds\":[11,12,13,99],\"reasons\":[\"出场角色\",\"环境匹配\",\"无关\",\"伪造id\"]}]}");
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
        stubLlm("不是JSON");
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

    // ==================== 台账约束两条（用户指令并入） ====================

    @Test
    void keywordFallbackHandlesCommaAndUnderscoreTags() {
        // 约束 1：tags 双形态——扫描入库逗号分隔 / booru 入库空白或单 tag 内下划线
        StoryShot shot = new StoryShot(); shot.setId(4L); shot.setShotOrder(1);
        shot.setSceneDescription("miku 站在舞台上，灯光打亮，1girl 特写");
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(21L, "初音", "CHARACTER", "1girl hatsune_miku"),   // booru tag_string 形态
                asset(22L, "演唱会台", "SCENE", "舞台,灯光"),             // 扫描逗号形态
                asset(23L, "沙漠", "SCENE", "驼铃_落日")));               // 零重叠
        when(modelService.getChatClient()).thenThrow(new RuntimeException("LLM 不可用"));
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("FALLBACK", r.source());
        List<Long> ids = r.matches().get(0).candidates().stream().map(c -> c.assetId()).toList();
        assertTrue(ids.contains(21L), "下划线 tag 拆分后 miku/1girl 命中");
        assertTrue(ids.contains(22L), "逗号 tag 舞台/灯光 命中");
        assertFalse(ids.contains(23L));
    }

    @Test
    void catalogTruncatedKeepsNewest100() {
        // 约束 2：素材目录 >100 截断 100（spec §2.1 第 5 条）；审查 M-6：mock 按真实仓 id 降序返回，
        // 断言保留的是最新 101..2 而非最旧，锁住「倒序截断=留最新」语义
        List<MediaAsset> many = new ArrayList<>();
        for (int i = 101; i >= 1; i--) {
            many.add(asset(i, "角色" + i, "CHARACTER", "标签"));
        }
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(many);
        ChatClient.ChatClientRequestSpec spec = stubLlm("{\"matches\":[]}");
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("LLM", r.source());
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(spec).user(cap.capture());
        String prompt = cap.getValue();
        assertEquals(100, prompt.lines().filter(l -> l.startsWith("id=")).count());
        assertTrue(prompt.contains("id=101 "), "最新条目必须保留");
        assertFalse(prompt.contains("id=1 "), "最旧条目必须被截掉");
    }

    // ==================== 审查修复轮：I-1 围栏 / I-2 角色名 / M-3 shotId 全 miss ====================

    @Test
    void unclosedCodeFenceStillParses() {
        // I-1：未闭合围栏（截断输出）不得丢弃整份有效 JSON 静默降级
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直")));
        stubLlm("```json\n{\"matches\":[{\"shotId\":3,\"assetIds\":[11],\"reasons\":[\"出场角色\"]}]}");
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("LLM", r.source());
        assertEquals(11L, r.matches().get(0).candidates().get(0).assetId());
    }

    @Test
    void llmPromptIncludesShotCharacterNames() {
        // I-2（spec §2.1.1）：出场角色名喂进 LLM 输入，支撑「角色名与库一致→命中率高」闭环
        StoryShot shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setCharacterIds("5");
        shot.setSceneDescription("她走进咖啡店窗边坐下");   // 代词化描述，靠角色名补信号
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(List.of(shot));
        com.github.ylyan2015.springaidemo.entity.StoryCharacter c =
                new com.github.ylyan2015.springaidemo.entity.StoryCharacter();
        c.setId(5L); c.setName("林晚");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(c));
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直")));
        ChatClient.ChatClientRequestSpec spec = stubLlm("{\"matches\":[]}");
        service.match(7L);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(spec).user(cap.capture());
        assertTrue(cap.getValue().contains("出场角色=[林晚]"), cap.getValue());
    }

    @Test
    void llmResponseWithoutMatchesFallsBack() {
        // 复审 Warning 收口：合法 JSON 但缺 matches 字段 → 精确 IAE 降级，不 NPE
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直")));
        stubLlm("{\"success\":true}");
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("FALLBACK", r.source());
    }

    @Test
    void llmShotIdAllMismatchFallsBack() {
        // M-3：合法 JSON 但 shotId 全是模型自己编号 → 降级 FALLBACK，不报 source=LLM+零候选
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(11L, "林晚", "CHARACTER", "黑长直")));
        stubLlm("{\"matches\":[{\"shotId\":1,\"assetIds\":[11],\"reasons\":[\"x\"]}]}");   // shotId=1 不存在（真实为 3）
        AssetMatchService.MatchResult r = service.match(7L);
        assertEquals("FALLBACK", r.source());
        assertEquals(11L, r.matches().get(0).candidates().get(0).assetId());   // 关键词路径仍命中"林晚"
    }
}
