package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGeneratorDefaultMethodTest {

    @Test
    void legacyGeneratorIgnoresReferences() {
        ImageGenerator legacy = mock(ImageGenerator.class);
        when(legacy.generate(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new GeneratedImage("u", "f", "legacy", 1, 1));
        when(legacy.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList()))
                .thenCallRealMethod();
        when(legacy.supportsReferences()).thenCallRealMethod();
        assertFalse(legacy.supportsReferences());
        GeneratedImage img = legacy.generateWithReferences("p", null, 64, 64, List.of("ref"));
        assertEquals("u", img.getImageUrl());
        verify(legacy).generate("p", null, 64, 64);
    }
}