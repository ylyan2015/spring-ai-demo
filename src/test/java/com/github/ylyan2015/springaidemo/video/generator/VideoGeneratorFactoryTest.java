package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VideoGeneratorFactoryTest {

    private VideoGeneratorFactory factory(List<VideoGenerator> gens, String configured) {
        VideoGeneratorFactory f = new VideoGeneratorFactory(gens);
        ReflectionTestUtils.setField(f, "configuredType", configured);
        f.init();
        return f;
    }

    @Test
    void defaultNoneAndNoneAlwaysRegistered() {
        VideoGeneratorFactory f = factory(List.of(new NoneVideoGenerator()), null);
        assertEquals("none", f.getActiveType());
        assertTrue(f.getActiveGenerator().isAvailable());
    }

    @Test
    void unimplementedTypeFallsBackToNone() {
        VideoGeneratorFactory f = factory(List.of(new NoneVideoGenerator()), "kling");
        assertEquals("none", f.getActiveType());
    }

    @Test
    void switchToRegisteredAndRejectUnknown() {
        VideoGenerator wan = new DashScopeWanVideoGenerator(null, null, null,
                "https://x", "sk", "wan2.7-i2v", "720P", 600, 5000);
        VideoGeneratorFactory f = factory(List.of(new NoneVideoGenerator(), wan), "none");
        f.switchGenerator("dashscope-wan");
        assertEquals("dashscope-wan", f.getActiveType());
        assertThrows(IllegalArgumentException.class, () -> f.switchGenerator("seedance"));
    }

    @Test
    void emptyKeyWanUnavailable() {
        VideoGenerator wan = new DashScopeWanVideoGenerator(null, null, null,
                "https://x", "", "wan2.7-i2v", "720P", 600, 5000);
        assertFalse(wan.isAvailable());
    }
}