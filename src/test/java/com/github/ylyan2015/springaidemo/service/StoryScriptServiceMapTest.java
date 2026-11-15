package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceMapTest {

    @Test
    @SuppressWarnings("unchecked")
    void scriptToMapExposesStyleAndCharacters() {
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryScriptService service = new StoryScriptService(mock(StoryScriptRepository.class),
                mock(StoryShotRepository.class), mock(UserRepository.class),
                mock(VideoScriptService.class), charRepo);
        StoryScript s = new StoryScript();
        s.setId(7L);
        s.setStyle("ANIME");
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        c.setName("林晚");
        c.setVoice("zh-CN-XiaoxiaoNeural");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(c));

        Map<String, Object> map = service.scriptToMap(s);
        assertEquals("ANIME", map.get("style"));
        List<Map<String, Object>> chars = (List<Map<String, Object>>) map.get("characters");
        assertEquals("林晚", chars.get(0).get("name"));
    }
}