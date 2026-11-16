package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐镜出处水印测试（Phase 5 Track B Task 5）
 * attributionFilter：非空出处→drawtext 段；空/null→空串；配置字体→fontfile（B0 中文字形教训）
 */
class VideoComposeAttributionTest {

    @Test void attributionFilterContainsText() {
        String f = VideoComposeService.attributionFilter("来源 第1话", 720);
        assertTrue(f.contains("drawtext"), f);
        assertTrue(f.contains("第1话"), f);
    }

    @Test void emptyAttributionReturnsBlank() {
        assertEquals("", VideoComposeService.attributionFilter("", 720));
        assertEquals("", VideoComposeService.attributionFilter(null, 720));
        assertEquals("", VideoComposeService.attributionFilter("   ", 720));
    }

    @Test void configuredFontAddsFontfile() {
        // B0 教训：本机 fontconfig 缺省配置缺失，中文 drawtext 无 fontfile 静默不渲染→配置字体须落到 fontfile
        String f = VideoComposeService.attributionFilter("来源 第1话", 720, "C:/Windows/Fonts/msyh.ttc");
        assertTrue(f.contains("fontfile="), f);
        assertTrue(f.contains("第1话"), f);
    }

    @Test void noFontConfigOmitsFontfile() {
        // 默认无字体配置→不带 fontfile（避免 Docker/Linux 指向不存在字体致 ffmpeg 报错）
        String f = VideoComposeService.attributionFilter("来源 第1话", 720, "");
        assertFalse(f.contains("fontfile="), f);
    }

    @Test void attributionFilterDisablesTextExpansion() {
        // 审查 Major#1：用户文案含 %{…} 时 drawtext 默认 expansion=normal 会展开/报错→须 expansion=none
        String f = VideoComposeService.attributionFilter("x%{bogus}y", 720);
        assertTrue(f.contains("expansion=none"), f);
    }

    @Test void attributionFilterEscapesBackslashTwoLevels() {
        // 审查 Minor#2：滤镜图级+选项级两层解析各消耗一层→运行时 1 个反斜杠须落 4 个
        String f = VideoComposeService.attributionFilter("a\\b", 720);
        assertTrue(f.contains("a\\\\\\\\b"), f);
    }
}