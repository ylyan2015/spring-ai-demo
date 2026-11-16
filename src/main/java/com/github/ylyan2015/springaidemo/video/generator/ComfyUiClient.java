package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

/**
 * ComfyUI HTTP 协议共享客户端（Phase 5 切片 3 提取，video/image 生成器共用）。
 * /upload/image → /prompt → /history/{id} → /view；全 localhost。
 * 产物过滤 type=="output"（SaveVideo/SaveImage 在 images 键，LoadVideo 输入也写 images，手册 §5.1）。
 */
class ComfyUiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final long pollIntervalMs;

    ComfyUiClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                  int timeoutSeconds, long pollIntervalMs) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.timeoutSeconds = timeoutSeconds;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** 可用性探针：GET /system_stats（2s 超时），异常=false */
    boolean probe() {
        try {
            HttpRequest probe = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/system_stats"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return httpClient.send(probe, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** multipart 上传图片到 input 目录，返回服务端文件名 */
    String uploadImage(byte[] bytes, String fileName) throws Exception {
        String boundary = "----SpringAiDemoComfyUi" + System.currentTimeMillis();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + fileName + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(bytes);
        bos.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--"
                + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/upload/image"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("ComfyUI 图片上传失败 HTTP " + resp.statusCode()
                    + "（请确认 ComfyUI 已启动: " + baseUrl + "）: " + truncate(resp.body(), 300));
        }
        String name = objectMapper.readTree(resp.body()).path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new RuntimeException("ComfyUI 上传响应无 name: " + truncate(resp.body(), 300));
        }
        return name;
    }

    /** 提交工作流（body={"prompt": graph, "client_id"}），返回 prompt_id */
    String submit(ObjectNode workflow) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("prompt", workflow);
        body.put("client_id", "spring-ai-demo");
        HttpRequest submit = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prompt"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(submit, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("ComfyUI 提交失败 HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body(), 500) + "（请确认 ComfyUI 已启动且工作流已校准）");
        }
        String promptId = objectMapper.readTree(resp.body()).path("prompt_id").asText(null);
        if (promptId == null || promptId.isBlank()) {
            throw new RuntimeException("ComfyUI 响应无 prompt_id: " + truncate(resp.body(), 500));
        }
        return promptId;
    }

    /** 轮询至产物出现或 completed；error/超时抛异常；返回 history entry（调用方提取所需输出） */
    JsonNode pollEntry(String promptId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest poll = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/history/" + promptId))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(poll, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("ComfyUI 轮询失败 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
            }
            JsonNode entry = objectMapper.readTree(resp.body()).path(promptId);
            if (!entry.isMissingNode()) {
                if (findOutput(entry, ".mp4", ".webm", ".mkv", ".png", ".jpg", ".jpeg", ".webp") != null) {
                    return entry;
                }
                JsonNode status = entry.path("status");
                if ("error".equals(status.path("status_str").asText(""))) {
                    throw new RuntimeException("ComfyUI 执行失败: " + truncate(entry.toString(), 500));
                }
                if (status.path("completed").asBoolean(false)) {
                    return entry;
                }
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("ComfyUI 渲染超时（" + timeoutSeconds + "s）: " + promptId);
    }

    /** /view 下载产物字节 */
    byte[] download(String filename, String subfolder, String type) throws Exception {
        String url = baseUrl + "/view?filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8)
                + "&subfolder=" + URLEncoder.encode(subfolder, StandardCharsets.UTF_8)
                + "&type=" + URLEncoder.encode(type, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("ComfyUI 结果下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        return resp.body();
    }

    /** 通用 GET 字节（首帧同源取数等） */
    byte[] fetchBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        return resp.body();
    }

    /** 从 history entry 提取 {filename,subfolder,type}；仅 type==output；无匹配 null */
    static String[] findOutput(JsonNode entry, String... suffixes) {
        JsonNode item = findOutputNode(entry, suffixes);
        return item == null ? null : new String[]{item.path("filename").asText(),
                item.path("subfolder").asText(""), item.path("type").asText("output")};
    }

    /** 同 findOutput 但返回完整输出项节点（保留 metadata{width,height,fps,duration,frame_count}，A1 免解码核帧） */
    static JsonNode findOutputNode(JsonNode entry, String... suffixes) {
        Iterator<Map.Entry<String, JsonNode>> nodes = entry.path("outputs").fields();
        while (nodes.hasNext()) {
            JsonNode node = nodes.next().getValue();
            for (String key : new String[]{"gifs", "videos", "images"}) {
                JsonNode arr = node.path(key);
                if (!arr.isArray()) {
                    continue;
                }
                for (JsonNode item : arr) {
                    if (!"output".equals(item.path("type").asText())) {
                        continue;
                    }
                    String filename = item.path("filename").asText("");
                    for (String suffix : suffixes) {
                        if (filename.endsWith(suffix)) {
                            return item;
                        }
                    }
                }
            }
        }
        return null;
    }

    static String jsonEscape(ObjectMapper om, String value) throws Exception {
        String quoted = om.writeValueAsString(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    static String trimTrailingSlash(String url) {
        String b = url == null ? "" : url.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}