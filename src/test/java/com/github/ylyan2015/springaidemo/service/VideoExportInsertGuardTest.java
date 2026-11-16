package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引用占比护栏测试（Phase 5 Track B Task 5）
 * ratio=Σdur(insertAttribution 非空)/Σdur(all)；超上限拒绝、未超放行、无插入镜放行
 */
class VideoExportInsertGuardTest {

    private static StoryShot shot(int dur, String attribution) {
        StoryShot s = new StoryShot();
        s.setDurationSeconds(dur);
        s.setInsertAttribution(attribution);
        return s;
    }

    @Test void ratioComputesInsertOverTotal() {
        List<StoryShot> shots = List.of(shot(5, "来源 第1话"), shot(5, null), shot(5, ""), shot(5, null));
        assertEquals(0.25, VideoExportService.insertRatio(shots), 1e-9);
    }

    @Test void ratioOverLimitRejected() {
        List<StoryShot> shots = List.of(shot(5, "来源 第1话"), shot(5, null), shot(5, null), shot(5, null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VideoExportService.checkInsertRatio(shots, 0.2));
        assertTrue(e.getMessage().contains("引用占比超限"), e.getMessage());
    }

    @Test void ratioUnderLimitPassed() {
        List<StoryShot> shots = List.of(shot(15, "来源 第1话"), shot(85, null));
        assertDoesNotThrow(() -> VideoExportService.checkInsertRatio(shots, 0.2));
    }

    @Test void noInsertShotsPassed() {
        List<StoryShot> shots = List.of(shot(5, null), shot(5, ""));
        assertEquals(0.0, VideoExportService.insertRatio(shots), 1e-9);
        assertDoesNotThrow(() -> VideoExportService.checkInsertRatio(shots, 0.2));
    }

    @Test void emptyShotsPassed() {
        assertDoesNotThrow(() -> VideoExportService.checkInsertRatio(List.of(), 0.2));
    }
}