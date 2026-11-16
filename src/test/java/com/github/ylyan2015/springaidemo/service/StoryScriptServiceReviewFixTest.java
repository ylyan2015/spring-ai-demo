package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切片 1 审查修复回归：删除脚本级联清理角色卡 / updateShot 角色归属过滤 / updateStyle trim
 */
class StoryScriptServiceReviewFixTest {

    private StoryCharacterRepository charRepo;
    private StoryScriptRepository scriptRepo;
    private StoryShotRepository shotRepo;
    private StoryScriptService service;

    @BeforeEach
    void setUp() {
        charRepo = mock(StoryCharacterRepository.class);
        scriptRepo = mock(StoryScriptRepository.class);
        shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        service = new StoryScriptService(scriptRepo, shotRepo, userRepo, mock(VideoScriptService.class), charRepo, null);
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript();
        s.setId(7L);
        s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteScriptCleansCharactersFirst() {
        service.deleteScript(7L);
        var order = inOrder(charRepo, scriptRepo);
        order.verify(charRepo).deleteByScriptId(7L);
        order.verify(scriptRepo).delete(any(StoryScript.class));
    }

    @Test
    void updateShotFiltersForeignCharacterIds() {
        StoryShot shot = new StoryShot();
        shot.setId(3L);
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryCharacter owned = new StoryCharacter();
        owned.setId(11L);
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(owned));

        StoryShot updated = service.updateShot(7L, 3L, Map.of("characterIds", List.of(11L, 99L)));
        assertEquals("11", updated.getCharacterIds());
    }

    @Test
    void updateStyleTrimsWhitespace() {
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryScript updated = service.updateStyle(7L, " anime ");
        assertEquals("ANIME", updated.getStyle());
    }
}