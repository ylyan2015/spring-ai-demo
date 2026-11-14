package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出合成入参组装测试：cameraMovement 必须贯穿到 VideoComposeService 的 shots Map，
 * 否则运镜滤镜全部回退默认 zoom in（固定镜头仍缩放——实地核验发现的断链）。
 */
class VideoExportServiceComposeMapTest {

    @Test
    void cameraMovementIsPassedThrough() {
        StoryShot shot = new StoryShot();
        shot.setCameraMovement("固定");
        shot.setImageUrl("/images/a.png");
        shot.setDurationSeconds(4);
        shot.setNarration("旁白");

        Map<String, Object> m = VideoExportService.toComposeMap(shot);

        assertEquals("固定", m.get("cameraMovement"), "运镜字段缺失会导致固定镜头仍被缩放");
        assertEquals("/images/a.png", m.get("imageUrl"));
        assertEquals(4, m.get("durationSeconds"));
        assertEquals("旁白", m.get("narration"));
    }

    @Test
    void nullDurationFallsBackToFive() {
        StoryShot shot = new StoryShot();
        shot.setDurationSeconds(null);
        assertEquals(5, VideoExportService.toComposeMap(shot).get("durationSeconds"));
    }

    @Test
    void nullMovementIsPassedAsNullForDefaultMapping() {
        StoryShot shot = new StoryShot();
        shot.setCameraMovement(null);
        assertTrue(VideoExportService.toComposeMap(shot).containsKey("cameraMovement"));
        assertNull(VideoExportService.toComposeMap(shot).get("cameraMovement"));
    }
}