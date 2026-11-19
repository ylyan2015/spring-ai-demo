package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布元数据派生器测试：简介必须由「已有确定性数据」拼装，且分节齐全、事实原文照抄、
 * 时间戳落在压缩轴上。判据都落在可核对的文本结构上，不测「读起来好不好」。
 */
class PublishMetaDeriverTest {

    private StoryScriptRepository scriptRepository;
    private StoryShotRepository shotRepository;
    private VideoExportRepository exportRepository;
    private StoryCharacterRepository characterRepository;
    private UserRepository userRepository;
    private PublishMetaDeriver deriver;

    @BeforeEach
    void setUp() {
        scriptRepository = mock(StoryScriptRepository.class);
        shotRepository = mock(StoryShotRepository.class);
        exportRepository = mock(VideoExportRepository.class);
        characterRepository = mock(StoryCharacterRepository.class);
        userRepository = mock(UserRepository.class);
        deriver = new PublishMetaDeriver(scriptRepository, shotRepository, exportRepository,
                characterRepository, userRepository);

        User u = new User();
        u.setId(7L);
        u.setUsername("kurumi_fx");
        when(userRepository.findByUsername("kurumi_fx")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("kurumi_fx", null, List.of()));
    }

    private StoryScript script(String idea, String theme) {
        StoryScript s = new StoryScript();
        s.setId(738L);
        s.setUserId(7L);
        s.setTitle("萌系番的每个笑点，都是作者的爆仓记录");
        s.setTheme(theme);
        s.setIdea(idea);
        s.setFormat("commentary");
        return s;
    }

    private StoryShot shot(int order, int dur, String narration, String dialogue, String attribution) {
        StoryShot s = new StoryShot();
        s.setShotOrder(order);
        s.setDurationSeconds(dur);
        s.setNarration(narration);
        s.setDialogue(dialogue);
        s.setInsertAttribution(attribution);
        return s;
    }

    private void stubTwoShotsWithExport(String startsCsv) {
        when(scriptRepository.findByIdAndUserId(eq(738L), eq(7L)))
                .thenReturn(Optional.of(script("作者 18 年外汇，峰值亏 1400 万日元。纯属娱乐。",
                        "用一部番讲一个账户")));
        when(shotRepository.findByScriptIdOrderByShotOrderAsc(738L)).thenReturn(List.of(
                // 两镜共用同一出处：去重判据必须真的落到「重复输入」上，否则去掉去重也能绿
                shot(1, 4, "画久留美的人，爆仓了。", null, "图取自作品官网 fxkurumi-info.com"),
                shot(2, 5, "番里，她只负责可爱和赚钱。", "一晚赚了三万！",
                        "图取自作品官网 fxkurumi-info.com")));
        VideoExport ex = new VideoExport();
        ex.setStatus("SUCCESS");
        ex.setDurationSeconds(8);
        ex.setShotStartsCsv(startsCsv);
        when(exportRepository.findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(738L, 7L, "SUCCESS"))
                .thenReturn(Optional.of(ex));
        when(characterRepository.findByScriptIdOrderByIdAsc(738L))
                .thenReturn(List.of(character("福贺久留美")));
    }

    private StoryCharacter character(String name) {
        StoryCharacter c = new StoryCharacter();
        c.setName(name);
        return c;
    }

    @Test
    void descriptionCarriesAllFiveComplianceSectionsInOrder() {
        stubTwoShotsWithExport("0.00,3.20");
        String desc = (String) deriver.derive(738L, "BILIBILI").get("description");
        List<String> order = List.of("【本片事实】", "【素材出处】", "【AI 使用说明】",
                "【不构成投资建议】", "【时间轴】");
        int prev = -1;
        for (String h : order) {
            int at = desc.indexOf(h);
            assertTrue(at >= 0, "缺节: " + h);
            assertTrue(at > prev, "分节顺序错乱: " + h);
            prev = at;
        }
    }

    @Test
    void factsAreCopiedVerbatimAndDoNotSwallowWholeIdea() {
        stubTwoShotsWithExport("0.00,3.20");
        Map<String, Object> out = deriver.derive(738L, "BILIBILI");
        String desc = (String) out.get("description");
        assertTrue(desc.contains("1400 万日元"), "含数字的条目必须原样进事实节：" + desc);
        // 「纯属娱乐。」无数字，不得混进事实节（否则等于把主观评价伪装成事实）
        assertFalse(desc.contains("纯属娱乐"), "无数字条目不得进【本片事实】");
        @SuppressWarnings("unchecked")
        List<String> facts = (List<String>) out.get("facts");
        assertTrue(facts.stream().allMatch(f -> f.chars().anyMatch(Character::isDigit)),
                "事实条目必须全部含数字，逐条核对：" + facts);
    }

    @Test
    void attributionIsDeduplicatedAndStatesOriginExplicitly() {
        stubTwoShotsWithExport("0.00,3.20");
        String desc = (String) deriver.derive(738L, "BILIBILI").get("description");
        assertEquals(1, occurrences(desc, "fxkurumi-info.com"),
                "同一出处多镜共用时只写一次，否则简介会被重复行淹没");
    }

