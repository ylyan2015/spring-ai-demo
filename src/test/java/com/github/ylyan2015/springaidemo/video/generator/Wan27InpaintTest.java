package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 切片 3 T3：框选重绘请求体（相对坐标→绝对像素 bbox_list）+ 默认不支持 edit
 */
class Wan27InpaintTest {

    @Test
    void buildEditBodyConvertsRelativeRectToPixels() {
        Wan27ImageGenerator gen = new Wan27ImageGenerator(mock(StorageService.class), new ObjectMapper(),
                mock(HttpClient.class), "https://x", "sk", "wan2.7-image", 120);
        String body = gen.buildEditBody("replace umbrella with red one", "http://img/1.png",
                new double[]{0.1, 0.2, 0.5, 0.4}, 1000, 800).toString();
        assertTrue(body.contains("100") && body.contains("160") && body.contains("600") && body.contains("480"));
        assertTrue(body.contains("replace umbrella with red one"));
        assertTrue(body.contains("bbox_list"));
        assertTrue(body.contains("1000*800"));
    }

    @Test
    void defaultEditUnsupported() {
        ImageGenerator g = new ImageGenerator() {
            @Override public GeneratedImage generate(String p, String n, int w, int h) { return null; }
            @Override public String getType() { return "t"; }
            @Override public String getName() { return "t"; }
            @Override public boolean isAvailable() { return true; }
        };
        assertFalse(g.supportsEdit());
        assertThrows(UnsupportedOperationException.class,
                () -> g.editWithMask("i", "u", new double[]{0, 0, 1, 1}, 10, 10));
    }

    @Test
    void wan27SupportsEdit() {
        Wan27ImageGenerator gen = new Wan27ImageGenerator(mock(StorageService.class), new ObjectMapper(),
                mock(HttpClient.class), "https://x", "sk", "wan2.7-image", 120);
        assertTrue(gen.supportsEdit());
    }
}