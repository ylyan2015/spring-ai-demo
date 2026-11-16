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
 * 本地 ComfyUI 图生视频生成器（Phase 5 切片 1：零成本路线，见 docs/local-generation-feasibility.md）
 * 模板无关：工作流 JSON 占位符替换（{{FIRST_FRAME}}/{{PROMPT}}/{{FRAMES}}/{{WIDTH}}/{{HEIGHT}}/{{FPS}}/{{SEED}}），
 * 协议 /upload/image → /prompt → /history/{id} → /view，全 localhost；
 * 首帧相对路径经同源服务取字节（local 存储闭环，绕开公网 URL 约束）。
 * 超时/轮询/fps 均构造器注入（video.comfyui.* 配置）。
 */
public class ComfyUiVideoGenerator implements VideoGenerator {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiVideoGenerator.class);

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ComfyUiClient client;
    private final String workflowPath;
    private final int serverPort;
    private final int fps;

    public ComfyUiVideoGenerator(StorageService storageService, ObjectMapper objectMapper,
                                 HttpClient httpClient, String baseUrl, String workflowPath,
                                 int serverPort, int fps, int timeoutSeconds, long pollIntervalMs) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.client = new ComfyUiClient(httpClient, objectMapper, baseUrl, timeoutSeconds, pollIntervalMs);
        this.workflowPath = workflowPath;
        this.serverPort = serverPort;
        this.fps = fps;
        if (workflowPath != null && !workflowPath.isBlank()) {
            // 相对路径基于启动 CWD（审查 Minor#5）：log 实际位置便于打包部署排查
            log.info("ComfyUI workflow template: {}", Path.of(workflowPath).toAbsolutePath());
        }
    }

    @Override
    public GeneratedVideo generate(VideoGenerationRequest request) {
        int duration = Math.max(2, Math.min(15, request.durationSeconds()));
        try {
            byte[] firstFrame = fetchFirstFrame(request.firstFrameUrl());
            String uploadedName = client.uploadImage(firstFrame, firstFrameFileName());
            ObjectNode workflow = buildWorkflow(request, uploadedName);
            String promptId = client.submit(workflow);
            JsonNode entry = client.pollEntry(promptId);
            JsonNode outNode = parseVideoOutputNode(entry);
            if (outNode == null) {
                throw new RuntimeException("ComfyUI 完成但无视频输出: " + ComfyUiClient.truncate(entry.toString(), 500));
            }
            // A1 排产加固：metadata 免解码核帧（手册 §5.1）；不符仅告警，合成层 ffprobe 实测兜底
            int[] size = resolutionSize(request.resolution());
            String mismatch = videoSpecMismatch(outNode, computeFrames(duration), size[0], size[1]);
            if (mismatch != null) {
                log.warn("ComfyUI 产物规格与请求不符（{}）: promptId={}", mismatch, promptId);
            }
            String filename = outNode.path("filename").asText();
            byte[] video = client.download(filename, outNode.path("subfolder").asText(""),
                    outNode.path("type").asText("output"));
            String ext = filename.substring(filename.lastIndexOf('.'));
            String fileId = storageService.upload(new ByteArrayInputStream(video),
                    "comfyui_video_" + System.currentTimeMillis() + ext, contentTypeFor(filename));
            log.info("ComfyUI i2v done: promptId={}, fileId={}, meta={}", promptId, fileId, outNode.path("metadata"));
            return new GeneratedVideo(storageService.getAccessUrl(fileId), fileId, getType(), duration);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ComfyUI i2v 生成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "comfyui";
    }

    @Override
    public String getName() {
        return "本地 ComfyUI (Wan2.2 i2v)";
    }

    @Override
    public boolean isAvailable() {
        if (workflowPath == null || workflowPath.isBlank() || !Files.exists(Path.of(workflowPath))) {
            return false;
        }
        return client.probe();
    }

    // ==================== 包私有测试 seam ====================

    /** 帧数对齐 Wan 潜空间 4n+1 约束；时长 clamp 2-15 */
    int computeFrames(int durationSeconds) {
        int raw = Math.max(2, Math.min(15, durationSeconds)) * fps;
        return (raw / 4) * 4 + 1;
    }

    /** 分辨率档位映射；720P 高 704 为本机实测值（手册 §5.2-B）；8G 显存不推荐 1080P */
    static int[] resolutionSize(String resolution) {
        if ("480P".equals(resolution)) {
            return new int[]{832, 480};
        }
        if ("1080P".equals(resolution)) {
            return new int[]{1920, 1080};
        }
        return new int[]{1280, 704};
    }

    /** 输出容器 MIME 映射（审查 Minor#3：webm/mkv 不再硬编码 mp4） */
    static String contentTypeFor(String filename) {
        if (filename.endsWith(".webm")) {
            return "video/webm";
        }
        if (filename.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        return "video/mp4";
    }

    /** 首帧上传文件名：毫秒+UUID 防并行同毫秒 overwrite 串帧（审查 Minor#4） */
    String firstFrameFileName() {
        return "comfyui_first_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".png";
    }

    /** 占位符替换装配工作流（每次读模板，允许热校准）；字符串值 JSON escape */
    ObjectNode buildWorkflow(VideoGenerationRequest request, String uploadedName) {
        try {
            String tpl = Files.readString(Path.of(workflowPath));
            int[] size = resolutionSize(request.resolution());
            String wf = tpl
                    .replace("{{FIRST_FRAME}}", ComfyUiClient.jsonEscape(objectMapper, uploadedName))
                    .replace("{{FRAMES}}", String.valueOf(computeFrames(request.durationSeconds())))
                    .replace("{{WIDTH}}", String.valueOf(size[0]))
                    .replace("{{HEIGHT}}", String.valueOf(size[1]))
                    .replace("{{FPS}}", String.valueOf(fps))
                    .replace("{{SEED}}", String.valueOf(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE)))
                    // PROMPT 最后替换：用户文本含字面量 {{TOKEN}} 时不被二次替换（审查 Nit#6）
                    .replace("{{PROMPT}}", ComfyUiClient.jsonEscape(objectMapper, request.prompt() == null ? "" : request.prompt()));
            return (ObjectNode) objectMapper.readTree(wf);
        } catch (Exception e) {
            throw new RuntimeException("工作流模板装配失败（" + workflowPath + "）: " + e.getMessage(), e);
        }
    }

    /** 从 history entry 提取视频输出 {filename,subfolder,type}；优先 mp4，无视频 null */
    String[] parseVideoOutput(JsonNode entry) {
        JsonNode node = parseVideoOutputNode(entry);
        return node == null ? null : new String[]{node.path("filename").asText(),
                node.path("subfolder").asText(""), node.path("type").asText("output")};
    }

    /** 同 parseVideoOutput 但返回完整输出项（保留 metadata，A1 免解码核帧）；优先 mp4 */
    JsonNode parseVideoOutputNode(JsonNode entry) {
        JsonNode mp4 = ComfyUiClient.findOutputNode(entry, ".mp4");
        return mp4 != null ? mp4 : ComfyUiClient.findOutputNode(entry, ".webm", ".mkv");
    }

    /** 产物规格核验（手册 §5.1：outputs metadata 免解码核帧）；metadata 缺失宽容返回 null；不符返回描述（仅告警不阻断） */
    static String videoSpecMismatch(JsonNode outputItem, int expectFrames, int expectW, int expectH) {
        JsonNode meta = outputItem.path("metadata");
        if (!meta.isObject()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (meta.has("frame_count") && meta.path("frame_count").asInt() != expectFrames) {
            sb.append("frame_count=").append(meta.path("frame_count").asInt()).append("≠期望").append(expectFrames).append("; ");
        }
        if (meta.has("width") && meta.path("width").asInt() != expectW) {
            sb.append("width=").append(meta.path("width").asInt()).append("≠期望").append(expectW).append("; ");
        }
        if (meta.has("height") && meta.path("height").asInt() != expectH) {
            sb.append("height=").append(meta.path("height").asInt()).append("≠期望").append(expectH).append("; ");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ==================== 内部方法 ====================

    /** 首帧取字节：http 直下 / 相对路径同源（仅 127.0.0.1，SSRF 防护）/ data URI 解码 */
    private byte[] fetchFirstFrame(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new RuntimeException("镜头无可用首帧图（firstFrameUrl 空）");
        }
        if (url.startsWith("data:")) {
            return Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
        }
        String target;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            target = url;
        } else if (url.startsWith("/")) {
            target = "http://127.0.0.1:" + serverPort + url;
        } else {
            throw new RuntimeException("不支持的首帧 URL 形态: " + url);
        }
        return client.fetchBytes(target);
    }
}