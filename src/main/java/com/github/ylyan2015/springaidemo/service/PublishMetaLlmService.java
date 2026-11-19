package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.exception.PublishRejectException;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布文案的「文学性」部分：标题候选与简介开场钩子。
 *
 * <p>与 {@link PublishMetaDeriver} 的分工是刻意的：事实、出处、时间戳由派生器原文装配，
 * 模型只碰标题与钩子——但模型输出的这两样同样会出现在对外页面上，所以每条候选都要过三道护栏：
 * 长度、禁用词、<b>数字量级</b>。
 *
 * <p>数字护栏比「数字 ∈ 事实集」更严一档：计划原文要防的是「把 1400 万写成 1400 亿」，
 * 而纯数字集合对此完全失效（1400 本身就在集内）。这里比的是<b>数字 + 紧跟的量级单位</b>整体，
 * 并先把空白归一（模型爱写「1400 万」这种带空格的形式，不归一就能绕过）。
 *
 * <p>任何失败路径（模型异常、超时、输出全被拦下）都退回确定性模板，绝不返回空列表——
 * 发布面板给一个空标题就等于「功能坏了」。
 */
@Service
public class PublishMetaLlmService {

    private static final Logger log = LoggerFactory.getLogger(PublishMetaLlmService.class);

    static final int MAX_TITLES = 3;
    static final int MAX_HOOK_LINES = 2;