    @Test
    void noAttributionStillProducesAnExplicitOriginalWorkStatement() {
        when(scriptRepository.findByIdAndUserId(eq(738L), eq(7L)))
                .thenReturn(Optional.of(script("作者峰值亏 1400 万日元。", "主题")));
        when(shotRepository.findByScriptIdOrderByShotOrderAsc(738L)).thenReturn(List.of(
                shot(1, 4, "画久留美的人，爆仓了。", null, null)));
        VideoExport ex = new VideoExport();
        ex.setStatus("SUCCESS");
        ex.setShotStartsCsv("0.00");
        when(exportRepository.findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(738L, 7L, "SUCCESS"))
                .thenReturn(Optional.of(ex));
        when(characterRepository.findByScriptIdOrderByIdAsc(738L)).thenReturn(List.of());

        String desc = (String) deriver.derive(738L, "BILIBILI").get("description");
        assertTrue(desc.contains("无外部素材引用"),
                "全原创稿件也要在【素材出处】里明说，不能留空让读者猜");
    }

    @Test
    void timestampsComeFromStoredShotStarts() {
        // 4.50 是只有压缩轴（TTS 抬升）才能出现的值，名义轴算不出（4 − 0.8 = 3.2）；
        // 两个轴必须在这里可区分，否则实现默默回退到名义轴也能跑绿本条
        stubTwoShotsWithExport("0.00,4.50");
        @SuppressWarnings("unchecked")
        List<String> ts = (List<String>) deriver.derive(738L, "BILIBILI").get("timestamps");
        assertEquals(List.of("00:00", "00:04"), ts, "4.50s 必须向下取整到 00:04，跳早不跳晚");
        assertEquals(Boolean.FALSE, deriver.derive(738L, "BILIBILI").get("approxTimestamps"));
    }

    @Test
    void missingShotStartsFallsBackToNominalAxisAndFlagsApprox() {
        stubTwoShotsWithExport(null);
        Map<String, Object> out = deriver.derive(738L, "BILIBILI");
        assertEquals(Boolean.TRUE, out.get("approxTimestamps"),
                "旧导出记录没有镜边界时，必须显式告知这是近似值");
        @SuppressWarnings("unchecked")
        List<String> ts = (List<String>) out.get("timestamps");
        assertEquals(List.of("00:00", "00:03"), ts);   // 4 − 0.8 = 3.2
    }

    @Test
    void tagsReserveOneSlotForViewersAndIncludeCharacterAndFormWords() {
        stubTwoShotsWithExport("0.00,3.20");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) deriver.derive(738L, "BILIBILI").get("tags");
        assertTrue(tags.contains("福贺久留美"), "角色名是 IP 检索直达词：" + tags);
        assertTrue(tags.contains("动漫杂谈"), "评论类 format 要给出与分区一致的题材词：" + tags);
    }

    @Test
    void tagsAreTrimmedToPlatformCapMinusOne() {
        stubTwoShotsWithExport("0.00,3.20");
        // 候选量必须超过上限，否则「留 1 位给观众」这条裁剪规则根本不会被执行，
        // 把 tagMax - 1 改成 tagMax 也能绿 —— 那就是恒真判据
        List<StoryCharacter> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(character(i == 0 ? "福贺久留美" : "路人" + i));
        }
        when(characterRepository.findByScriptIdOrderByIdAsc(738L)).thenReturn(many);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) deriver.derive(738L, "BILIBILI").get("tags");
        assertEquals(9, tags.size(), "B 站上限 10，派生值必须恰好留 1 位：" + tags);
    }

    @Test
    void derivedTitleIsNonBlankAndWithinPlatformLimit() {
        stubTwoShotsWithExport("0.00,3.20");
        String title = (String) deriver.derive(738L, "BILIBILI").get("title");
        assertFalse(title.isBlank());
        assertTrue(title.length() <= 80, title);
    }

    @Test
    void deriverHasNoModelDependency() {
        // 结构护栏：派生器构造参数里不得出现模型类（零 LLM 由构造面保证）。
        // 不断言参数顺序/个数，避免「加一个仓库依赖就假失败」，只卡「不得出现模型类」。
        // 按简单名匹配而不是 Class 引用：ModelService 在 controller 包、ChatClient 属于可选依赖，
        // 绑死 FQN 会让测试跟着包结构抖，而这条判据要卡的是「谁都能看出这是模型入口」这件事。
        for (var ctor : PublishMetaDeriver.class.getDeclaredConstructors()) {
            for (Class<?> p : ctor.getParameterTypes()) {
                assertFalse(p.getSimpleName().contains("ModelService"), "派生器不得接模型服务: " + p);
                assertFalse(p.getSimpleName().contains("ChatClient"), "派生器不得接模型服务: " + p);
            }
        }
    }

    private static int occurrences(String text, String needle) {
        int n = 0, i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
