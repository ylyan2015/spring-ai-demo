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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 5 切片 3：角色卡 LoRA 字段全链（create/update 透传 + characterToMap 装配 + null 安全）
 */
class CharacterLoraFieldsTest {

    private StoryCharacterRepository characterRepo;
    private StoryCharacterService service;
    private StoryCharacter existing;

    @BeforeEach
    void setUp() {
        characterRepo = mock(StoryCharacterRepository.class);
        StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        service = new StoryCharacterService(characterRepo, scriptRepo, userRepo, mock(StorageService.class));

        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript script = new StoryScript();
        script.setId(7L);
        script.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(script));
        when(characterRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        existing = new StoryCharacter();
        existing.setId(3L);
        existing.setName("saki");
        existing.setLoraName("old.safetensors");
        existing.setTriggerWord("old_trigger");
        when(characterRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(existing));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createCarriesLoraFields() {
        Map<String, Object> m = service.create(7L, "saki", "feat", null, null,
                "saki_chan_lora.safetensors", "saki_chan");
        assertEquals("saki_chan_lora.safetensors", m.get("loraName"));
        assertEquals("saki_chan", m.get("triggerWord"));
    }

    @Test
    void updateLoraFieldsNullSafe() {
        Map<String, Object> m = service.update(7L, 3L, null, null, null, null, "new.safetensors", null);
        assertEquals("new.safetensors", m.get("loraName"));
        assertEquals("old_trigger", m.get("triggerWord")); // null 不覆盖
    }

    @Test
    void mapKeysPresentWhenNull() {
        Map<String, Object> m = StoryCharacterService.characterToMap(new StoryCharacter());
        assertTrue(m.containsKey("loraName"));
        assertTrue(m.containsKey("triggerWord"));
    }
}