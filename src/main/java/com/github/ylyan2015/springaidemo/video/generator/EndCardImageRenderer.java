package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 尾卡渲染器（Phase 5 Track A）：纯色底 + FFmpeg drawtext 压字，零生图/零 GPU。
 * endCard=true 的镜头在 ImageGenerationService 旁路生图 provider 走本渲染器，
 * 产物按普通静图镜头入库，运镜/xfade/字幕/导出链路零改动。
 * drawtext 转义对齐 VideoComposeService.attributionFilter idiom（B0：中文必须 fontfile；
 * Major#1：expansion=none 防 %{…} 注入；两层解析冒号/单引号/反斜杠处理）。
 */
@Component
public class EndCardImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(EndCardImageRenderer.class);

    private final StorageService storageService;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /** 中文字体文件（默认见 application.yml video.endcard.font；空=不带 fontfile，中文将静默不渲染——B0 教训） */
    @Value("${video.endcard.font:}")
    private String fontPath;

    /** 尾卡底色（lavfi color 表达式，默认纯黑） */
    @Value("${video.endcard.color:black}")
    private String backgroundColor;

    public EndCardImageRenderer(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 渲染纯色底+居中压字 PNG 并入库
     *
     * @param text   压字内容（空则仅纯色底）
     * @param width  画宽
     * @param height 画高
     * @return 生成图片（generatorType=endcard）
     */
    public GeneratedImage render(String text, int width, int height) {
        Path out = null;
        try {
            out = Files.createTempFile("endcard-", ".png");
            execute(buildCommand(ffmpegPath, text, fontPath, width, height, backgroundColor, out));
            byte[] bytes = Files.readAllBytes(out);
            if (bytes.length == 0) {
                throw new RuntimeException("FFmpeg 尾卡输出为空");
            }
            String fileId = storageService.upload(new ByteArrayInputStream(bytes),
                    "endcard_" + System.currentTimeMillis() + ".png", "image/png");
            log.info("End card rendered: fileId={}, text={} chars", fileId,
                    text == null ? 0 : text.codePointCount(0, text.length()));
            return new GeneratedImage(storageService.getAccessUrl(fileId), fileId, "endcard", width, height);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("尾卡渲染失败: " + e.getMessage(), e);
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 包私有命令构造 seam（单测直验转义与参数） */
    static List<String> buildCommand(String ffmpeg, String text, String fontFile,
                                     int width, int height, String color, Path out) {
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-f", "lavfi",
                "-i", "color=c=" + (color == null || color.isBlank() ? "black" : color)
                        + ":s=" + width + "x" + height,
                "-frames:v", "1"));
        if (text != null && !text.isBlank()) {
            // 对齐 attributionFilter：反斜杠四层转义、冒号选项级转义、单引号换弯引号防断链
            String safe = text.trim().replace("\\", "\\\\\\\\").replace(":", "\\:").replace("'", "\u2019");
            String font = (fontFile != null && !fontFile.isBlank())
                    ? ":fontfile='" + fontFile.replace("\\", "/").replace(":", "\\:") + "'" : "";
            int len = Math.max(1, safe.codePointCount(0, safe.length()));
            int fontsize = Math.max(28, Math.min((width - 120) / len, height / 6));
            String vf = String.format(
                    "drawtext=text='%s'%s:expansion=none:fontsize=%d:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2",
                    safe, font, fontsize);
            cmd.addAll(List.of("-vf", vf));
        }
        cmd.add(out.toAbsolutePath().toString());
        return cmd;
    }

    private void execute(List<String> cmd) throws Exception {
        log.info("End card ffmpeg: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Path logFile = null;
        Process p = null;
        try {
            logFile = Files.createTempFile("endcard-ffmpeg-", ".log");
            pb.redirectOutput(logFile.toFile());
            p = pb.start();
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("FFmpeg 尾卡渲染超时(60s)");
            }
            if (p.exitValue() != 0) {
                String out = logFile != null ? Files.readString(logFile) : "";
                throw new RuntimeException("FFmpeg 尾卡渲染失败(exit=" + p.exitValue() + "): "
                        + out.substring(0, Math.min(out.length(), 500)));
            }
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            if (logFile != null) {
                Files.deleteIfExists(logFile);
            }
        }
    }
}
