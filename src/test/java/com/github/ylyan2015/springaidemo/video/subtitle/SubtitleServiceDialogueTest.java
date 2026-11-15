package com.github.ylyan2015.springaidemo.video.subtitle;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切片 4 T2：字幕文本含对白（对白在前 + 旁白）
 */
class SubtitleServiceDialogueTest {

    @Test
    void cuesIncludeDialogueBeforeNarration() {
        SubtitleService service = new SubtitleService(null, null);
        StoryShot shot = new StoryShot();
        shot.setShotOrder(1);
        shot.setDurationSeconds(5);
        shot.setDialogue("你终于来了。");
        shot.setNarration("她轻声说。");
        List<SubtitleCue> cues = service.buildCues(
                service.resolveNarrationsForTest(List.of(shot), "zh"), List.of(5), 0.8);
        assertTrue(cues.get(0).text().contains("你终于来了。"));
        assertTrue(cues.get(0).text().contains("她轻声说。"));
        assertTrue(cues.get(0).text().indexOf("你终于来了。") < cues.get(0).text().indexOf("她轻声说。"));
    }

    @Test
    void dialogueOnlyShotStillProducesCue() {
        SubtitleService service = new SubtitleService(null, null);
        StoryShot shot = new StoryShot();
        shot.setShotOrder(1);
        shot.setDurationSeconds(5);
        shot.setDialogue("只有对白。");
        List<SubtitleCue> cues = service.buildCues(
                service.resolveNarrationsForTest(List.of(shot), "zh"), List.of(5), 0.8);
        assertEquals(1, cues.size());
        assertEquals("只有对白。", cues.get(0).text());
    }
}