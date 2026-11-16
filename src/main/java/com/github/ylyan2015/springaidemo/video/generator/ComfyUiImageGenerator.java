package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地 ComfyUI 图像生成器（Phase 5 切片 3：SD1.5+角色 LoRA 分镜，实测 ~8s/张）
 * 三模板选择：plain（无 LoRA 角色）/ lora（身份一致性）/ lora+canny（ControlNet 锁机位，gated）；
 * 占位符 {{PROMPT}}/{{NEGATIVE}}/{{WIDTH}}/{{HEIGHT}}/{{SEED}}/{{LORA_NAME}}/{{CN_IMAGE}}，数值不带引号，PROMPT/NEGATIVE 最后替换；
 * 协议复用 ComfyUiClient；720 高→704（本机实测，手册 §6）；SD1.5 用裸 VAEDecode（tiled 强制仅 Wan）。
 */
public class ComfyUiImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiImageGenerator.class);

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ComfyUiClient client;
    private final String plainPath;
    private final String loraPath;
    private final String cannyPath;
    private final int serverPort;

    public ComfyUiImageGenerator(StorageService storageService, ObjectMapper objectMapper,
                                 HttpClient httpClient, String baseUrl,
                                 String plainPath, String loraPath, String cannyPath,
                                 int serverPort, int timeoutSeconds, long pollIntervalMs) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.client = new ComfyUiClient(httpClient, objectMapper, baseUrl, timeoutSeconds, pollIntervalMs);
        this.plainPath = plainPath;
        this.loraPath = loraPath;
        this.cannyPath = cannyPath;
        this.serverPort = serverPort;
        if (plainPath != null && !plainPath.isBlank()) {
            // 相对路径基于启动 CWD：log 实际位置便于打包部署排查
            log.info("ComfyUI image templates: plain={}, lora={}, canny={}",
                    Path.of(plainPath).toAbsolutePath(), Path.of(loraPath).toAbsolutePath(),
                    Path.of(cannyPath).toAbsolutePath());
        }
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        return doGenerate(prompt, negativePrompt, width, height, null, null);
    }

    @Override
    public GeneratedImage generateWithLora(String prompt, String negativePrompt, int width, int height,
                                           String loraName, String controlNetRefUrl) {
        return doGenerate(prompt, negativePrompt, width, height, loraName, controlNetRefUrl);
    }

    @Override
    public boolean supportsLora() {
        return true;
    }

    @Override
    public String getType() {
        return "comfyui";
    }

    @Override
    public String getName() {
        return "本地 ComfyUI (SD1.5+LoRA)";
    }

    @Override
    public boolean isAvailable() {
        if (plainPath == null || plainPath.isBlank() || !Files.exists(Path.of(plainPath))
                || loraPath == null || loraPath.isBlank() || !Files.exists(Path.of(loraPath))) {
            return false;
        }
        return client.probe();
    }

    // ==================== 包私有测试 seam ====================

    /** 720 高→704（本机实测 SD1.5/Wan 共用 704 高；其余尺寸直传） */
    static int[] imageSize(int width, int height) {
        return new int[]{width, height == 720 ? 704 : height};
    }

    /** 输出图片 MIME 映射 */
    static String contentTypeFor(String filename) {
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /** ControlNet 参考图上传文件名：毫秒+UUID 防并行同毫秒 overwrite 串图 */
    String cnFileName() {
        return "comfyui_cn_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".png";
    }

    /** 三模板选择 + 占位符替换（每次读模板，允许热校准）；PROMPT/NEGATIVE 最后替换防二次替换 */
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName) {
        String path = uploadedCnName != null ? cannyPath : loraName != null ? loraPath : plainPath;
        try {
            String tpl = Files.readString(Path.of(path));
            int[] size = imageSize(width, height);
            String wf = tpl;
            if (uploadedCnName != null) {
                wf = wf.replace("{{CN_IMAGE}}", ComfyUiClient.jsonEscape(objectMapper, uploadedCnName));
            }
            if (loraName != null) {
                wf = wf.replace("{{LORA_NAME}}", ComfyUiClient.jsonEscape(objectMapper, loraName));
            }
            wf = wf.replace("{{WIDTH}}", String.valueOf(size[0]))
                    .replace("{{HEIGHT}}", String.valueOf(size[1]))
                    .replace("{{SEED}}", String.valueOf(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE)))
                    .replace("{{NEGATIVE}}", ComfyUiClient.jsonEscape(objectMapper, negativePrompt == null ? "" : negativePrompt))
                    .replace("{{PROMPT}}", ComfyUiClient.jsonEscape(objectMapper, prompt == null ? "" : prompt));
            return (ObjectNode) objectMapper.readTree(wf);
        } catch (Exception e) {
            throw new RuntimeException("工作流模板装配失败（" + path + "）: " + e.getMessage(), e);
        }
    }

    // ==================== 内部方法 ====================

    private GeneratedImage doGenerate(String prompt, String negativePrompt, int width, int height,
                                      String loraName, String controlNetRefUrl) {
        try {
            String cnName = null;
            if (controlNetRefUrl != null && !controlNetRefUrl.isBlank()) {
                cnName = client.uploadImage(fetchRefBytes(controlNetRefUrl), cnFileName());
            }
            int[] size = imageSize(width, height);
            ObjectNode workflow = buildWorkflow(prompt, negativePrompt, width, height, loraName, cnName);
            String promptId = client.submit(workflow);
            JsonNode entry = client.pollEntry(promptId);
            String[] output = ComfyUiClient.findOutput(entry, ".png");
            if (output == null) {
                output = ComfyUiClient.findOutput(entry, ".jpg", ".jpeg", ".webp");
            }
            if (output == null) {
                throw new RuntimeException("ComfyUI 完成但无图片输出: " + ComfyUiClient.truncate(entry.toString(), 500));
            }
            byte[] image = client.download(output[0], output[1], output[2]);
            String ext = output[0].substring(output[0].lastIndexOf('.'));
            String fileId = storageService.upload(new ByteArrayInputStream(image),
                    "comfyui_img_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ext,
                    contentTypeFor(output[0]));
            log.info("ComfyUI image done: promptId={}, fileId={}, lora={}", promptId, fileId, loraName);
            return new GeneratedImage(storageService.getAccessUrl(fileId), fileId, getType(), size[0], size[1]);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ComfyUI 图像生成失败: " + e.getMessage(), e);
        }
    }

    /** 参考图取字节：http 直下 / 相对路径同源（仅 127.0.0.1，SSRF 防护）/ data URI 解码 */
    private byte[] fetchRefBytes(String url) throws Exception {
        if (url.startsWith("data:")) {
            return Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
        }
        String target;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            target = url;
        } else if (url.startsWith("/")) {
            target = "http://127.0.0.1:" + serverPort + url;
        } else {
            throw new RuntimeException("不支持的参考图 URL 形态: " + url);
        }
        return client.fetchBytes(target);
    }
}