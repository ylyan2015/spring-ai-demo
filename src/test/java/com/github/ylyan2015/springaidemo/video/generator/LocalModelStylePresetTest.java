package com.github.ylyan2015.springaidemo.video.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地模型风格预设单元测试（模块 G 收尾）
 * 覆盖：按模型名解析内置预设、配置覆盖、提示词/负面词合并
 */
class LocalModelStylePresetTest {

    @Test
    void resolvesFluxPresetByModelName() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve("flux.1-dev", "", "");
        assertTrue(preset.getStylePrefix().contains("FLUX"));
        // FLUX 对负面提示词不敏感，预设为空
        assertTrue(preset.getNegativePrompt().isEmpty());
    }

    @Test
    void resolvesSdxlPresetCaseInsensitively() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve("SDXL-Turbo", "", "");
        assertTrue(preset.getStylePrefix().contains("masterpiece"));
        assertFalse(preset.getNegativePrompt().isEmpty());
    }

    @Test
    void resolvesSd15Preset() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve("stable-diffusion-v1-5", "", "");
        assertTrue(preset.getStylePrefix().contains("masterpiece"));
        assertTrue(preset.getNegativePrompt().contains("lowres"));
    }

    @Test
    void unknownModelFallsBackToGenericPreset() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve("my-custom-model", "", "");
        assertFalse(preset.getStylePrefix().isEmpty());
        assertFalse(preset.getNegativePrompt().isEmpty());
    }

    @Test
    void nullModelFallsBackToGenericPreset() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve(null, "", "");
        assertFalse(preset.getStylePrefix().isEmpty());
    }

    @Test
    void configOverridesBuiltInPreset() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve(
                "sdxl", "custom style prefix", "custom negative");
        assertEquals("custom style prefix", preset.getStylePrefix());
        assertEquals("custom negative", preset.getNegativePrompt());
    }

    @Test
    void blankConfigKeepsBuiltInPreset() {
        LocalModelStylePreset preset = LocalModelStylePreset.resolve("sdxl", "  ", "");
        assertTrue(preset.getStylePrefix().contains("masterpiece"));
        assertFalse(preset.getNegativePrompt().isEmpty());
    }

    @Test
    void composePromptPrependsStylePrefix() {
        String composed = LocalModelStylePreset.composePrompt("cinematic lighting", "a cat on a roof");
        assertEquals("cinematic lighting, a cat on a roof", composed);
    }

    @Test
    void composePromptSkipsBlankPrefix() {
        assertEquals("a cat on a roof", LocalModelStylePreset.composePrompt("", "a cat on a roof"));
        assertEquals("a cat on a roof", LocalModelStylePreset.composePrompt(null, "a cat on a roof"));
    }

    @Test
    void mergeNegativeCombinesWithoutDuplicates() {
        String merged = LocalModelStylePreset.mergeNegative("lowres, bad anatomy", "blurry, lowres");
        assertEquals("lowres, bad anatomy, blurry", merged);
    }

    @Test
    void mergeNegativeHandlesBlankInputs() {
        assertEquals("blurry", LocalModelStylePreset.mergeNegative("", "blurry"));
        assertEquals("lowres", LocalModelStylePreset.mergeNegative("lowres", null));
        assertEquals("", LocalModelStylePreset.mergeNegative(null, null));
    }
}