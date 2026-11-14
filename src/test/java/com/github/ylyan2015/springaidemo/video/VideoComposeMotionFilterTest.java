package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 镜头运动滤镜测试（晃动/头晕修复）：
 * A 超采样消除 zoompan 整数截断抖动（crop 后放大 N 倍再 zoompan、s= 目标尺寸缩回输出）；
 * B 运镜字段生效：固定=纯静态（无 zoompan）、推/拉/pan 按 cameraMovement 映射，不再按镜头序号奇偶交替。
 */
class VideoComposeMotionFilterTest {

    private static final int FRAMES = 125; // 5s * 25fps
    private static final int FPS = 25;
    private static final int W = 1280;
    private static final int H = 720;

    private String filter(String movement) {
        return VideoComposeService.motionFilter(movement, FRAMES, FPS, W, H, 0.06, 4);
    }

    @Test
    void fixedMovementIsStaticWithoutZoompan() {
        String f = filter("固定微距");
        assertFalse(f.contains("zoompan"), "固定镜头不应有 zoompan 动效: " + f);
        assertFalse(f.contains("scale=iw*4"), "静态无需超采样: " + f);
        assertTrue(f.contains("fps=25"), "静态仍需显式帧率: " + f);
    }

    @Test
    void pushInMovementZoomsInWithSupersampling() {
        String f = filter("缓慢推进");
        assertTrue(f.contains("scale=iw*3:ih*3:flags=lanczos"), "720p 自适应 3×（中间长边≤3840）: " + f);
        assertTrue(f.contains("zoompan=z='min(1+0.06*on/125,1.06)'"), "推近表达式: " + f);
        assertTrue(f.contains("s=1280x720"), "zoompan 输出缩回目标尺寸: " + f);
        assertTrue(f.contains("x='iw/2-(iw/zoom/2)'"), "中心缩放: " + f);
    }

    @Test
    void slightPushInAlsoMapsToZoomIn() {
        assertTrue(filter("轻微推近").contains("min(1+0.06*on/125,1.06)"));
    }

    @Test
    void pullBackMovementZoomsOut() {
        String f = filter("缓慢拉远");
        assertTrue(f.contains("zoompan=z='max(1.06-0.06*on/125,1.0)'"), "拉远表达式: " + f);
        assertTrue(f.contains("scale=iw*3:ih*3:flags=lanczos"));
    }

    @Test
    void panMovementMovesHorizontally() {
        String f = filter("缓慢平移");
        assertTrue(f.contains("x='(iw-iw/zoom)*on/125'"), "水平 pan 从左到右: " + f);
        assertTrue(f.contains("y='ih/2-(ih/zoom/2)'"), "垂直居中: " + f);
        assertTrue(f.contains("scale=iw*3:ih*3:flags=lanczos"));
    }

    @Test
    void fullHdCapsSupersampleAtTwo() {
        String f = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, 1920, 1080, 0.06, 4);
        assertTrue(f.contains("scale=iw*2:ih*2:flags=lanczos"), "1080p 中间长边封顶 3840 → 2×: " + f);
        String portrait = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, 1080, 1920, 0.06, 4);
        assertTrue(portrait.contains("scale=iw*2:ih*2"), "9:16 1080p 同样 2×: " + portrait);
    }

    @Test
    void uhdInputDisablesUpscale() {
        String f = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, 3840, 2160, 0.06, 4);
        assertFalse(f.contains("scale=iw*"), "中间长边已 3840 不再放大: " + f);
        assertTrue(f.contains("zoompan"));
    }

    @Test
    void smallResolutionKeepsConfiguredFactor() {
        String f = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, 854, 480, 0.06, 4);
        assertTrue(f.contains("scale=iw*4:ih*4:flags=lanczos"), "480p 保持配置 4×: " + f);
    }

    @Test
    void nullOrUnknownMovementDefaultsToZoomIn() {
        assertTrue(filter(null).contains("min(1+0.06*on/125,1.06)"));
        assertTrue(filter("").contains("min(1+0.06*on/125,1.06)"));
        assertTrue(filter("随便什么").contains("min(1+0.06*on/125,1.06)"));
    }

    @Test
    void supersampleFactorOneDisablesUpscale() {
        String f = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, W, H, 0.06, 1);
        assertFalse(f.contains("scale=iw*"), "supersample=1 时不放大: " + f);
        assertTrue(f.contains("zoompan"));
    }

    @Test
    void zoomAmountIsConfigurable() {
        String f = VideoComposeService.motionFilter("缓慢推进", FRAMES, FPS, W, H, 0.03, 4);
        assertTrue(f.contains("min(1+0.03*on/125,1.03)"), "幅度可配置: " + f);
    }
}