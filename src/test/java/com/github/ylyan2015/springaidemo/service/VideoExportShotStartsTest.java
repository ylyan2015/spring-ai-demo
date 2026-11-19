package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出记录落库镜起点的提取判据：合成结果有 shotStarts 就落 CSV，缺失/类型不符一律降级 null，
 * 绝不让导出因发布字段而失败（发布是下游能力，不能反过来阻断出片）。
 */
class VideoExportShotStartsTest {

    // 轴本身的算法由 VideoComposeShotTimelineTest 守；本类只测「合成结果 → 导出列」这条提取链路，
    // 所以直接用已知的压缩轴起点常量，不依赖包私有 seam。
    private static final List<Double> STARTS = List.of(0.0, 3.2, 7.4, 10.6);

    @Test
    void extractsCompressedAxisStartsFromComposeResult() {
        String csv = VideoExportService.shotStartsCsv(Map.of("shotStarts", STARTS));
        assertEquals("0.00,3.20,7.40,10.60", csv);
    }

    @Test
    void missingFieldDegradesToNullInsteadOfThrowing() {
        assertNull(VideoExportService.shotStartsCsv(Map.of("totalDuration", 80)));
        assertNull(VideoExportService.shotStartsCsv(null));
        assertNull(VideoExportService.shotStartsCsv(Map.of("shotStarts", "nonsense")));
        assertNull(VideoExportService.shotStartsCsv(Map.of("shotStarts", List.of())));
    }

    @Test
    void parsedBackStartsFeedTimestampRendering() {
        // 落库→读回这条链路必须无损到「两位小数」精度，否则时间戳会逐镜偏移
        List<Double> back = VideoComposeService.parseStartsCsv(VideoExportService.shotStartsCsv(
                Map.of("shotStarts", STARTS)));
        assertEquals(STARTS.size(), back.size());
        for (int i = 0; i < STARTS.size(); i++) {
            assertEquals(STARTS.get(i), back.get(i), 0.005, "镜 " + (i + 1));
        }
    }
}
