package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 镜边界输出测试（发布元数据时间戳的单一真源）。
 *
 * <p>为什么必须有：本仓库已两次因「两套时间轴」出事故——字幕轴偏移（P0-1）与口播漂移（音轨床未压缩）。
 * 简介里的可点击时间戳必须落在与画面/字幕/音轨床同一条轴上，即「累计压缩床」。
 * 判据：起点序列 == 逐段扣 xfade 的累计值；末镜起点 + 末镜全时长 == 成片总时长。
 */
class VideoComposeShotTimelineTest {

    private static final double XF = 0.8;

    @Test
    void shotStartsEqualCumulativeCompressedBeds() {
        List<Double> starts = VideoComposeService.shotStarts(List.of(4, 5, 4, 6), XF);
        assertEquals(4, starts.size());
        assertEquals(0.0, starts.get(0), 1e-9);
        assertEquals(3.2, starts.get(1), 1e-9);   // 4 − 0.8
        assertEquals(7.4, starts.get(2), 1e-9);   // 3.2 + (5 − 0.8)
        assertEquals(10.6, starts.get(3), 1e-9);  // 7.4 + (4 − 0.8)
    }

    @Test
    void lastShotStartPlusFullDurationEqualsVideoLength() {
        List<Integer> eff = List.of(4, 5, 4, 6);
        List<Double> starts = VideoComposeService.shotStarts(eff, XF);
        // 末镜床不扣转场，所以「末镜起点 + 末镜全时长」必须等于 compose 的 exactDuration
        double endOfFilm = starts.get(eff.size() - 1) + eff.get(eff.size() - 1);
        double exactDuration = eff.stream().mapToInt(Integer::intValue).sum() - (eff.size() - 1) * XF;
        assertEquals(exactDuration, endOfFilm, 1e-9);
    }

    @Test
    void startsMatchIndependentCompressedAxisFormula() {
        // 用与本仓实现无关的写法重算一遍「每镜后累计扣一次转场」，
        // 交叉验证镜起点确实落在压缩轴（而不是名义累计轴）
        List<Integer> eff = List.of(4, 5, 4, 6, 7, 6, 7, 5, 6, 6, 6, 8, 6, 8, 5, 4, 4);
        List<Double> starts = VideoComposeService.shotStarts(eff, XF);
        double acc = 0.0;
        for (int i = 0; i < eff.size(); i++) {
            assertEquals(acc, starts.get(i), 1e-9, "镜 " + (i + 1) + " 起点偏离压缩轴");
            acc += eff.get(i) - XF;
        }
        // 上面每镜都多扣了一次（末镜其实无后继转场），故 acc + XF 就是成片总长
        assertEquals(eff.stream().mapToInt(Integer::intValue).sum() - (eff.size() - 1) * XF,
                acc + XF, 1e-9);
    }

    @Test
    void singleShotStartsAtZeroOnly() {
        assertEquals(List.of(0.0), VideoComposeService.shotStarts(List.of(8), XF));
    }

    @Test
    void emptyInputYieldsEmptyStarts() {
        assertEquals(List.of(), VideoComposeService.shotStarts(List.of(), XF));
    }

    @Test
    void csvRoundTripKeepsTwoDecimalsWithoutDrift() {
        List<Double> starts = VideoComposeService.shotStarts(List.of(4, 5, 4, 6), XF);
        String csv = VideoComposeService.toStartsCsv(starts);
        assertEquals("0.00,3.20,7.40,10.60", csv);
        List<Double> back = VideoComposeService.parseStartsCsv(csv);
        assertEquals(4, back.size());
        assertEquals(7.40, back.get(2), 1e-9);
        // 二次序列化必须幂等：累加浮点误差不得在「存→读→再存」循环里逐轮放大
        assertEquals(csv, VideoComposeService.toStartsCsv(back));
    }

    @Test
    void parseStartsCsvIsNullOrBlankSafe() {
        assertEquals(List.of(), VideoComposeService.parseStartsCsv(null));
        assertEquals(List.of(), VideoComposeService.parseStartsCsv("  "));
        assertEquals(List.of(), VideoComposeService.parseStartsCsv("abc"));   // 脏数据不炸整条链路
    }
}
