package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.exception.PublishRejectException;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布元数据确定性派生器：把「已经存在的事实」装配成投稿页可粘贴的发布包。
 *
 * <p>本类<b>不调用任何模型</b>（构造面即护栏，见 PublishMetaDeriverTest）。理由：
 * 简介里的【本片事实】【素材出处】是对外承诺，一次数字幻觉就是合规事故；
 * 而这些内容本来就有权威来源（{@code script.idea}、{@code shot.insertAttribution}、
 * 导出记录的镜边界），装配是纯函数活。文学性的标题与钩子交给 {@code PublishMetaLlmService}，
 * 并由它那道数字集合校验兜住。
 *
 * <p>时间戳一律取导出记录的 {@code shotStartsCsv}（压缩轴，与字幕 cue、音轨床同源）；
 * 老记录没有该列时退回名义近似，并把 {@code approxTimestamps=true} 显式交给前端提示复核。
 */
@Service
public class PublishMetaDeriver {

    private static final Logger log = LoggerFactory.getLogger(PublishMetaDeriver.class);

    /** 事实条目上限：再多就没人看完了 */
    static final int MAX_FACT_ITEMS = 12;
    /** 事实条目最长：超过说明切分没切开，整段会被当一条吞进来 */
    static final int MAX_FACT_LENGTH = 120;
    /** 时间轴最多列这么多镜（长片取前段，避免简介被时间戳淹没） */
    static final int MAX_TIMELINE_ITEMS = 24;
    /** 时间戳行里的文本长度 */
    private static final int TEXT_SNIPPET = 14;
    /** 作品名书名号提取 */
    private static final Pattern WORK_TITLE = Pattern.compile("《([^》]{1,30})》");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    static final String SECTION_FACTS = "【本片事实】";
    static final String SECTION_SOURCES = "【素材出处】";
    static final String SECTION_AI = "【AI 使用说明】";
    static final String SECTION_DISCLAIMER = "【不构成投资建议】";
    static final String SECTION_TIMELINE = "【时间轴】";

    private static final String AI_NOTICE =
            "本片为评论类二创：分镜画面由 AI 生成（画面带「AI 生成」标识），动态镜头由图生视频模型生成，"
                    + "配音为 TTS 合成，片中信息卡与数据面板为本地绘制。已按平台要求勾选「含AI生成内容」。";
    private static final String DISCLAIMER =
            "本片只谈作品与公开记录，不推荐任何平台、任何品种，不预测行情。"
                    + "杠杆的数学：跌一半，要涨一倍才回本。别拿生活费赌行情。";
    private static final String NO_SOURCE_NOTICE =
            "本片画面均为原创生成，无外部素材引用；如含作品官方视觉图，出处见上列。";

    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final VideoExportRepository exportRepository;
    private final StoryCharacterRepository characterRepository;
    private final UserRepository userRepository;

    public PublishMetaDeriver(StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              VideoExportRepository exportRepository,
                              StoryCharacterRepository characterRepository,
                              UserRepository userRepository) {
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.exportRepository = exportRepository;
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
    }

    /** 派生发布包草稿（不落库；人工确认后再 PUT），用户取自当前登录态 */
    public Map<String, Object> derive(Long scriptId, String platform) {
        return derive(scriptId, getCurrentUserId(), platform);
    }

    /**
     * 显式传 userId 的入口：导出 worker 线程里调派生不能依赖登录态。
     *
     * <p>本项目踩过的坑：SecurityContextHolder 基于 ThreadLocal，异步线程里不显式传播就报
     * 「用户未登录」。发布包是导出成功后顺手写的附属产物，不该依赖调用线程有没有会话。
     */
    public Map<String, Object> derive(Long scriptId, Long userId, String platform) {
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new PublishRejectException("脚本不存在或无权访问: " + scriptId));
        PublishSpec spec = PublishMetaService.specFor(platform);
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        Optional<VideoExport> latest =
                exportRepository.findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(scriptId, userId, "SUCCESS");

