package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 尾卡渲染器单测：纯色底 + drawtext 压字（零生图依赖）
 * 覆盖：命令安全构造（fontfile/expansion=none/冒号转义/字号计算）+ 真实 FFmpeg 出图
 */
class EndCardImageRendererTest {

    private static final String FFMPEG = System.getProperty("video.ffmpeg-path",
            "D:/Program Files/ffmpeg/bin/ffmpeg.exe");
    private static final String FONT = "C:/Windows/Fonts/msyh.ttc";

    private EndCardImageRenderer newRenderer(StorageService storage) {
        EndCardImageRenderer r = new EndCardImageRenderer(storage);
        ReflectionTestUtils.setField(r, "ffmpegPath", FFMPEG);
        ReflectionTestUtils.setField(r, "fontPath", FONT);
        return r;
    }

    @Test
    void buildCommandCarriesSafeDrawtextOptionsAndEscapes() {
        List<String> cmd = EndCardImageRenderer.buildCommand("ffmpeg",
                "那座山，翻过去了:终局 'quoted'", FONT, 1280, 720, "black", Path.of("out.png"));
        String joined = String.join(" ", cmd);
        assertTrue(cmd.contains("lavfi") && joined.contains("color=c=black:s=1280x720"),
                "应为纯色 lavfi 输入: " + joined);
        assertTrue(cmd.contains("-frames:v"), "单帧 PNG 输出");
        String vf = cmd.get(cmd.indexOf("-vf") + 1);
        assertTrue(vf.contains("expansion=none"), "必须禁用 %{} 展开（Major#1 教训）");
        assertTrue(vf.contains("fontfile='"), "中文必须带 fontfile（B0 教训）");
        assertTrue(vf.contains("C\\:/Windows/Fonts/msyh.ttc"), "fontfile 冒号需转义: " + vf);
        // 文本内冒号转义、单引号换弯引号、反斜杠四层转义（对齐 attributionFilter idiom）
        assertTrue(vf.contains("翻过去了\\:终局"), "文本冒号需转义: " + vf);
        assertTrue(vf.contains("\u2019quoted\u2019"), "单引号应换弯引号: " + vf);
        assertTrue(vf.contains("x=(w-text_w)/2") && vf.contains("y=(h-text_h)/2"), "文字应居中");
        int fontsize = Integer.parseInt(vf.replaceAll(".*fontsize=(\\d+).*", "$1"));
        assertTrue(fontsize >= 28 && fontsize <= 120, "字号应随文本长度自适应: " + fontsize);
    }

    @Test
    void buildCommandBlankTextSkipsDrawtext() {
        List<String> cmd = EndCardImageRenderer.buildCommand("ffmpeg", "  ", FONT, 1280, 720, "black", Path.of("out.png"));
        assertTrue(!String.join(" ", cmd).contains("drawtext"), "空文本不压字，只出纯色底");
    }

    @Test
    void renderProducesPngViaRealFfmpeg() throws Exception {
        assumeTrue(Files.exists(Path.of(FFMPEG)), "FFmpeg 缺失: " + FFMPEG);
        assumeTrue(Files.exists(Path.of(FONT)), "字体缺失: " + FONT);

        AtomicReference<byte[]> captured = new AtomicReference<>();
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(InputStream.class), anyString(), anyString())).thenAnswer(inv -> {
            InputStream in = inv.getArgument(0);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            captured.set(bos.toByteArray());
            return "endcard_fid";
        });
        when(storage.getAccessUrl("endcard_fid")).thenReturn("/images/2026/11/16/endcard_fid.png");

        GeneratedImage img = newRenderer(storage).render("那座山，我们翻过去了", 1280, 720);

        assertNotNull(img);
        assertEquals("/images/2026/11/16/endcard_fid.png", img.getImageUrl());
        assertEquals("endcard", img.getGeneratorType());
        byte[] png = captured.get();
        assertNotNull(png, "storage.upload 未被调用");
        assertTrue(png.length > 500, "PNG 过小: " + png.length);
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(bi, "产物不是可解码 PNG");
        assertEquals(1280, bi.getWidth());
        assertEquals(720, bi.getHeight());
        // 中心区域应显著亮于四角（文字已渲染，防静默不画字回归）
        int corner = bi.getRGB(10, 10) & 0xFF;
        int centerBand = 0;
        for (int x = 200; x < 1080; x += 10) {
            centerBand = Math.max(centerBand, bi.getRGB(x, 360) & 0xFF);
        }
        assertTrue(corner < 30, "背景应为近黑色, got " + corner);
        assertTrue(centerBand > 120, "中心文字行应有亮像素（中文渲染核验）, got " + centerBand);
    }
}
