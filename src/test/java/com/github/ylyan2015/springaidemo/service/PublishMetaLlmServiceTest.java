package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 标题候选与开场钩子测试。这里测的不是「文案好不好」，而是那道护栏：
 * 模型可以随便发挥文学性，但一旦写出事实台账里没有的<b>数量级</b>，必须出局。
 * 降级路径同样要测——发布面板不能因为模型超时就是空的。
 */
class PublishMetaLlmServiceTest {

    private StoryScriptRepository scriptRepository;
    private ModelService modelService;
    private ChatClient client;
    private PublishMetaLlmService service;

    @BeforeEach
    void setUp() {
        scriptRepository = mock(StoryScriptRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        modelService = mock(ModelService.class);
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new PublishMetaLlmService(scriptRepository, userRepository, modelService);
        when(modelService.getChatClient()).thenReturn(client);

        User u = new User();
        u.setId(7L);
        u.setUsername("kurumi_fx");
        when(userRepository.findByUsername("kurumi_fx")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("kurumi_fx", null, List.of()));

        when(scriptRepository.findByIdAndUserId(738L, 7L)).thenReturn(Optional.of(script()));
    }

    private StoryScript script() {
        StoryScript s = new StoryScript();
        s.setId(738L);
        s.setUserId(7L);
        s.setTitle("萌系番的每个笑点，都是作者的爆仓记录");
        s.setTheme("用一部番讲一个账户");
        s.setIdea("作者 18 年外汇，峰值亏 1400 万日元。纯属娱乐。");
        s.setFormat("commentary");
        return s;
    }

    private void stubModel(String text) {
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(text);
    }

    private void stubModelFailure() {
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("read timeout"));
    }

    // ---------- 正常路径 ----------

    @Test
    void candidatesCopyFactNumbersVerbatim() {
        stubModel("久留美爆仓：一部番讲完一个账户\n"
                + "作者炒汇 18 年，峰值亏 1400 万日元\n"
                + "萌系画风背后是一张爆仓截图");
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertEquals(3, titles.size(), titles.toString());
        assertTrue(titles.contains("作者炒汇 18 年，峰值亏 1400 万日元"),
                "照抄台账数字的候选必须活着，否则护栏等于「一律丢弃」：" + titles);
    }

    @Test
    void seriesNumberMarkerIsExemptFromFactSet() {
        // 「钱坑漫评01：」是集数编号，不是对外事实声明；按纯数字集合它会被误杀，
        // 而用户的连载标题本来就是这个形态，误杀等于每次都只剩模板兜底
        stubModel("钱坑漫评01：萌系画风背后是一张爆仓截图");
        assertEquals(List.of("钱坑漫评01：萌系画风背后是一张爆仓截图"),
                service.generateTitles(738L, "BILIBILI"));
    }

    @Test
    void moreThanThreeCandidatesAreTrimmed() {
        stubModel("候选一\n候选二\n候选三\n候选四\n候选五");
        assertEquals(3, service.generateTitles(738L, "BILIBILI").size());
    }

    @Test
    void listNumberingAndQuotesAreStrippedBeforeGuards() {
        // 模型爱写「1. 标题」；若不自带编号里剥掉，编号会被数字护栏当成编造数字
        stubModel("1. 「久留美爆仓：一部番讲完一个账户」");
        assertEquals(List.of("久留美爆仓：一部番讲完一个账户"), service.generateTitles(738L, "BILIBILI"));
    }

    // ---------- 三条护栏 ----------

    @Test
    void overLongCandidateIsDroppedNotTruncated() {
        String tooLong = "钱坑漫评：" + "久".repeat(80);   // 85 字符
        stubModel(tooLong + "\n久留美爆仓：一部番讲完一个账户");
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertTrue(titles.stream().noneMatch(t -> t.codePointCount(0, t.length()) > 80),
                "超 80 字符的候选不得出现在结果里：" + titles);
        assertFalse(titles.contains(tooLong.substring(0, 80)),
                "超限必须整条丢弃，截断会造出半句话：" + titles);
        assertTrue(titles.contains("久留美爆仓：一部番讲完一个账户"), titles.toString());
    }

