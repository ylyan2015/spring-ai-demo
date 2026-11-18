package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.generator.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageGenerationRoutingTest {

    private final GeneratedImage img = new GeneratedImage("/images/x.png", "fx", "mock", 1280, 704);

    @Test
    void loraPlusRefsRoutesToCombined() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsLora()).thenReturn(true);
        when(g.supportsReferences()).thenReturn(true);
        when(g.generateWithLoraAndReferences(anyString(), any(), anyInt(), anyInt(), anyString(), any(), anyList()))
                .thenReturn(img);
        LoraSpec lora = new LoraSpec("a.safetensors", null, null);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), lora));
        verify(g).generateWithLoraAndReferences("p", "n", 1280, 720, "a.safetensors", null, List.of("r1"));
        verify(g, never()).generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void refsOnlyRoutesToReferences() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsReferences()).thenReturn(true);
        when(g.generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), null));
        verify(g).generateWithReferences("p", "n", 1280, 720, List.of("r1"));
    }

    @Test
    void loraOnlyKeepsExistingPath() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.supportsLora()).thenReturn(true);
        when(g.generateWithLora(anyString(), any(), anyInt(), anyInt(), anyString(), any())).thenReturn(img);
        LoraSpec lora = new LoraSpec("a.safetensors", null, "/images/cn.png");
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of(), lora));
        verify(g).generateWithLora("p", "n", 1280, 720, "a.safetensors", "/images/cn.png");
    }

    @Test
    void plainWhenNothing() {
        ImageGenerator g = mock(ImageGenerator.class);
        when(g.generate(anyString(), any(), anyInt(), anyInt())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, null, null));
        verify(g).generate("p", "n", 1280, 720);
    }

    @Test
    void refsSilentlyIgnoredWhenUnsupported() {
        ImageGenerator g = mock(ImageGenerator.class); // supportsReferences 默认 false
        when(g.generate(anyString(), any(), anyInt(), anyInt())).thenReturn(img);
        assertSame(img, ImageGenerationService.routeGeneration(g, "p", "n", 1280, 720, List.of("r1"), null));
        verify(g).generate("p", "n", 1280, 720);
        verify(g, never()).generateWithReferences(anyString(), any(), anyInt(), anyInt(), anyList());
    }

    @Test
    void defaultCombinedDegradesToReferencesThenGenerate() {
        // 匿名最小实现：只覆写 generate，验证两级 default 降级链逐字成立
        ImageGenerator minimal = new ImageGenerator() {
            @Override public GeneratedImage generate(String p, String n, int w, int h) { return img; }
            @Override public String getType() { return "minimal"; }
            @Override public String getName() { return "minimal"; }
            @Override public boolean isAvailable() { return true; }
        };
        assertSame(img, minimal.generateWithLoraAndReferences("p", "n", 1, 1, "l", null, List.of("r")));
        assertSame(img, minimal.generateWithReferences("p", "n", 1, 1, List.of("r")));
    }
}
