package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.Shot;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * endCard 字段贯穿链路单测（cameraMovement 断链教训：组装点需独立测试）
 * 覆盖：saveFromStoryboard 持久化、toStoryboard 反向装配、shotToMap 键贯穿
 */
class StoryScriptEndCardMapTest {

    private StoryScriptRepository scriptRepo;
    private StoryScriptService service;

    @BeforeEach
    void setUp() {
        scriptRepo = mock(StoryScriptRepository.class);
        when(scriptRepo.save(any(StoryScript.class))).thenAnswer(inv -> {
            StoryScript s = inv.getArgument(0);
            if (s.getId() == null) s.setId(7L);
            return s;
        });
        service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class),
                mock(UserRepository.class), mock(VideoScriptService.class),
                mock(StoryCharacterRepository.class), null);
    }

    private Storyboard storyboardWithEndCard() {
        Storyboard sb = new Storyboard();
        sb.setTitle("测试尾卡");
        Shot s1 = new Shot();
        s1.setDurationSeconds(6);
        s1.setNarration("正文镜头");
        s1.setImagePrompt("mountain");
        Shot s2 = new Shot();
        s2.setDurationSeconds(7);
        s2.setNarration("那座山，翻过去了");
        s2.setEndCard(Boolean.TRUE);
        sb.setShots(List.of(s1, s2));
        return sb;
    }

    @Test
    void saveFromStoryboardPersistsEndCard() {
        StoryScript saved = service.saveFromStoryboard(1L, storyboardWithEndCard(), "idea");

        List<StoryShot> shots = saved.getShots();
        assertEquals(2, shots.size());
        assertEquals(null, shots.get(0).getEndCard(), "普通镜 endCard 应为 null");
        assertEquals(Boolean.TRUE, shots.get(1).getEndCard(), "尾卡镜 endCard 必须持久化");
    }

    @Test
    void toStoryboardCarriesEndCardBack() {
        StoryScript saved = service.saveFromStoryboard(1L, storyboardWithEndCard(), "idea");
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(java.util.Optional.of(saved));

        Storyboard round = service.toStoryboard(saved);
        assertEquals(Boolean.TRUE, round.getShots().get(1).getEndCard());
    }

    @Test
    void shotToMapExposesEndCard() {
        StoryShot shot = new StoryShot();
        shot.setId(1L);
        shot.setShotOrder(9);
        shot.setEndCard(Boolean.TRUE);

        Map<String, Object> map = service.shotToMap(shot);
        assertEquals(Boolean.TRUE, map.get("endCard"));
    }
}