        List<String> facts = extractFacts(script.getIdea());
        List<String> sources = extractSources(shots);
        List<Double> starts = latest.map(e -> VideoComposeService.parseStartsCsv(e.getShotStartsCsv()))
                .filter(l -> !l.isEmpty())
                .orElse(null);
        boolean approx = starts == null;
        if (approx) {
            starts = nominalStarts(shots);
        }
        List<String> timestamps = timelineStarts(starts);
        List<String> tags = deriveTags(script, shots, spec);
        String title = clampTitle(script.getTitle(), spec.titleMax());
        String description = buildDescription(script, facts, sources, timestamps, shots, starts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", spec.platform());
        out.put("title", title);
        out.put("description", description);
        out.put("tags", tags);
        out.put("categoryName", spec.categories().get(0).name());
        out.put("categoryTid", spec.categories().get(0).tid());
        out.put("declaration", spec.declarationOptions().get(0));
        out.put("facts", facts);
        out.put("sources", sources);
        out.put("timestamps", timestamps);
        out.put("approxTimestamps", approx);
        // 计划风险 4：引用占比有两个口径（整镜计入 vs 外部工具按插入秒数），数值差 3 倍。
        // 不带上口径的百分比会与版权说明自相矛盾，所以两者一起下发。
        out.put("insertRatioPercent", (int) Math.round(VideoExportService.insertRatio(shots) * 100));
        out.put("insertRatioBasis", "整镜口径：镜头只要叠了出处水印就整镜计入，与按插入秒数统计的外部口径不同");
        out.put("descriptionLength", description.codePointCount(0, description.length()));
        if (description.codePointCount(0, description.length()) > spec.descLimit(spec.categories().get(0).tid())) {
            log.warn("Derived description for script {} is {} chars, over {} limit — trim facts or timeline",
                    scriptId, description.length(), spec.descMax());
        }
        return out;
    }

    /** idea 里含数字的条目原文照抄；无数字的主观句一律不进事实节 */
    static List<String> extractFacts(String idea) {
        List<String> out = new ArrayList<>();
        if (idea == null || idea.isBlank()) {
            return out;
        }
        for (String line : idea.split("[\\n；;]")) {
            for (String part : line.split("。")) {
                String t = part.trim();
                if (t.isEmpty() || t.length() > MAX_FACT_LENGTH) {
                    continue;
                }
                if (!HAS_DIGIT.matcher(t).find()) {
                    continue;
                }
                if (!out.contains(t)) {
                    out.add(t);
                }
                if (out.size() >= MAX_FACT_ITEMS) {
                    return out;
                }
            }
        }
        return out;
    }

    /** 逐镜出处去重保序（B 站《投稿须知》要求简介标注素材原作者/地址，这里就是权威来源） */
    static List<String> extractSources(List<StoryShot> shots) {
        List<String> out = new ArrayList<>();
        for (StoryShot s : shots) {
            String a = s.getInsertAttribution();
            if (a == null || a.isBlank()) {
                continue;
            }
            String t = a.trim();
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 近似轴：名义时长累计并扣 xfade（与压缩轴同式，但不含 TTS 抬升，故必须标 approx） */
    static List<Double> nominalStarts(List<StoryShot> shots) {
        List<Double> starts = new ArrayList<>();
        double acc = 0.0;
        for (int i = 0; i < shots.size(); i++) {
            starts.add(acc);
            int d = shots.get(i).getDurationSeconds() == null ? 5 : shots.get(i).getDurationSeconds();
            acc += d - (i < shots.size() - 1 ? VideoComposeService.XFADE_DURATION : 0);
        }
        return starts;
    }

    /** 秒 → mm:ss 列表；向下取整，宁可跳早 0.99s 也不要跳进下一镜 */
    static List<String> timelineStarts(List<Double> starts) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(starts.size(), MAX_TIMELINE_ITEMS); i++) {
            out.add(formatClock(starts.get(i)));
        }
        return out;
    }

