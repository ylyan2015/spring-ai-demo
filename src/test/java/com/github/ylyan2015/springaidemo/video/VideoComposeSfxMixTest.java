package com.github.ylyan2015.springaidemo.video;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoComposeSfxMixTest {

    @Test
    void sfxFilterHasVolumeAndDelay() {
        String filter = VideoComposeService.buildSfxFilter(
                List.of(new VideoComposeService.SfxSpec(Path.of("rain.mp3"), 2.5)), 1, true);
        assertTrue(filter.contains("volume=0.3"));
        assertTrue(filter.contains("adelay=2500|2500"));
        assertTrue(filter.contains("normalize=0"));
    }

    @Test
    void noTracksReturnsNull() {
        assertNull(VideoComposeService.buildSfxFilter(List.of(), 0, true));
    }

    @Test
    void multiTracksWithoutAudioUseLongest() {
        // 无既有音轨时 duration=first 会截掉后续音效，必须 longest（审查修复回归）
        String filter = VideoComposeService.buildSfxFilter(
                List.of(new VideoComposeService.SfxSpec(Path.of("a.mp3"), 0),
                        new VideoComposeService.SfxSpec(Path.of("b.mp3"), 10)), 1, false);
        assertTrue(filter.contains("duration=longest"));
        assertTrue(filter.contains("normalize=0"));
    }
}