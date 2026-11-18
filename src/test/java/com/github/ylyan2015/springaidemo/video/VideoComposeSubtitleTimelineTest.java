package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字幕时间轴重排决策测试（P0-1：字幕按 TTS 实测有效时长累计重排）
 * 重排基准必须是名义声明时长（预生成 ASS 的时间轴来源），而非 durations——
 * durations 已含真片段 ffprobe 实测值，拿它对比会漏掉「实测≠声明但无 TTS 抬升」的漂移。
 */
class VideoComposeSubtitleTimelineTest {

    private static final String PRECOMPUTED = "Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,预生成";

    // ==================== 重排触发 ====================

    @Test
    void ttsRaisedDurationsRebuildOnEffective() {
        // 静图 5/5/5，第 2 镜旁白实测撑到 8s → 必须按有效时长重排
        AtomicInteger calls = new AtomicInteger();
        List<Integer> seen = new ArrayList<>();
        Function<List<Integer>, String> factory = effective -> {
            calls.incrementAndGet();
            seen.addAll(effective);
            return "REGEN:" + effective;
        };
        String ass = VideoComposeService.resolveSubtitleAss(PRECOMPUTED, factory,
                List.of(5, 5, 5), List.of(5, 8, 5));
        assertEquals("REGEN:[5, 8, 5]", ass);
        assertEquals(1, calls.get());
        assertEquals(List.of(5, 8, 5), seen);
    }

    @Test
    void realClipMeasuredDriftRebuildsEvenWithoutTtsRaise() {
        // 回归：真片段声明 5s 实测 7s、无 TTS 抬升 → durations==effective，
        // 旧条件（durations vs effective）漏判不重排；基准改名义时长后必须重排
        AtomicInteger calls = new AtomicInteger();
        Function<List<Integer>, String> factory = effective -> {
            calls.incrementAndGet();
            return "REGEN:" + effective;
        };
        String ass = VideoComposeService.resolveSubtitleAss(PRECOMPUTED, factory,
                List.of(5, 5), List.of(7, 5));
        assertEquals("REGEN:[7, 5]", ass);
        assertEquals(1, calls.get());
    }

    @Test
    void noDriftKeepsPrecomputedWithoutFactoryCall() {
        // 无漂移沿用预生成结果，不额外付一次翻译成本（en/bilingual 会调翻译接口）
        AtomicInteger calls = new AtomicInteger();
        Function<List<Integer>, String> factory = effective -> {
            calls.incrementAndGet();
            return "REGEN";
        };
        String ass = VideoComposeService.resolveSubtitleAss(PRECOMPUTED, factory,
                List.of(5, 5, 5), List.of(5, 5, 5));
        assertEquals(PRECOMPUTED, ass);
        assertEquals(0, calls.get());
    }

    // ==================== 降级护栏 ====================

    @Test
    void nullFactoryKeepsPrecomputed() {
        assertEquals(PRECOMPUTED, VideoComposeService.resolveSubtitleAss(PRECOMPUTED, null,
                List.of(5), List.of(8)));
    }

    @Test
    void factoryFailureKeepsPrecomputedWithoutBlocking() {
        Function<List<Integer>, String> factory = effective -> {
            throw new RuntimeException("translation unavailable");
        };
        assertEquals(PRECOMPUTED, VideoComposeService.resolveSubtitleAss(PRECOMPUTED, factory,
                List.of(5), List.of(8)));
    }

    @Test
    void blankRegenKeepsPrecomputed() {
        assertEquals(PRECOMPUTED, VideoComposeService.resolveSubtitleAss(PRECOMPUTED,
                effective -> "", List.of(5), List.of(8)));
        assertEquals(PRECOMPUTED, VideoComposeService.resolveSubtitleAss(PRECOMPUTED,
                effective -> null, List.of(5), List.of(8)));
    }

    // ==================== 名义时长基准 ====================

    @Test
    void nominalDurationsReadsDeclaredWithDefaultFive() {
        // 与 SubtitleService.durationsOf / toComposeMap 同规则：缺省/非法兜底 5s
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("durationSeconds", 7);
        Map<String, Object> missing = new LinkedHashMap<>();
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("durationSeconds", "abc");
        assertEquals(List.of(7, 5, 5),
                VideoComposeService.nominalDurationsOf(List.of(declared, missing, invalid)));
    }
}