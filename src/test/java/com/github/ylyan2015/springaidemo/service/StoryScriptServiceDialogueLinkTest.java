package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切片 4 T1：对白持久化 + 对白角色 name→id 关联（未匹配 warn 置 null）
 */
class StoryScriptServiceDialogueLinkTest {

    @Test
    void linksDialogueCharacterAndWarnsUnmatched() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        VideoScriptService videoScriptService = mock(VideoScriptService.class);
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        StoryScriptService service = new StoryScriptService(scriptRepo, shotRepo, userRepo, videoScriptService, charRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        Storyboard sb = new Storyboard();
        sb.setTitle("t"); sb.setTheme("th");
        CharacterDesign cd = new CharacterDesign();
        cd.setName("林晚");
        sb.setCharacters(List.of(cd));
        Shot shot = new Shot();
        shot.setSceneDescription("s"); shot.setImagePrompt("p");
        shot.setDialogue("你终于来了。");
        shot.setDialogueCharacterName("林晚");
        Shot shot2 = new Shot();
        shot2.setSceneDescription("s2"); shot2.setImagePrompt("p2");
        shot2.setDialogue("你是谁？");
        shot2.setDialogueCharacterName("不存在");
        sb.setShots(List.of(shot, shot2));
        when(videoScriptService.generateScript("idea", null)).thenReturn(sb);
        when(charRepo.save(any())).thenAnswer(inv -> { StoryCharacter c = inv.getArgument(0); c.setId(11L); return c; });
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryScript saved = service.generateAndSaveScript("idea");
        assertEquals("你终于来了。", saved.getShots().get(0).getDialogue());
        assertEquals(11L, saved.getShots().get(0).getDialogueCharacterId());
        assertNull(saved.getShots().get(1).getDialogueCharacterId());
        SecurityContextHolder.clearContext();
    }
}