package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旁白音轨床时长测试（P0：音画逐镜漂移）。
 * 视频时间轴被 xfade 转场压缩（Σ有效 − (n−1)·xfade），而旁白音轨按整段有效时长顺序拼接时
 * 总长等于 Σ有效，于是口播逐镜滞后画面 0.8s、末镜旁白被 -shortest 截掉。
 * 判据一律落在「床总长 == 实际视频时长」与「逐段扣减」上，去掉扣减必须失败。
 */
class VideoComposeNarrationTimelineTest {

    private static final double XF = 0.8;

    @Test
    void bedTotalMatchesCompressedVideoDuration() {
        List<Integer> effective = List.of(5, 5, 5);
        List<Double> bed = VideoComposeService.audioBedDurations(effective, XF);
        double bedTotal = bed.stream().mapToDouble(Double::doubleValue).sum();
        // 与 compose 里 exactDuration 同式：Σ有效 − (n−1)·xfade
        assertEquals(15 - 2 * XF, bedTotal, 1e-9);
    }

    @Test
    void nonLastSegmentsSubtractOneXfadeEach() {
        List<Double> bed = VideoComposeService.audioBedDurations(List.of(5, 6, 7), XF);
        assertEquals(4.2, bed.get(0), 1e-9);
        assertEquals(5.2, bed.get(1), 1e-9);
    }

    @Test
    void lastSegmentKeepsFullDuration() {
        // 末镜后面没有转场可扣，扣了会把结尾旁白截掉
        List<Double> bed = VideoComposeService.audioBedDurations(List.of(5, 4), XF);
        assertEquals(4.0, bed.get(1), 1e-9);
    }

    @Test
    void singleShotKeepsFullDurationBecauseNoTransitionExists() {
        assertEquals(List.of(8.0), VideoComposeService.audioBedDurations(List.of(8), XF));
    }

    @Test
    void emptyInputYieldsEmptyBed() {
        assertEquals(List.of(), VideoComposeService.audioBedDurations(List.of(), XF));
    }

    @Test
    void tinySegmentFlooredToPositiveMinimum() {
        // 1s 镜扣掉 0.8 只剩 0.2，ffmpeg 的 whole_dur/d= 需要正数且不宜过小 → 取下限
        List<Double> bed = VideoComposeService.audioBedDurations(List.of(1, 5), XF);
        assertEquals(0.25, bed.get(0), 1e-9);
        assertTrue(bed.get(0) > 0.0);
    }

    @Test
    void kurumiScriptShapeKeepsAudioAndPictureInSync() {
        // #738 实测形态：17 镜，静图镜名义 4-8s、i2v 镜实测略长
        List<Integer> effective = List.of(4, 5, 4, 6, 7, 6, 7, 5, 5, 5, 6, 6, 6, 8, 5, 4, 4);
        List<Double> bed = VideoComposeService.audioBedDurations(effective, XF);
        int sum = effective.stream().mapToInt(Integer::intValue).sum();
        double bedTotal = bed.stream().mapToDouble(Double::doubleValue).sum();

        assertEquals(17, bed.size());
        assertEquals(sum - 16 * XF, bedTotal, 1e-9);
        assertEquals(4.0, bed.get(16), 1e-9);
        // 音轨床必须短于 Σ有效，否则画面 80s 而口播 93s（旧缺陷）
        assertTrue(bedTotal < sum);
    }
}
