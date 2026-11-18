package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.video.generator.ComfyUiImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S3 IP-Adapter 真实冒烟（默认跳过；-Dipadapter=true 执行）。
 * 生效唯一可信证据 = 同 prompt 无 refs 对照组并排目视（spec §6.3），产物落 target/ipadapter-smoke/。
 * DEFINED_PORT(18082) 保证 serverPort 接线=实际端口（参考图相对 URL 同源取字节）；
 * 端口分配：ComfyUiRealSmokeTest=18080、ComfyUiImageRealSmokeTest=18081、本类=18082（DEFINED_PORT 下必须全局唯一）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18082")
class IpAdapterRealSmokeTest {

    @Autowired ComfyUiImageGenerator gen;
    @Autowired com.github.ylyan2015.springaidemo.storage.StorageService storage;

    /** 非 gated 接线回归：RANDOM_PORT 会注入 server.port=0，相对 URL 参考图将取不到字节 */
    @Test
    void selfPortWiredForRelativeRef() {
        assertEquals(18082, org.springframework.test.util.ReflectionTestUtils.getField(gen, "serverPort"));
    }

    @Test
    void ipAdapterSingleShotWithControlGroup() throws Exception {
        assumeTrue(Boolean.getBoolean("ipadapter"), "真实冒烟需 -Dipadapter=true");
        assumeTrue(gen.isAvailable(), "需 ComfyUI 启动且模板已校准");
        assumeTrue(gen.supportsReferences(), "需 sd15_ipadapter.template.json 已校准（Task 9 Step 1）");
        // 固定 fixture（已入库）：此闸只防护 docs/ 被剥离（如容器形态）或 CWD 非仓库根的情况
        Path refSrc = Path.of("docs/comfyui/smoke-ref.png");
        assumeTrue(Files.exists(refSrc), "参考图 fixture 缺失: " + refSrc.toAbsolutePath());
        String fileId;
        try (var in = Files.newInputStream(refSrc)) {
            fileId = storage.upload(in, "smoke-ref.png", "image/png");
        }
        String prompt = "1girl, black long hair, red coat, rainy alley at dusk, neon reflections, best quality, anime coloring";
        String negative = "low quality, blurry, watermark, text";
        // spec §6.3：生效唯一可信证据 = 同 prompt **同 seed** 无 refs 对照组。常规 generate 每次重掷 seed，
        // 两张图的差异 = IP-Adapter 效应 + 随机构图漂移，无法归因；故两组均走 generateWithSeed 固定同一个 seed
        long seed = 20261118L;
        GeneratedImage withRefs = gen.generateWithSeed(prompt, negative, 1280, 720,
                List.of(storage.getAccessUrl(fileId)), seed);
        assertNotNull(withRefs.getFileId());
        assertEquals(1280, withRefs.getWidth());
        assertEquals(704, withRefs.getHeight());
        GeneratedImage control = gen.generateWithSeed(prompt, negative, 1280, 720, null, seed);
        Path out = Path.of("target/ipadapter-smoke");
        Files.createDirectories(out);
        try (var in = storage.download(withRefs.getFileId())) {
            Files.copy(in, out.resolve("with-refs.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        try (var in = storage.download(control.getFileId())) {
            Files.copy(in, out.resolve("control-no-refs.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        // 可解码断言（零依赖，能抓截断/坏图；不断言「两图字节不同」：seed 随机时恒真，给不了生效信号）
        assertNotNull(ImageIO.read(out.resolve("with-refs.png").toFile()), "with-refs 必须是可解码 PNG");
        assertNotNull(ImageIO.read(out.resolve("control-no-refs.png").toFile()), "control 必须是可解码 PNG");
        writeSideBySide(out);
        // 目视对照：side-by-side.png 左=带参考图、右=同 seed 无参考图；左图应继承参考图配色/天气/材质
        System.out.println("[ipadapter-smoke] 目视判读入口: " + out.resolve("side-by-side.png").toAbsolutePath());
    }

    /** 把两张产物并排合成一个 PNG（spec §6.3「并排目视」的交付物：打开一个文件即可判读） */
    private static void writeSideBySide(Path out) throws Exception {
        BufferedImage refs = ImageIO.read(out.resolve("with-refs.png").toFile());
        BufferedImage control = ImageIO.read(out.resolve("control-no-refs.png").toFile());
        BufferedImage both = new BufferedImage(refs.getWidth() + control.getWidth(),
                Math.max(refs.getHeight(), control.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        g.drawImage(refs, 0, 0, null);
        g.drawImage(control, refs.getWidth(), 0, null);
        g.dispose();
        ImageIO.write(both, "png", out.resolve("side-by-side.png").toFile());
    }
}
