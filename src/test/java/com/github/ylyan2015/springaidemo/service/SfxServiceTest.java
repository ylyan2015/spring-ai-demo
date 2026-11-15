package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.SfxTrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SfxServiceTest {

    @Test
    void keywordFallbackWhenLlmUnavailable() {
        SfxTrackRepository repo = mock(SfxTrackRepository.class);
        SfxService service = new SfxService(repo, null); // ModelService null → 关键词回退
        ReflectionTestUtils.setField(service, "libraryPath", "./data/sfx");
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        shot.setSceneDescription("细雨落在青石巷，她撑伞缓缓走过");
        Map<Long, String> rec = service.recommendForScript(List.of(shot));
        assertEquals("rain", rec.get(3L));
    }

    @Test
    void noKeywordMatchYieldsNull() {
        SfxService service = new SfxService(mock(SfxTrackRepository.class), null);
        StoryShot shot = new StoryShot();
        shot.setId(4L);
        shot.setSceneDescription("安静的房间");
        assertNull(service.recommendForScript(List.of(shot)).get(4L));
    }
}