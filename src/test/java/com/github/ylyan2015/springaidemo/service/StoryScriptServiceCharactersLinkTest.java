package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.CharacterDesign;
import com.github.ylyan2015.springaidemo.video.Shot;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryScriptServiceCharactersLinkTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void linksCharacterNamesToIdsAndWarnsUnmatched() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        VideoScriptService videoScriptService = mock(VideoScriptService.class);
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryScriptService service = new StoryScriptService(scriptRepo, shotRepo, userRepo, videoScriptService, charRepo, null);

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        Storyboard sb = new Storyboard();
        sb.setTitle("t");
        sb.setTheme("th");
        CharacterDesign cd = new CharacterDesign();
        cd.setName("林晚");
        cd.setFeatureTable("long black hair, amber eyes, red wool coat");
        sb.setCharacters(List.of(cd));
        Shot shot = new Shot();
        shot.setSceneDescription("s");
        shot.setImagePrompt("p");
        shot.setCharacterNames(List.of("林晚", "不存在"));
        shot.setShotSize("close");
        sb.setShots(List.of(shot));
        when(videoScriptService.generateScript("idea", null)).thenReturn(sb);
        when(charRepo.save(any())).thenAnswer(inv -> {
            StoryCharacter c = inv.getArgument(0);
            c.setId(11L);
            return c;
        });
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryScript saved = service.generateAndSaveScript("idea");
        assertEquals("11", saved.getShots().get(0).getCharacterIds());
        assertEquals("CLOSE", saved.getShots().get(0).getShotSize());
    }

    @Test
    void nullShotSizeDefaultsMedium() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        VideoScriptService videoScriptService = mock(VideoScriptService.class);
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryScriptService service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class),
                userRepo, videoScriptService, charRepo, null);
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        Storyboard sb = new Storyboard();
        sb.setTitle("t");
        Shot shot = new Shot();
        shot.setSceneDescription("s");
        shot.setImagePrompt("p");
        sb.setShots(List.of(shot));
        when(videoScriptService.generateScript("idea", null)).thenReturn(sb);
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryScript saved = service.generateAndSaveScript("idea");
        assertEquals("MEDIUM", saved.getShots().get(0).getShotSize());
        assertNull(saved.getShots().get(0).getCharacterIds());
    }
}