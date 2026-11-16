package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryCharacterServiceTest {

    private StoryCharacterRepository charRepo;
    private StoryScriptRepository scriptRepo;
    private StorageService storage;
    private StoryCharacterService service;

    @BeforeEach
    void setUp() {
        charRepo = mock(StoryCharacterRepository.class);
        scriptRepo = mock(StoryScriptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        service = new StoryCharacterService(charRepo, scriptRepo, userRepo, storage);
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
    void createDefaultsVoiceAndLinksScript() {
        when(charRepo.save(any())).thenAnswer(inv -> {
            StoryCharacter c = inv.getArgument(0);
            c.setId(11L);
            return c;
        });
        Map<String, Object> m = service.create(7L, "林晚", "black hair, red coat", "冷静", null, null, null);
        assertEquals("zh-CN-XiaoxiaoNeural", m.get("voice"));
        assertEquals("林晚", m.get("name"));
        verify(charRepo).save(argThat(c -> c.getScript().getId().equals(7L)));
    }

    @Test
    void createRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, " ", null, null, null, null, null));
    }

    @Test
    void otherUserScriptDenied() {
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.listByScript(7L));
    }

    @Test
    void anonymousDenied() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.listByScript(7L));
    }

    @Test
    void uploadReferenceTransfersToStorage() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        StoryScript s = new StoryScript();
        s.setId(7L);
        c.setScript(s);
        when(charRepo.findByIdAndScriptId(11L, 7L)).thenReturn(Optional.of(c));
        when(storage.upload(any(), eq("ref.png"), eq("image/png"))).thenReturn("fid1");
        when(storage.getAccessUrl("fid1")).thenReturn("/images/fid1.png");
        when(charRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> m = service.uploadReferenceImage(7L, 11L,
                new ByteArrayInputStream(new byte[]{1}), "ref.png", "image/png");
        assertEquals("/images/fid1.png", m.get("referenceImageUrl"));
    }

    @Test
    void uploadRejectsNonImage() {
        StoryCharacter c = new StoryCharacter();
        c.setId(11L);
        StoryScript s = new StoryScript();
        s.setId(7L);
        c.setScript(s);
        when(charRepo.findByIdAndScriptId(11L, 7L)).thenReturn(Optional.of(c));
        assertThrows(IllegalArgumentException.class, () -> service.uploadReferenceImage(7L, 11L,
                new ByteArrayInputStream(new byte[]{1}), "a.txt", "text/plain"));
    }
}