    @Test
    void bannedWordCandidateIsDropped() {
        stubModel("【必看】久留美爆仓\n一部番讲完一个账户");
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertFalse(titles.stream().anyMatch(t -> t.contains("必看")), "禁用词候选必须出局：" + titles);
        assertEquals(List.of("一部番讲完一个账户"), titles);
    }

    @Test
    void fabricatedNumberCandidateIsDropped() {
        stubModel("钱坑漫评：作者一年亏 1.4 亿\n一部番讲完一个账户");
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertTrue(titles.stream().noneMatch(t -> t.contains("1.4")),
                "台账里没有 1.4，这条属幻觉：" + titles);
    }

    @Test
    void magnitudeUnitSwapIsDropped() {
        // 计划原文要防的就是这个：数字照抄、单位换掉，量级错 4 个数量级。
        // 只比数字集合的写法会放它过关，所以护栏必须比「数字 + 紧跟的量级单位」整体
        stubModel("作者 18 年外汇，峰值亏 1400 亿\n一部番讲完一个账户");
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertTrue(titles.stream().noneMatch(t -> t.contains("1400 亿") || t.contains("1400亿")),
                "1400 万被写成 1400 亿必须出局：" + titles);
    }

    // ---------- 降级 ----------

    @Test
    void llmFailureFallsBackToTemplateTitle() {
        stubModelFailure();
        List<String> titles = service.generateTitles(738L, "BILIBILI");
        assertEquals(1, titles.size(), "模型挂了也要给一条能用的标题，且不得抛异常");
        assertEquals("萌系番的每个笑点，都是作者的爆仓记录", titles.get(0));
    }

    @Test
    void blankModelOutputFallsBackToTemplateTitle() {
        stubModel("   \n  \n");
        assertEquals(1, service.generateTitles(738L, "BILIBILI").size());
    }

    @Test
    void allCandidatesRejectedFallsBackToTemplateTitle() {
        // 全被护栏拦下时不能返回空列表——空标题在投稿页就是「功能坏了」
        stubModel("【必看】一年亏 1.4 亿\n" + "久".repeat(90));
        assertEquals(List.of("萌系番的每个笑点，都是作者的爆仓记录"),
                service.generateTitles(738L, "BILIBILI"));
    }

    // ---------- 钩子 ----------

    @Test
    void hookKeepsAtMostTwoModelLines() {
        stubModel("画久留美的人，爆仓了。\n番里她只负责可爱和赚钱。\n第三行不要");
        String hook = service.generateHook(738L, "BILIBILI");
        assertEquals("画久留美的人，爆仓了。\n番里她只负责可爱和赚钱。", hook);
    }

    @Test
    void hookLineWithFabricatedNumberIsDroppedButValidLineSurvives() {
        // 钩子直接进简介正文：不在这里过同一道数字护栏，等于在护栏上开个后门
        stubModel("作者一年亏 1.4 亿\n番里她只负责可爱和赚钱。");
        assertEquals("番里她只负责可爱和赚钱。", service.generateHook(738L, "BILIBILI"));
    }

    @Test
    void hookFallsBackToThemeWhenModelFails() {
        stubModelFailure();
        assertEquals("用一部番讲一个账户", service.generateHook(738L, "BILIBILI"));
    }

    // ---------- prompt 与归属 ----------

    @Test
    void promptCarriesFactNumbersAndExplicitProhibition() {
        String prompt = PublishMetaLlmService.buildUserPrompt(script(),
                List.of("作者 18 年外汇，峰值亏 1400 万日元"));
        assertTrue(prompt.contains("1400 万"), "事实集要显式进 prompt，模型才谈得上复述：" + prompt);
        assertTrue(prompt.contains("禁止出现下面事实里没有的数字"),
                "数字硬约束要写在 prompt 里前置引导，别只靠后处理拦：" + prompt);
    }

    @Test
    void accessIsScopedToOwner() {
        when(scriptRepository.findByIdAndUserId(738L, 7L)).thenReturn(Optional.empty());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.generateTitles(738L, "BILIBILI"));
        assertTrue(e.getMessage().contains("738"), e.getMessage());
    }
}
