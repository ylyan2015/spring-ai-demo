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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 ComfyUI 图像生成器（Phase 5 切片 3：SD1.5+角色 LoRA 分镜，实测 ~8s/张）
 * 模板选择（refs 优先）：ipadapter+lora（组合）/ ipadapter / plain / lora / lora+canny；
 * 占位符 {{PROMPT}}/{{NEGATIVE}}/{{WIDTH}}/{{HEIGHT}}/{{SEED}}/{{LORA_NAME}}/{{CN_IMAGE}}
 * + {{REF_IMAGE}}/{{REF_IMAGE2}}/{{IP_WEIGHT}}，数值不带引号，PROMPT/NEGATIVE 最后替换；
 * ipadapter 模板未就位时 supportsReferences=false 自动降级，不阻塞启动（spec §3.4）；
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
    private final String ipadapterPath;
    private final String ipadapterLoraPath;
    private final double ipWeight;
    private final int serverPort;
    /** 组合模板缺失的降级只 WARN 一次（整集 40+ 镜逐镜命中会把 S3 生效线索淹没在重复日志里） */
    private final AtomicBoolean combinedMissingLogged = new AtomicBoolean();

    public ComfyUiImageGenerator(StorageService storageService, ObjectMapper objectMapper,
                                 HttpClient httpClient, String baseUrl,
                                 String plainPath, String loraPath, String cannyPath,
                                 int serverPort, int timeoutSeconds, long pollIntervalMs,
                                 String ipadapterPath, String ipadapterLoraPath, double ipWeight) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.client = new ComfyUiClient(httpClient, objectMapper, baseUrl, timeoutSeconds, pollIntervalMs);
        this.plainPath = plainPath;
        this.loraPath = loraPath;
        this.cannyPath = cannyPath;
        this.serverPort = serverPort;
        this.ipadapterPath = ipadapterPath;
        this.ipadapterLoraPath = ipadapterLoraPath;
        this.ipWeight = ipWeight;
        if (plainPath != null && !plainPath.isBlank()) {
            // 相对路径基于启动 CWD：log 实际位置便于打包部署排查
            log.info("ComfyUI image templates: plain={}, lora={}, canny={}, ipadapter={}, ipadapterLora={}, ipWeight={}",
                    abs(plainPath), abs(loraPath), abs(cannyPath), abs(ipadapterPath), abs(ipadapterLoraPath), ipWeight);
        }
    }

    /** 日志用绝对路径：null/空白原样输出（supportsReferences 同样容忍 null，日志不能先 NPE） */
    private static String abs(String path) {
        return path == null || path.isBlank() ? String.valueOf(path) : Path.of(path).toAbsolutePath().toString();
    }

    @Override
    public GeneratedImage generate(String prompt, String negativePrompt, int width, int height) {
        return doGenerate(prompt, negativePrompt, width, height, null, null, null);
    }

    @Override
    public GeneratedImage generateWithLora(String prompt, String negativePrompt, int width, int height,
                                           String loraName, String controlNetRefUrl) {
        return doGenerate(prompt, negativePrompt, width, height, loraName, controlNetRefUrl, null);
    }

    @Override
    public boolean supportsLora() {
        return true;
    }

    /** 参考图能力唯一开关 = ipadapter 模板是否就位（未校准自动降级纯 prompt，不阻塞启动） */
    @Override
    public boolean supportsReferences() {
        return ipadapterPath != null && !ipadapterPath.isBlank() && Files.exists(Path.of(ipadapterPath));
    }

    @Override
    public GeneratedImage generateWithReferences(String prompt, String negativePrompt, int width, int height,
                                                 List<String> referenceImageUrls) {
        return doGenerate(prompt, negativePrompt, width, height, null, null, referenceImageUrls);
    }

    @Override
    public GeneratedImage generateWithLoraAndReferences(String prompt, String negativePrompt, int width, int height,
                                                        String loraName, String controlNetRefUrl,
                                                        List<String> referenceImageUrls) {
        if (ipadapterLoraPath == null || ipadapterLoraPath.isBlank() || !Files.exists(Path.of(ipadapterLoraPath))) {
            if (combinedMissingLogged.compareAndSet(false, true)) {
                log.warn("IP-Adapter+LoRA 组合模板未就位（路径已配置但文件不存在同样降级），降级纯 LoRA（身份优先）: {}",
                        abs(ipadapterLoraPath));
            } else {
                log.debug("IP-Adapter+LoRA 组合模板未就位，继续降级纯 LoRA: {}", abs(ipadapterLoraPath));
            }
            return generateWithLora(prompt, negativePrompt, width, height, loraName, controlNetRefUrl);
        }
        // 组合模板不含 ControlNet 节点：controlNetRefUrl 忽略（spec YAGNI，机位锁定留纯 LoRA+canny 链路）
        return doGenerate(prompt, negativePrompt, width, height, loraName, null, referenceImageUrls);
    }

    @Override
    public String getType() {
        return "comfyui";
    }

    /**
     * 同 seed 对照专用入口（spec §6.3：「同 prompt 同 seed 无 refs 对照组」= IP-Adapter 生效唯一可信证据）。
     * 常规 generate/generateWithReferences 每次重掷 seed，两张图的差异 = IP-Adapter 效应 + 随机构图漂移，无法归因；
     * 真实冒烟必须用同一个 seed 分别跑 refs 组与对照组（refs 传 null 即对照组）。
     * 不进 {@link ImageGenerator} 接口：仅供同 seed 取证，不参与业务路由。
     */
    public GeneratedImage generateWithSeed(String prompt, String negativePrompt, int width, int height,
                                           List<String> referenceImageUrls, long seed) {
        return doGenerate(prompt, negativePrompt, width, height, null, null, referenceImageUrls, seed);
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

    /** IP-Adapter 参考图上传文件名：与 cnFileName 同款防串图 */
    String refFileName() {
        return "comfyui_ref_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".png";
    }

    /** 存量六参入口：无参考图路径，ipWeight 取配置值 */
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName) {
        return buildWorkflow(prompt, negativePrompt, width, height, loraName, uploadedCnName, null, ipWeight);
    }

    /** 模板选择：refs 优先（组合>单 refs）> canny > lora > plain；PROMPT/NEGATIVE 最后替换防二次替换；seed 重掷 */
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName,
                             List<String> uploadedRefNames, double ipWeight) {
        return buildWorkflow(prompt, negativePrompt, width, height, loraName, uploadedCnName, uploadedRefNames,
                ipWeight, ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
    }

    /** 九参版：seed 由调用方给定（同 seed 对照 seam，唯一调用方 = generateWithSeed） */
    ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                             String loraName, String uploadedCnName,
                             List<String> uploadedRefNames, double ipWeight, long seed) {
        boolean hasRefs = uploadedRefNames != null && !uploadedRefNames.isEmpty();
        String path = hasRefs ? (loraName != null ? ipadapterLoraPath : ipadapterPath)
                : uploadedCnName != null ? cannyPath : loraName != null ? loraPath : plainPath;
        try {
            String tpl = Files.readString(Path.of(path));
            int[] size = imageSize(width, height);
            String wf = tpl;
            if (hasRefs) {
                // 模板固定双槽：缺第二槽时用第一张补位（单张场景在 doGenerate 已补上传，这里兼容直传一名的调用）
                wf = wf.replace("{{REF_IMAGE}}", ComfyUiClient.jsonEscape(objectMapper, uploadedRefNames.get(0)))
                        .replace("{{REF_IMAGE2}}", ComfyUiClient.jsonEscape(objectMapper,
                                uploadedRefNames.size() > 1 ? uploadedRefNames.get(1) : uploadedRefNames.get(0)))
                        .replace("{{IP_WEIGHT}}", String.valueOf(ipWeight)); // 数值不带引号
            }
            if (uploadedCnName != null) {
                wf = wf.replace("{{CN_IMAGE}}", ComfyUiClient.jsonEscape(objectMapper, uploadedCnName));
            }
            if (loraName != null) {
                wf = wf.replace("{{LORA_NAME}}", ComfyUiClient.jsonEscape(objectMapper, loraName));
            }
            wf = wf.replace("{{WIDTH}}", String.valueOf(size[0]))
                    .replace("{{HEIGHT}}", String.valueOf(size[1]))
                    .replace("{{SEED}}", String.valueOf(seed))
                    .replace("{{NEGATIVE}}", ComfyUiClient.jsonEscape(objectMapper, negativePrompt == null ? "" : negativePrompt))
                    .replace("{{PROMPT}}", ComfyUiClient.jsonEscape(objectMapper, prompt == null ? "" : prompt));
            return (ObjectNode) objectMapper.readTree(wf);
        } catch (Exception e) {
            throw new RuntimeException("工作流模板装配失败（" + path + "）: " + e.getMessage(), e);
        }
    }

    // ==================== 内部方法 ====================

    private GeneratedImage doGenerate(String prompt, String negativePrompt, int width, int height,
                                      String loraName, String controlNetRefUrl, List<String> referenceImageUrls) {
        return doGenerate(prompt, negativePrompt, width, height, loraName, controlNetRefUrl, referenceImageUrls,
                ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
    }

    private GeneratedImage doGenerate(String prompt, String negativePrompt, int width, int height,
                                      String loraName, String controlNetRefUrl, List<String> referenceImageUrls,
                                      long seed) {
        try {
            List<String> refNames = null;
            String cnName = null;
            if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
                if (referenceImageUrls.size() > 2) {
                    log.warn("参考图 {} 张超上限，仅取前 2 张（CHARACTER 优先已由 resolver 排序）", referenceImageUrls.size());
                }
                byte[] first = fetchRefBytes(referenceImageUrls.get(0));
                refNames = new ArrayList<>();
                refNames.add(client.uploadImage(first, refFileName()));
                // 单张重复上传补第二槽位（模板 ImageBatch 双 LoadImage 固定两槽）
                refNames.add(client.uploadImage(referenceImageUrls.size() > 1
                        ? fetchRefBytes(referenceImageUrls.get(1)) : first, refFileName()));
            } else if (controlNetRefUrl != null && !controlNetRefUrl.isBlank()) {
                cnName = client.uploadImage(fetchRefBytes(controlNetRefUrl), cnFileName());
            }
            int[] size = imageSize(width, height);
            ObjectNode workflow = buildWorkflow(prompt, negativePrompt, width, height, loraName, cnName, refNames,
                    ipWeight, seed);
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
            log.info("ComfyUI image done: promptId={}, fileId={}, lora={}, refs={}",
                    promptId, fileId, loraName, refNames == null ? 0 : refNames.size());
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