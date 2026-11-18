package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P0-3 竖屏优先：脚本级 aspectRatio 字段（白名单 16:9/9:16/1:1，null=默认横屏），
 * updateAspect 对齐 updateStyle idiom；scriptToMap 暴露 aspectRatio（null→"16:9"）。
 */
class StoryScriptServiceAspectTest {

    private StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    private StoryScriptService service;
    private StoryScript script;

    @BeforeEach
    void setUp() {
        UserRepository userRepo = mock(UserRepository.class);
        service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class), userRepo,
                mock(VideoScriptService.class), mock(StoryCharacterRepository.class),
                mock(MediaAssetRepository.class));
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateAspectSetsAndSaves() {
        StoryScript updated = service.updateAspect(7L, "9:16");
        assertEquals("9:16", updated.getAspectRatio());
        verify(scriptRepo).save(script);
    }

    @Test
    void invalidAspectRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.updateAspect(7L, "4:3"));
        verify(scriptRepo, never()).save(any());
    }

    @Test
    void nullAspectClearsField() {
        script.setAspectRatio("9:16");
        StoryScript updated = service.updateAspect(7L, null);
        assertNull(updated.getAspectRatio());
    }

    @Test
    void blankAspectClearsFieldAndValueIsTrimmed() {
        script.setAspectRatio("9:16");
        assertNull(service.updateAspect(7L, "  ").getAspectRatio());
        assertEquals("1:1", service.updateAspect(7L, " 1:1 ").getAspectRatio());
    }

    @Test
    void scriptToMapExposesAspectRatioWithLandscapeDefault() {
        Map<String, Object> map = service.scriptToMap(script);
        assertEquals("16:9", map.get("aspectRatio"));
        script.setAspectRatio("9:16");
        assertEquals("9:16", service.scriptToMap(script).get("aspectRatio"));
    }
}