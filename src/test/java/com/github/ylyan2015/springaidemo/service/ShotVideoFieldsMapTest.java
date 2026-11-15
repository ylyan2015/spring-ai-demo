package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切片 2 装配点回归：shotToMap 与 toComposeMap 必须暴露 videoUrl/videoStatus
 */
class ShotVideoFieldsMapTest {

    @Test
    void shotToMapExposesVideoFields() {
        StoryScriptService service = new StoryScriptService(null, null, null, null, null);
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setShotOrder(1);
        shot.setVideoUrl("/images/v.mp4");
        shot.setVideoStatus("READY");
        Map<String, Object> map = service.shotToMap(shot);
        assertEquals("/images/v.mp4", map.get("videoUrl"));
        assertEquals("READY", map.get("videoStatus"));
    }

    @Test
    void toComposeMapExposesVideoUrl() {
        StoryShot shot = new StoryShot();
        shot.setVideoUrl("/images/v.mp4");
        shot.setDurationSeconds(5);
        Map<String, Object> map = VideoExportService.toComposeMap(shot);
        assertEquals("/images/v.mp4", map.get("videoUrl"));
    }
}