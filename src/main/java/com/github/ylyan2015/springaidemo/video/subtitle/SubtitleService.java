package com.github.ylyan2015.springaidemo.video.subtitle;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕服务类
 * 根据镜头旁白（narration）与时长生成带时间轴的 SRT（导出用）与 ASS（FFmpeg 烧录用）字幕内容
 *
 * 时间轴计算原则（与 VideoComposeService 的 xfade 合成严格对齐）：
 * - 实际视频时长 = 镜头时长之和 − (镜头数−1) × 转场时长
 * - 镜头 i 的字幕从「上一转场中点」开始，到「下一转场中点」结束
 *   （转场进行到一半时切换字幕，相邻字幕无缝衔接且时间轴不重叠）
 */
@Service
public class SubtitleService {

    /** ASS 虚拟坐标系（PlayResX/Y），字号在该坐标系下绝对，libass 按实际分辨率自动等比缩放 */
    private static final int ASS_PLAY_RES_X = 1280;
    private static final int ASS_PLAY_RES_Y = 720;

    @Value("${video.subtitle.font-name:Microsoft YaHei}")
    private String defaultFontName;

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
     * 根据镜头实体列表生成 SRT 字幕内容
     */
    public String generateSrt(List<StoryShot> shots) {
        return generateSrtContent(
                shots.stream().map(StoryShot::getNarration).toList(),
                shots.stream().map(s -> s.getDurationSeconds() == null ? 5 : s.getDurationSeconds()).toList());
    }

    /**
     * 根据镜头实体列表生成 ASS 字幕内容（style 为 null 时使用默认样式）
     */
    public String generateAss(List<StoryShot> shots, SubtitleStyle style) {
        return generateAssContent(
                shots.stream().map(StoryShot::getNarration).toList(),
                shots.stream().map(s -> s.getDurationSeconds() == null ? 5 : s.getDurationSeconds()).toList(),
                style);
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
