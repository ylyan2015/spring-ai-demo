package com.github.ylyan2015.springaidemo.video.subtitle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字幕服务类
 * 根据镜头旁白（narration）与时长生成带时间轴的 SRT（导出用）与 ASS（FFmpeg 烧录用）字幕内容
 * 支持多语言（模块 F）：zh 原文 / en 英文（LLM 翻译）/ bilingual 中英双语
 *
 * 时间轴计算原则（与 VideoComposeService 的 xfade 合成严格对齐）：
 * - 实际视频时长 = 镜头时长之和 − (镜头数−1) × 转场时长
 * - 镜头 i 的字幕从「上一转场中点」开始，到「下一转场中点」结束
 *   （转场进行到一半时切换字幕，相邻字幕无缝衔接且时间轴不重叠）
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    /** ASS 虚拟坐标系（PlayResX/Y），字号在该坐标系下绝对，libass 按实际分辨率自动等比缩放 */
    private static final int ASS_PLAY_RES_X = 1280;
    private static final int ASS_PLAY_RES_Y = 720;

    @Value("${video.subtitle.font-name:Microsoft YaHei}")
    private String defaultFontName;

    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    /** 译文缓存（原文 → 英文），避免同一旁白重复导出时反复调用 LLM */
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();

    public SubtitleService(ModelService modelService, ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    // ==================== 时间轴与内容生成（底层，双场景复用） ====================

    /**
     * 根据旁白列表与镜头时长计算字幕时间轴
     *
     * @param narrations     每个镜头的旁白（null/空白的镜头不产生字幕，但时间轴照常推进）
     * @param durations      每个镜头的时长（秒）
     * @param xfadeDuration  xfade 转场时长（秒），取 VideoComposeService.XFADE_DURATION 保持一致
     */
    public List<SubtitleCue> buildCues(List<String> narrations, List<Integer> durations, double xfadeDuration) {
        int n = narrations.size();
        List<SubtitleCue> cues = new ArrayList<>();
        if (n == 0 || durations.size() != n) {
            return cues;
        }

        double videoDuration = durations.stream().mapToInt(Integer::intValue).sum()
                - (n > 1 ? (n - 1) * xfadeDuration : 0);

        // offset[k] = d0+...+dk − (k+1)·f，即转场 k 在视频时间轴上的起点
        double[] offset = new double[Math.max(1, n - 1)];
        double cumulative = 0;
        for (int k = 0; k < n - 1; k++) {
            cumulative += durations.get(k);
            offset[k] = cumulative - (k + 1) * xfadeDuration;
        }

        for (int i = 0; i < n; i++) {
            String text = narrations.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            long startMs = i == 0 ? 0 : toMs(offset[i - 1] + xfadeDuration / 2);
            long endMs = i == n - 1 ? toMs(videoDuration) : toMs(offset[i] + xfadeDuration / 2);
            if (endMs <= startMs) {
                endMs = startMs + 500; // 极短镜头兜底，保证字幕可见
            }
            cues.add(new SubtitleCue(text.trim(), startMs, endMs));
        }
        return cues;
    }

    /**
     * 生成 SRT 字幕内容（社媒平台上传用），时间格式 HH:MM:SS,mmm
     */
    public String generateSrtContent(List<String> narrations, List<Integer> durations) {
        return cuesToSrt(buildCues(narrations, durations, VideoComposeService.XFADE_DURATION));
    }

    /**
     * 生成 ASS 字幕内容（FFmpeg subtitles 滤镜烧录用），含样式定义
     */
    public String generateAssContent(List<String> narrations, List<Integer> durations, SubtitleStyle style) {
        return cuesToAss(buildCues(narrations, durations, VideoComposeService.XFADE_DURATION), style);
    }

    /**
     * 字幕列表 → SRT 文本
     */
    public String cuesToSrt(List<SubtitleCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (SubtitleCue cue : cues) {
            sb.append(index++).append('\n')
                    .append(formatSrtTime(cue.startMs())).append(" --> ")
                    .append(formatSrtTime(cue.endMs())).append('\n')
                    .append(escapeSrtText(cue.text())).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 字幕列表 → ASS 文本（含样式行与 Dialogue 事件）
     */
    public String cuesToAss(List<SubtitleCue> cues, SubtitleStyle style) {
        if (cues == null || cues.isEmpty()) {
            return "";
        }
        SubtitleStyle s = (style == null) ? defaultStyle() : style;
        String position = s.isValidPosition() ? s.getPosition().toLowerCase() : "bottom";
        // ASS Alignment（九宫格数字键位）：2=底部居中、5=中部居中、8=顶部居中
        int alignment = switch (position) {
            case "center" -> 5;
            case "top" -> 8;
            default -> 2;
        };
        int marginV = "center".equals(position) ? 0 : 30;

        StringBuilder sb = new StringBuilder();
        sb.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(ASS_PLAY_RES_X).append('\n')
                .append("PlayResY: ").append(ASS_PLAY_RES_Y).append('\n')
                .append("WrapStyle: 0\n")
                .append("ScaledBorderAndShadow: yes\n\n");

        sb.append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, ")
                .append("BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, ")
                .append("BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append(String.format("Style: Default,%s,%d,%s,&H000000FF,%s,&H000000,0,0,0,0,100,100,0,0,1,%d,0,%d,40,40,%d,1%n%n",
                        s.getFontName(), s.getFontSize(), toAssColor(s.getFontColor()),
                        toAssColor(s.getOutlineColor()), s.getOutlineWidth(), alignment, marginV));

        sb.append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (SubtitleCue cue : cues) {
            sb.append(String.format("Dialogue: 0,%s,%s,Default,,0,0,0,,%s%n",
                    formatAssTime(cue.startMs()), formatAssTime(cue.endMs()), escapeAssText(cue.text())));
        }
        return sb.toString();
    }

    // ==================== 实体便捷方法 ====================

    /**
     * 根据镜头实体列表生成 SRT 字幕内容（默认原文语言）
     */
    public String generateSrt(List<StoryShot> shots) {
        return generateSrt(shots, "zh");
    }

    /**
     * 根据镜头实体列表生成 SRT 字幕内容
     *
     * @param language zh 原文 / en 英文 / bilingual 中英双语（非法值回退 zh）
     */
    public String generateSrt(List<StoryShot> shots, String language) {
        return cuesToSrt(buildCues(resolveNarrations(shots, language), durationsOf(shots),
                VideoComposeService.XFADE_DURATION));
    }

    /**
     * 根据镜头实体列表生成 ASS 字幕内容（style 为 null 时使用默认样式，默认原文语言）
     */
    public String generateAss(List<StoryShot> shots, SubtitleStyle style) {
        return generateAss(shots, style, "zh");
    }

    /**
     * 根据镜头实体列表生成 ASS 字幕内容（支持多语言，style 为 null 时使用默认样式）
     */
    public String generateAss(List<StoryShot> shots, SubtitleStyle style, String language) {
        return cuesToAss(buildCues(resolveNarrations(shots, language), durationsOf(shots),
                VideoComposeService.XFADE_DURATION), style);
    }

    /**
     * 合成对齐重载：用**实际生效的逐镜时长**排时间轴。
     * 合成器会把旁白超时的镜头拉长（有效时长 = max(声明, ⌈实测⌉)），真片段也按 ffprobe 实测取时长；
     * 此时若仍按 StoryShot 声明时长生成字幕，该镜之后的所有 cue 会整体错位。
     */
    public String generateAss(List<StoryShot> shots, SubtitleStyle style, String language,
                              List<Integer> effectiveDurations) {
        if (effectiveDurations == null || effectiveDurations.size() != shots.size()) {
            return generateAss(shots, style, language);
        }
        return cuesToAss(buildCues(resolveNarrations(shots, language), effectiveDurations,
                VideoComposeService.XFADE_DURATION), style);
    }

    // ==================== 多语言翻译（模块 F） ====================

    private static final String TRANSLATE_SYSTEM_PROMPT = """
            你是专业的短视频字幕翻译。将用户提供的 JSON 字符串数组中的每条文本翻译为自然流畅的英文。
            要求：
            1. 保持数组顺序与数量完全一致，逐条对应翻译
            2. 口语化、简洁，适合字幕展示，不要逐字硬译
            3. 只输出一个 JSON 字符串数组，不要任何其他文字或 Markdown 标记
            """;

    /** 语言规范化：zh/en/bilingual，非法值回退 zh */
    public static String normalizeLanguage(String language) {
        if (language == null) return "zh";
        return switch (language.toLowerCase()) {
            case "en", "bilingual" -> language.toLowerCase();
            default -> "zh";
        };
    }

    /** 按目标语言解析旁白文本：en 仅译文，bilingual 原文+换行+译文，zh 原文；对白在前拼接（切片 4） */
    private List<String> resolveNarrations(List<StoryShot> shots, String language) {
        List<String> originals = shots.stream().map(s -> {
            String d = s.getDialogue();
            String n = s.getNarration();
            boolean hasD = d != null && !d.isBlank();
            boolean hasN = n != null && !n.isBlank();
            if (hasD && hasN) {
                return d.trim() + " " + n.trim();
            }
            return hasD ? d : n;
        }).toList();
        String lang = normalizeLanguage(language);
        if ("zh".equals(lang)) {
            return originals;
        }
        List<String> english = translateToEnglish(originals);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            String orig = originals.get(i);
            if (orig == null || orig.isBlank()) {
                result.add(orig);
                continue;
            }
            String en = english.get(i);
            result.add("bilingual".equals(lang) ? orig.trim() + "\n" + en : en);
        }
        return result;
    }

    /** 包私有测试 seam：委托私有 resolveNarrations（对白+旁白拼接验证） */
    List<String> resolveNarrationsForTest(List<StoryShot> shots, String language) {
        return resolveNarrations(shots, language);
    }

    /**
     * 批量翻译非空旁白：一次 LLM 调用 + 按原文缓存；
     * LLM 不可用/输出解析失败/数量不符时逐条回退原文，不阻断字幕生成
     */
    private List<String> translateToEnglish(List<String> originals) {
        List<String> result = new ArrayList<>(originals);
        List<Integer> pendingIdx = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            String text = originals.get(i);
            if (text == null || text.isBlank()) continue;
            String cached = translationCache.get(text.trim());
            if (cached != null) {
                result.set(i, cached);
            } else {
                pendingIdx.add(i);
                pendingTexts.add(text.trim());
            }
        }
        if (pendingTexts.isEmpty()) {
            return result;
        }

        try {
            String answer = modelService.getChatClient()
                    .prompt()
                    .system(TRANSLATE_SYSTEM_PROMPT)
                    .user(objectMapper.writeValueAsString(pendingTexts))
                    .call()
                    .content();
            JsonNode arr = objectMapper.readTree(extractJsonArray(answer));
            if (arr.isArray() && arr.size() == pendingTexts.size()) {
                for (int k = 0; k < pendingTexts.size(); k++) {
                    String en = arr.get(k).asText();
                    if (en != null && !en.isBlank()) {
                        translationCache.put(pendingTexts.get(k), en.trim());
                        result.set(pendingIdx.get(k), en.trim());
                    }
                }
            } else {
                log.warn("Subtitle translation output mismatch (expected {} items), fallback to original",
                        pendingTexts.size());
            }
        } catch (Exception e) {
            log.warn("Subtitle translation failed, fallback to original text: {}", e.getMessage());
        }
        return result;
    }

    /** LLM 输出可能被 Markdown 代码块包裹，提取其中的 JSON 数组 */
    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private List<Integer> durationsOf(List<StoryShot> shots) {
        return shots.stream()
                .map(s -> s.getDurationSeconds() == null ? 5 : s.getDurationSeconds())
                .toList();
    }

    /**
     * 默认字幕样式（字体名来自 video.subtitle.font-name 配置）
     */
    public SubtitleStyle defaultStyle() {
        return new SubtitleStyle().fontName(defaultFontName);
    }

    // ==================== 工具方法 ====================

    private long toMs(double seconds) {
        return Math.round(Math.max(0, seconds) * 1000);
    }

    /** SRT 时间格式：HH:MM:SS,mmm */
    private String formatSrtTime(long ms) {
        return String.format("%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** ASS 时间格式：H:MM:SS.cc（厘秒） */
    private String formatAssTime(long ms) {
        long cs = ms / 10;
        return String.format("%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100);
    }

    /** #RRGGBB → ASS 颜色 &H00BBGGRR（BGR 倒序 + 00 透明度） */
    private String toAssColor(String hexColor) {
        String hex = (hexColor == null ? "#FFFFFF" : hexColor).replace("#", "");
        if (hex.length() != 6) {
            hex = "FFFFFF";
        }
        String r = hex.substring(0, 2);
        String g = hex.substring(2, 4);
        String b = hex.substring(4, 6);
        return "&H00" + b.toUpperCase() + g.toUpperCase() + r.toUpperCase();
    }

    /** SRT 文本转义：保留原样，仅去掉首尾空白 */
    private String escapeSrtText(String text) {
        return text.replace("\r\n", "\n").trim();
    }

    /** ASS 文本转义：{} 为特效标记需替换为全角，换行转 \\N */
    private String escapeAssText(String text) {
        return text.replace("\r\n", "\n")
                .replace("{", "｛").replace("}", "｝")
                .replace("\n", "\\N");
    }
}
