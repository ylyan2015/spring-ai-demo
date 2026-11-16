package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShotPromptAssemblerTest {

    private final ShotPromptAssembler assembler = new ShotPromptAssembler();

    private StoryShot shot(String prompt, String size, String camera) {
        StoryShot s = new StoryShot();
        s.setShotOrder(1);
        s.setImagePrompt(prompt);
        s.setShotSize(size);
        s.setCameraMovement(camera);
        return s;
    }

    private StoryCharacter character(String name, String features) {
        StoryCharacter c = new StoryCharacter();
        c.setName(name);
        c.setFeatureTable(features);
        return c;
    }

    @Test
    void assemblesStyleThenCharactersThenBaseThenShotSize() {
        String out = assembler.assemble(shot("a girl under rain", "CLOSE", "推进"),
                VisualStyle.ANIME, List.of(character("林晚", "long black hair, red coat")));
        int iStyle = out.indexOf(VisualStyle.ANIME.getPromptPrefix());
        int iChar = out.indexOf("Character 林晚: long black hair, red coat.");
        int iBase = out.indexOf("a girl under rain");
        int iSize = out.indexOf("close-up");
        assertTrue(iStyle >= 0 && iStyle < iChar && iChar < iBase && iBase < iSize, out);
        assertTrue(out.contains("camera: 推进"));
    }

    @Test
    void emptyCharactersDegradesToStylePlusBase() {
        String out = assembler.assemble(shot("base", null, null), VisualStyle.ANIME, List.of());
        assertEquals(VisualStyle.ANIME.getPromptPrefix() + ", base, medium shot", out);
    }

    @Test
    void blankImagePromptFallsBackToSceneDescription() {
        StoryShot s = shot(" ", "LONG", null);
        s.setSceneDescription("场景描述");
        assertTrue(assembler.assemble(s, VisualStyle.ANIME, null).contains("场景描述"));
        assertTrue(assembler.assemble(s, VisualStyle.ANIME, null).contains("long shot"));
    }

    @Test
    void noPromptNoDescriptionThrows() {
        assertThrows(RuntimeException.class,
                () -> assembler.assemble(shot(null, null, null), VisualStyle.ANIME, null));
    }

    @Test
    void triggerWordInjectedAfterCharacterFeatures() {
        StoryCharacter c = character("saki", "brown hair");
        c.setTriggerWord("saki_chan");
        String out = assembler.assemble(shot("base", null, null), VisualStyle.ANIME, List.of(c));
        assertTrue(out.contains("Character saki: brown hair., saki_chan"), out);
    }

    @Test
    void noTriggerWordKeepsLegacyFormat() {
        String out = assembler.assemble(shot("base", null, null), VisualStyle.ANIME,
                List.of(character("saki", "brown hair")));
        assertEquals(VisualStyle.ANIME.getPromptPrefix() + ", Character saki: brown hair., base, medium shot", out);
    }
}