    static String formatClock(double seconds) {
        long total = (long) Math.floor(seconds + 1e-9);
        if (total < 0) {
            total = 0;
        }
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    /**
     * 标签：角色名（IP 直达）+ 作品名书名号 + 评论类形态词。
     * 只留 tagMax−1 个位置，最后 1 个留给观众补 tag（观众加 tag 也算互动，且便于纠错）。
     */
    List<String> deriveTags(StoryScript script, List<StoryShot> shots, PublishSpec spec) {
        List<String> raw = new ArrayList<>();
        for (String w : List.of(String.valueOf(script.getTitle()), String.valueOf(script.getTheme()),
                String.valueOf(script.getIdea()))) {
            Matcher m = WORK_TITLE.matcher(w);
            if (m.find()) {
                raw.add(m.group(1));
            }
        }
        try {
            for (StoryCharacter c : characterRepository.findByScriptIdOrderByIdAsc(script.getId())) {
                if (c.getName() != null && !c.getName().isBlank()) {
                    raw.add(c.getName().trim());
                }
            }
        } catch (Exception e) {
            log.warn("Character lookup failed for script {}, tags lose character words: {}",
                    script.getId(), e.getMessage());
        }
        if ("commentary".equals(script.getFormat())) {
            raw.add("动漫杂谈");
            raw.add("番剧解说");
        }
        List<String> norm = PublishSpec.normalizeTags(raw);
        int cap = Math.max(1, spec.tagMax() - 1);
        return norm.size() > cap ? norm.subList(0, cap) : norm;
    }

    private String buildDescription(StoryScript script, List<String> facts, List<String> sources,
                                    List<String> timestamps, List<StoryShot> shots, List<Double> starts) {
        StringBuilder sb = new StringBuilder();
        sb.append(hookLine(script)).append('\n');
        sb.append("下面是本片的事实与素材出处，看完再决定要不要笑。\n\n");

        sb.append(SECTION_FACTS).append('\n');
        if (facts.isEmpty()) {
            sb.append("· 本片不含未经核实的数字结论。\n");
        } else {
            for (String f : facts) {
                sb.append("· ").append(f).append('\n');
            }
        }

        sb.append('\n').append(SECTION_SOURCES).append('\n');
        if (sources.isEmpty()) {
            sb.append(NO_SOURCE_NOTICE).append('\n');
        } else {
            for (String s : sources) {
                sb.append("- ").append(s).append('\n');
            }
        }

        sb.append('\n').append(SECTION_AI).append('\n').append(AI_NOTICE).append('\n');
        sb.append('\n').append(SECTION_DISCLAIMER).append('\n').append(DISCLAIMER).append('\n');

        sb.append('\n').append(SECTION_TIMELINE).append('\n');
        for (int i = 0; i < timestamps.size(); i++) {
            sb.append(timestamps.get(i)).append(' ').append(shotText(shots.get(i))).append('\n');
        }
        if (starts.size() > timestamps.size()) {
            sb.append("（其余 ").append(starts.size() - timestamps.size()).append(" 镜从略）\n");
        }
        return sb.toString().strip();
    }

    private static String hookLine(StoryScript script) {
        String theme = script.getTheme();
        if (theme != null && !theme.isBlank()) {
            String first = theme.strip().split("[。\n]")[0];
            return first.length() > 60 ? first.substring(0, 60) : first;
        }
        return script.getTitle() == null ? "" : script.getTitle().strip();
    }

    /** 时间戳行的文本：有对白先标对白，再标旁白（与合成期音轨顺序一致） */
    private static String shotText(StoryShot shot) {
        String d = shot.getDialogue() == null ? "" : shot.getDialogue().strip();
        String n = shot.getNarration() == null ? "" : shot.getNarration().strip();
        StringBuilder sb = new StringBuilder();
        if (!d.isEmpty()) {
            sb.append(snippet(d, 8)).append(" / ");
        }
        sb.append(snippet(n, TEXT_SNIPPET));
        return sb.toString().strip();
    }

    private static String snippet(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 标题按码点安全截断（截半句话比丢字更糟，但派生值本就允许人工改写） */
    static String clampTitle(String title, int max) {
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) {
            return "未命名稿件";
        }
        int n = t.codePointCount(0, t.length());
        if (n <= max) {
            return t;
        }
        int cut = t.offsetByCodePoints(0, max);
        return t.substring(0, cut);
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
