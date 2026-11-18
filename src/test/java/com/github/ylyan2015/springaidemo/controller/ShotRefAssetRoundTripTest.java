package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 素材库切片 S2 Task 5（Task 3 审查 Important#1 收口）：referenceAssetIds 读/写两侧 DTO 组装点贯穿。
 * 批量 PUT /{id}/shots 为删旧插新语义，前端回传 shotToMap 产物若丢绑定即静默清空——round-trip 锁死。
 */
class ShotRefAssetRoundTripTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> shotToMap(StoryShot shot) {
        StoryScriptService service = new StoryScriptService(mock(StoryScriptRepository.class),
                mock(StoryShotRepository.class), mock(UserRepository.class),
                mock(VideoScriptService.class), mock(StoryCharacterRepository.class),
                mock(MediaAssetRepository.class));
        return service.shotToMap(shot);
    }

    private StoryScriptController controller() {
        return new StoryScriptController(mock(StoryScriptService.class), mock(VideoTemplateService.class));
    }

    @Test
    void shotToMapExposesReferenceAssetIdsAsLongList() {
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setReferenceAssetIds("11,12");
        Map<String, Object> map = shotToMap(shot);
        assertEquals(List.of(11L, 12L), map.get("referenceAssetIds"));
    }

    @Test
    void unboundShotOutputsEmptyList() {
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        assertEquals(List.of(), shotToMap(shot).get("referenceAssetIds"));
    }

    @Test
    void batchPutRoundTripPreservesBinding() {
        // 读侧 → 写侧全链路：service.shotToMap → controller.mapToStoryShot（PUT /{id}/shots 的转换路径）
        StoryShot original = new StoryShot();
        original.setId(3L);
        original.setReferenceAssetIds("11,12");
        Map<String, Object> map = shotToMap(original);
        StoryShot rebuilt = controller().mapToStoryShot(map);
        assertEquals("11,12", rebuilt.getReferenceAssetIds());
    }

    @Test
    void absentKeyDoesNotFabricateBinding() {
        StoryShot rebuilt = controller().mapToStoryShot(Map.of("shotOrder", 1));
        assertNull(rebuilt.getReferenceAssetIds());
    }
}
