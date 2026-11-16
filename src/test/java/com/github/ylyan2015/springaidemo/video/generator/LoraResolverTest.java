package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 切片 3：LoRA 解析（首个带 LoRA 角色；ControlNet 参考图 gated）
 */
class LoraResolverTest {

    private StoryCharacter ch(String lora, String trigger, String ref) {
        StoryCharacter c = new StoryCharacter();
        c.setName("c");
        c.setLoraName(lora);
        c.setTriggerWord(trigger);
        c.setReferenceImageUrl(ref);
        return c;
    }

    @Test
    void firstLoraCharacterWins() {
        StoryCharacter a = ch(null, null, null);
        StoryCharacter b = ch("b.safetensors", "b_trigger", "http://ref/b.png");
        LoraSpec spec = new LoraResolver(false).resolve(List.of(a, b));
        assertNotNull(spec);
        assertEquals("b.safetensors", spec.loraName());
        assertEquals("b_trigger", spec.triggerWord());
        assertNull(spec.controlNetRefUrl()); // controlnet 关闭不携带参考图
    }

    @Test
    void controlnetEnabledCarriesReferenceUrl() {
        LoraSpec spec = new LoraResolver(true).resolve(List.of(ch("a.safetensors", "t", "http://ref/a.png")));
        assertNotNull(spec);
        assertEquals("http://ref/a.png", spec.controlNetRefUrl());
    }

    @Test
    void noLoraReturnsNull() {
        assertNull(new LoraResolver(true).resolve(null));
        assertNull(new LoraResolver(true).resolve(List.of()));
        assertNull(new LoraResolver(true).resolve(List.of(ch(" ", null, null))));
    }
}