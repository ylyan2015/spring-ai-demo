package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 尺寸映射测试（OpenAI 兼容 images 协议差异之二）：
 * 固定尺寸枚举的提供者按宽高比就近映射；支持任意尺寸的提供者原样透传。
 */
class ImageSizeMapperTest {

    private static final List<String> OPENAI_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");
    private static final List<String> ZHIPU_SIZES = List.of(
            "1024x1024", "1344x768", "768x1344", "1152x864", "864x1152", "1440x720", "720x1440");

    @Test
    void arbitrarySizeProviderPassesThrough() {
        assertEquals("1280x720", ImageSizeMapper.map(1280, 720, List.of()));
        assertEquals("1280x720", ImageSizeMapper.map(1280, 720, null));
    }

    @Test
    void openAiLandscapeMapsTo1536x1024() {
        assertEquals("1536x1024", ImageSizeMapper.map(1280, 720, OPENAI_SIZES));
    }

    @Test
    void openAiPortraitMapsTo1024x1536() {
        assertEquals("1024x1536", ImageSizeMapper.map(720, 1280, OPENAI_SIZES));
    }

    @Test
    void openAiSquareMapsTo1024x1024() {
        assertEquals("1024x1024", ImageSizeMapper.map(1000, 1000, OPENAI_SIZES));
    }

    @Test
    void zhipuLandscapePrefersClosestRatioNotWidest() {
        // 1280x720 (1.78) 应映射 1344x768 (1.75)，而非更宽的 1440x720 (2.0)
        assertEquals("1344x768", ImageSizeMapper.map(1280, 720, ZHIPU_SIZES));
        assertEquals("768x1344", ImageSizeMapper.map(720, 1280, ZHIPU_SIZES));
    }

    @Test
    void parseCsvHandlesEmptyAndBlank() {
        assertEquals(List.of(), ImageSizeMapper.parse(null));
        assertEquals(List.of(), ImageSizeMapper.parse(""));
        assertEquals(List.of(), ImageSizeMapper.parse("   "));
        assertEquals(OPENAI_SIZES, ImageSizeMapper.parse("1024x1024, 1536x1024,1024x1536"));
    }

    @Test
    void invalidEntriesAreSkipped() {
        assertEquals("1024x1024", ImageSizeMapper.map(1000, 1000, List.of("bad", "1024x1024", "12x")));
    }

    @Test
    void allInvalidEntriesFallBackToPassThrough() {
        assertEquals("1280x720", ImageSizeMapper.map(1280, 720, List.of("bad", "xx")));
    }

    @Test
    void ratioTieTakesFirstEntry() {
        assertEquals("1024x1024", ImageSizeMapper.map(1000, 1000, List.of("1024x1024", "2048x2048")));
    }
}