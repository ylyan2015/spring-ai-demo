package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-3 竖屏优先：9:16 时提示词装配注入竖构图词（centered subject / vertical framing），
 * 多人镜拆单人特写（SD1.5 多人构图短板）；横屏/null 输出与存量逐字一致（向后兼容契约）。
 */
class ShotPromptAssemblerPortraitTest {

    private final ShotPromptAssembler assembler = new ShotPromptAssembler();

    private static StoryShot shot(String shotSize) {
        StoryShot s = new StoryShot();
        s.setShotOrder(1);
        s.setImagePrompt("1girl, red coat, neon alley");
        s.setShotSize(shotSize);
        return s;
    }

    private static StoryCharacter character(String name) {
        StoryCharacter c = new StoryCharacter();
        c.setName(name);
        c.setFeatureTable("black long hair, red coat");
        return c;
    }

    @Test
    void landscapeAndNullAspectKeepLegacyOutput() {
        StoryShot s = shot("MEDIUM");
        List<StoryCharacter> chars = List.of(character("林晚"));
        String legacy = assembler.assemble(s, VisualStyle.fromName(null), chars);
        assertEquals(legacy, assembler.assemble(s, VisualStyle.fromName(null), chars, null));
        assertEquals(legacy, assembler.assemble(s, VisualStyle.fromName(null), chars, "16:9"));
        assertFalse(legacy.contains("vertical framing"));
    }

    @Test
    void portraitSingleCharacterInjectsVerticalComposition() {
        String p = assembler.assemble(shot("MEDIUM"), VisualStyle.fromName(null),
                List.of(character("林晚")), "9:16");
        assertTrue(p.contains("vertical framing"), p);
        assertTrue(p.contains("centered subject"), p);
        assertFalse(p.contains("only one person in frame"), "单人镜不需要拆镜指令: " + p);
        assertTrue(p.contains("medium shot"), "单人镜保留原景别: " + p);
    }

    @Test
    void portraitMultiCharacterForcesSingleCloseUp() {
        // 多人镜拆单人特写：景别强制 close-up，原 LONG 不得残留
        String p = assembler.assemble(shot("LONG"), VisualStyle.fromName(null),
                List.of(character("林晚"), character("阿哲")), "9:16");
        assertTrue(p.contains("single character close-up"), p);
        assertTrue(p.contains("only one person in frame"), p);
        assertFalse(p.contains("long shot"), "多人竖屏镜不得保留原远景景别: " + p);
        assertTrue(p.contains("vertical framing"), p);
    }

    @Test
    void portraitNoCharactersKeepsShotSize() {
        // 空镜（无角色）只注入竖构图词，不拆镜、保留原景别
        String p = assembler.assemble(shot("LONG"), VisualStyle.fromName(null), List.of(), "9:16");
        assertTrue(p.contains("long shot"), p);
        assertTrue(p.contains("vertical framing"), p);
        assertFalse(p.contains("only one person in frame"), p);
    }
}