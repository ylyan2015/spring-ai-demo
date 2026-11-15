package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对白角色归属校验回归（切片 4 审查修复）：
 * addShot/batchUpdateShots 与 updateShot 一致，非本脚本角色置 null
 */
class StoryScriptServiceDialogueOwnershipTest {

    private StoryScriptService service;

    @BeforeEach
    void setUp() {
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepo = mock(StoryShotRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        service = new StoryScriptService(scriptRepo, shotRepo, userRepo,
                mock(VideoScriptService.class), charRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(shotRepo.findByScriptIdOrderByShotOrderAsc(7L)).thenReturn(new ArrayList<>());
        StoryCharacter owned = new StoryCharacter(); owned.setId(11L); owned.setName("林晚");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(owned));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void addShotFiltersForeignDialogueCharacter() {
        StoryShot foreign = new StoryShot();
        foreign.setSceneDescription("s"); foreign.setDialogueCharacterId(99L);
        service.addShot(7L, null, foreign);
        assertNull(foreign.getDialogueCharacterId());

        StoryShot owned = new StoryShot();
        owned.setSceneDescription("s2"); owned.setDialogueCharacterId(11L);
        service.addShot(7L, null, owned);
        assertEquals(11L, owned.getDialogueCharacterId());
    }

    @Test
    void batchUpdateShotsFiltersForeignDialogueCharacter() {
        StoryShot foreign = new StoryShot();
        foreign.setSceneDescription("s"); foreign.setDialogueCharacterId(99L);
        StoryShot owned = new StoryShot();
        owned.setSceneDescription("s2"); owned.setDialogueCharacterId(11L);
        service.batchUpdateShots(7L, new ArrayList<>(List.of(foreign, owned)));
        assertNull(foreign.getDialogueCharacterId());
        assertEquals(11L, owned.getDialogueCharacterId());
    }
}