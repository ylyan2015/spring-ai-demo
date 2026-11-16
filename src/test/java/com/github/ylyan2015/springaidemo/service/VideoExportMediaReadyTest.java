package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 镜头媒体就绪判定测试（Phase 5 Track B t7 集成修正）
 * 镜像 compose downloadShotsMedia 视频优先：videoUrl 非空须 videoStatus=READY；否则须 imageUrl+imageStatus=READY。
 * Track B 视频绑定镜头仅有 videoUrl（无 imageUrl）须放行，修复 doExport notReady 误拒。
 */
class VideoExportMediaReadyTest {

    private static StoryShot shot(String videoUrl, String videoStatus, String imageUrl, String imageStatus) {
        StoryShot s = new StoryShot();
        s.setVideoUrl(videoUrl);
        s.setVideoStatus(videoStatus);
        s.setImageUrl(imageUrl);
        s.setImageStatus(imageStatus);
        return s;
    }

    @Test void videoBoundShotWithoutImageIsReady() {
        // 核心修正：评论视频绑定镜头 imageStatus=PENDING/imageUrl=null，但 videoUrl READY → 就绪
        assertTrue(VideoExportService.isShotMediaReady(shot("/v.mp4", "READY", null, "PENDING")));
    }

    @Test void imageShotIsReady() {
        assertTrue(VideoExportService.isShotMediaReady(shot(null, null, "/i.png", "READY")));
    }

    @Test void pendingShotNotReady() {
        assertFalse(VideoExportService.isShotMediaReady(shot(null, null, null, "PENDING")));
    }

    @Test void videoNotReadyBlocksEvenIfImageReady() {
        // 镜像 compose 视频优先：videoUrl 非空但 videoStatus 非 READY → 不就绪（防合成取用未就绪片段）
        assertFalse(VideoExportService.isShotMediaReady(shot("/v.mp4", "GENERATING", "/i.png", "READY")));
    }

    @Test void blankVideoUrlFallsBackToImage() {
        assertTrue(VideoExportService.isShotMediaReady(shot("  ", "NONE", "/i.png", "READY")));
    }
}