    /** 模型自带的序号与包裹引号（不剥掉，序号里的数字会被数字护栏误杀） */
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*·•]|\\d+\\s*[.、)）])\\s+?");
    private static final Pattern WRAPPING_QUOTES = Pattern.compile("^[「『“'\u2019]+|[」』”'\u2019]+$");
    /**
     * 集数编号豁免：「钱坑漫评01：」「第 3 期」是连载序号，不是对外事实声明。
     * 只认「数字紧跟冒号」与「第 N 期/集/季」两种封闭形态，卡不住的话宁可被判出局（fail-closed）。
     */
    private static final Pattern SERIAL_MARKER =
            Pattern.compile("(?:第\\s*\\d{1,3}\\s*[期集季])|(?:\\d{1,3}\\s*[：:])");
    /** 数字 + 紧跟的量级单位；单位表只放会改变量级的字符 */
    private static final Pattern NUMBER_UNIT = Pattern.compile("(\\d+(?:[.,]\\d+)?)([万亿千百%％]?)");

    private static final String TITLE_SYSTEM = """
            你是 B 站动漫杂谈区标题助手。给定一部评论类二创短片的事实台账，写出让观众愿意点进来的标题。
            风格：口语、具体、有反差，不堆形容词，不写「深度解析」「一口气看完」这类被用烂的套话。
            硬性要求（违反即作废）：
            1. 每条不超过 80 字符；
            2. 禁止出现事实台账里没有的数字；引用数字时把数字和单位（万/亿/%）一起原样照抄事实行，不要换算、不要改写单位；
            3. 若要写期数编号，用「01：」这种数字紧跟冒号的形式，别把它当成数据；
            4. 禁用词：画质、音质、测试、求支持、第一次投稿、必看、震惊；
            5. 只输出标题正文，每行一条，不要编号、不要引号、不要解释。""";
    private static final String HOOK_SYSTEM = """
            你是 B 站动漫杂谈区文案助手。给定事实台账，写简介开头的钩子：最多两行，每行一句短句。
            第一句给冲突或反常识，第二句交代本片要谈什么。不写「大家好」「本期视频」这类占位话。
            硬性要求（违反即作废）：
            1. 禁止出现事实台账里没有的数字，引用数字必须连单位原样照抄；
            2. 禁用词：画质、音质、测试、求支持、第一次投稿、必看、震惊；
            3. 只输出正文，每行一句，不要编号、不要引号。""";

    private final StoryScriptRepository scriptRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;

    public PublishMetaLlmService(StoryScriptRepository scriptRepository, UserRepository userRepository,
                                 ModelService modelService) {
        this.scriptRepository = scriptRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
    }

    /** 标题候选（最多 3 条，已过护栏）；模型不可用时返回 1 条模板标题，绝不返回空列表 */
    public List<String> generateTitles(Long scriptId, String platform) {
        StoryScript script = loadOwnedScript(scriptId);
        PublishSpec spec = PublishMetaService.specFor(platform);
        List<String> facts = PublishMetaDeriver.extractFacts(script.getIdea());
        Set<String> allowed = factNumbers(script, facts);
        try {
            String response = modelService.getChatClient().prompt()
                    .system(TITLE_SYSTEM).user(buildUserPrompt(script, facts))
                    .call().content();
            List<String> kept = keptLines(response, spec, allowed, MAX_TITLES, scriptId, "标题");
            if (!kept.isEmpty()) {
                return kept;
            }
        } catch (Exception e) {
            log.warn("LLM 标题生成失败，退回模板标题（scriptId={}）: {}", scriptId, e.getMessage());
            return templateTitles(script, spec);
        }
        log.warn("LLM 标题候选全部被护栏拦下或为空（scriptId={}），退回模板标题", scriptId);
        return templateTitles(script, spec);
    }

    /** 简介开场钩子（最多 2 行，已过护栏）；模型不可用时退回主题首句 */
    public String generateHook(Long scriptId, String platform) {
        StoryScript script = loadOwnedScript(scriptId);
        PublishSpec spec = PublishMetaService.specFor(platform);
        List<String> facts = PublishMetaDeriver.extractFacts(script.getIdea());
        Set<String> allowed = factNumbers(script, facts);
        try {
            String response = modelService.getChatClient().prompt()
                    .system(HOOK_SYSTEM).user(buildUserPrompt(script, facts))
                    .call().content();
            List<String> kept = keptLines(response, spec, allowed, MAX_HOOK_LINES, scriptId, "钩子");
            if (!kept.isEmpty()) {
                return String.join("\n", kept);
            }
        } catch (Exception e) {
            log.warn("LLM 钩子生成失败，退回主题首句（scriptId={}）: {}", scriptId, e.getMessage());
            return fallbackHook(script);
        }
        log.warn("LLM 钩子全部被护栏拦下或为空（scriptId={}），退回主题首句", scriptId);
        return fallbackHook(script);
    }

    /** 逐行清洗 + 三道护栏 + 去重 + 截到上限；返回的是清洗后的原文行（不做任何截断） */
    private List<String> keptLines(String response, PublishSpec spec, Set<String> allowed,
                                   int max, Long scriptId, String what) {
        Set<String> kept = new LinkedHashSet<>();
        if (response == null) {
            return List.of();
        }
        for (String raw : response.split("\\R")) {
            String line = cleanLine(raw);
            if (line.isEmpty() || kept.size() >= max) {
                continue;
            }
            String reason = rejection(line, spec, allowed);
            if (reason != null) {
                log.warn("丢弃 {} 候选（scriptId={}）：{} —— {}", what, scriptId, reason, line);
                continue;
            }
            kept.add(line);
        }
        return new ArrayList<>(kept);
    }

    /** 返回拒绝理由；{@code null} 表示通过 */
    private static String rejection(String line, PublishSpec spec, Set<String> allowed) {
        int len = line.codePointCount(0, line.length());
        if (len > spec.titleMax()) {
            // 整条丢弃而不是截断：截断会造出半句话，而半句话贴在投稿页上比没标题更难看
            return "超过 " + spec.titleMax() + " 字符（" + len + "）";
        }
        String banned = PublishSpec.firstBannedWord(line);
        if (banned != null) {
            return "命中禁用词「" + banned + "」";
        }
        List<String> bad = foreignNumbers(line, allowed);
        if (!bad.isEmpty()) {
            return "事实台账里没有这些数字量级 " + bad;
        }
        return null;
    }

    private static String cleanLine(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        s = LIST_MARKER.matcher(s).replaceFirst("");
        s = WRAPPING_QUOTES.matcher(s).replaceAll("");
        return s.strip();
    }

    /** 权威数字集：来自 idea 台账 + 标题 + 主题，即「本片已经在内部确认过的说法」 */
    private static Set<String> factNumbers(StoryScript script, List<String> facts) {
        StringBuilder authoritative = new StringBuilder()
                .append(nvl(script.getIdea())).append('\n')
                .append(nvl(script.getTitle())).append('\n')
                .append(nvl(script.getTheme())).append('\n');
        for (String f : facts) {
            authoritative.append(f).append('\n');
        }
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER_UNIT.matcher(noSpace(authoritative.toString()));
        while (m.find()) {
            out.add(m.group(1));
            if (!m.group(2).isEmpty()) {
                out.add(m.group(1) + m.group(2));
            }
        }
        return out;
    }

    /** 候选里出现的、事实台账没有的数字量级（数字与紧跟的单位整体比对） */
    static List<String> foreignNumbers(String candidate, Set<String> allowed) {
        String text = SERIAL_MARKER.matcher(noSpace(candidate)).replaceAll("");
        List<String> bad = new ArrayList<>();
        Matcher m = NUMBER_UNIT.matcher(text);
        while (m.find()) {
            String unit = m.group(2);
            String key = unit.isEmpty() ? m.group(1) : m.group(1) + unit;
            if (!allowed.contains(key) && !bad.contains(key)) {
                bad.add(key);
            }
        }
        return bad;
    }

    /** 空白归一：模型写「1400 万」还是「1400万」必须等价，否则单位护栏一推就开 */
    private static String noSpace(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 兜底标题：库里既有的标题就是唯一事实，不编新说法 */
    private static List<String> templateTitles(StoryScript script, PublishSpec spec) {
        String title = PublishMetaDeriver.clampTitle(script.getTitle(), spec.titleMax());
        return List.of(title);
    }

    private static String fallbackHook(StoryScript script) {
        String theme = nvl(script.getTheme()).strip();
        if (!theme.isEmpty()) {
            return theme.split("[。\\n]")[0].strip();
        }
        return nvl(script.getTitle()).strip();
    }

    /** 包私有 seam：prompt 内容本身是判据（数字硬约束要前置告诉模型，不能只靠后处理拦） */
    static String buildUserPrompt(StoryScript script, List<String> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("现有标题：").append(nvl(script.getTitle())).append('\n');
        sb.append("主题：").append(nvl(script.getTheme())).append('\n');
        sb.append("创作说明（事实台账原文）：").append(nvl(script.getIdea())).append('\n');
        sb.append("事实台账（引用数字时只能从这里原样照抄，禁止出现下面事实里没有的数字）：\n");
        if (facts.isEmpty()) {
            sb.append("（无含数字的事实条目，因此标题与钩子里一个数字都不要出现）\n");
        } else {
            for (String f : facts) {
                sb.append("· ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

    private StoryScript loadOwnedScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new PublishRejectException("脚本不存在或无权访问: " + scriptId));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new PublishRejectException("用户未登录");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new PublishRejectException("用户不存在"))
                .getId();
    }
}
