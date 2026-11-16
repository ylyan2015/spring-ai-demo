package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComfyUiVideoGeneratorTest {

    @TempDir Path temp;
    private final ObjectMapper om = new ObjectMapper();

    private String writeTemplate() throws Exception {
        String tpl = """
            {"4":{"class_type":"LoadImage","inputs":{"image":"{{FIRST_FRAME}}"}},
             "5":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "7":{"class_type":"Wan22ImageToVideoLatent","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}},"length":{{FRAMES}}}},
             "8":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "10":{"class_type":"VHS_VideoCombine","inputs":{"frame_rate":{{FPS}}}}}
            """;
        Path p = temp.resolve("wf.json");
        Files.writeString(p, tpl);
        return p.toString();
    }

    private ComfyUiVideoGenerator newGen(HttpClient http, StorageService storage, String wf,
                                         int timeout, long poll) {
        return new ComfyUiVideoGenerator(storage, om, http, "http://127.0.0.1:8188", wf, 8080, 24, timeout, poll);
    }

    @Test void framesAlignTo4nPlus1() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        assertEquals(121, g.computeFrames(5));   // 5*24=120 → 121
        assertEquals(33, new ComfyUiVideoGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://x", writeTemplate(), 8080, 16, 60, 1).computeFrames(2)); // 2*16=32 → 33
    }

    @Test void resolutionMapping() {
        assertArrayEquals(new int[]{1280, 704}, ComfyUiVideoGenerator.resolutionSize("720P")); // 本机实测 704 高
        assertArrayEquals(new int[]{832, 480}, ComfyUiVideoGenerator.resolutionSize("480P"));
        assertArrayEquals(new int[]{1280, 704}, ComfyUiVideoGenerator.resolutionSize("unknown"));
    }

    @Test void workflowTokensSubstitutedAndPromptEscaped() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        String prompt = "girl \"waves\"\nsmiles";
        var wf = g.buildWorkflow(new VideoGenerationRequest(prompt, "http://a/1.png", null, 5, "720P"), "up1.png");
        assertEquals("up1.png", wf.at("/4/inputs/image").asText());
        assertEquals(prompt, wf.at("/5/inputs/text").asText());
        assertEquals(121, wf.at("/7/inputs/length").asInt());
        assertEquals(1280, wf.at("/7/inputs/width").asInt());
        assertEquals(704, wf.at("/7/inputs/height").asInt());
        assertEquals(24, wf.at("/10/inputs/frame_rate").asInt());
        assertTrue(wf.at("/8/inputs/seed").isNumber());
    }

    @Test @SuppressWarnings("unchecked")
    void happyPathUploadPromptHistoryViewTransfer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"up1.png\",\"subfolder\":\"\",\"type\":\"input\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                + "\"outputs\":{\"10\":{\"gifs\":[{\"filename\":\"out.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(first.body()).thenReturn(new byte[]{9});
        HttpResponse<byte[]> view = mock(HttpResponse.class);
        when(view.statusCode()).thenReturn(200); when(view.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(first, view);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf1");
        when(storage.getAccessUrl("cf1")).thenReturn("/images/cf1.mp4");

        GeneratedVideo v = newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"));
        assertEquals("/images/cf1.mp4", v.getVideoUrl());
        assertEquals("cf1", v.getFileId());
        assertEquals("comfyui", v.getGeneratorType());
        assertEquals(5, v.getDurationSeconds());
        verify(storage).upload(any(), startsWith("comfyui_video_"), eq("video/mp4"));
    }

    @Test @SuppressWarnings("unchecked")
    void relativeFirstFrameFetchesFromSelfServer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"up1.png\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},\"outputs\":{\"10\":{\"videos\":[{\"filename\":\"o.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf2");
        when(storage.getAccessUrl("cf2")).thenReturn("/images/cf2.mp4");

        newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "/images/a.png", null, 5, "720P"));
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals("http://127.0.0.1:8080/images/a.png", cap.getAllValues().get(0).uri().toString());
    }

    @Test @SuppressWarnings("unchecked")
    void historyErrorThrows() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}",
                "{\"p1\":{\"status\":{\"status_str\":\"error\",\"completed\":true},\"outputs\":{}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        ComfyUiVideoGenerator g = newGen(http, mock(StorageService.class), writeTemplate(), 60, 1);
        assertThrows(RuntimeException.class, () ->
                g.generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P")));
    }

    @Test @SuppressWarnings("unchecked")
    void timeoutThrows() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        ComfyUiVideoGenerator g = newGen(http, mock(StorageService.class), writeTemplate(), 0, 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                g.generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P")));
        assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
    }

    @Test void unavailableWhenWorkflowMissing() {
        HttpClient http = mock(HttpClient.class);
        ComfyUiVideoGenerator g = new ComfyUiVideoGenerator(mock(StorageService.class), om, http,
                "http://127.0.0.1:8188", "", 8080, 24, 60, 1);
        assertFalse(g.isAvailable());
        verifyNoInteractions(http);
    }

    @Test @SuppressWarnings("unchecked")
    void availabilityProbesSystemStats() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok);
        assertTrue(newGen(http, mock(StorageService.class), writeTemplate(), 60, 1).isAvailable());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("refused"));
        assertFalse(newGen(http, mock(StorageService.class), writeTemplate(), 60, 1).isAvailable());
    }

    @Test @SuppressWarnings("unchecked")
    void webmOutputKeepsExtensionAndMime() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}",
                "{\"p1\":{\"status\":{\"completed\":true},\"outputs\":{\"10\":{\"videos\":[{\"filename\":\"o.webm\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf3");
        when(storage.getAccessUrl("cf3")).thenReturn("/images/cf3.webm");
        newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"));
        verify(storage).upload(any(), endsWith(".webm"), eq("video/webm"));
    }

    @Test void promptLiteralTokenSurvives() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        String prompt = "move {{FRAMES}} frames at {{WIDTH}}";
        var wf = g.buildWorkflow(new VideoGenerationRequest(prompt, "http://a/1.png", null, 5, "720P"), "up1.png");
        assertEquals(prompt, wf.at("/5/inputs/text").asText());
    }

    @Test void firstFrameFileNameUnique() throws Exception {
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        assertNotEquals(g.firstFrameFileName(), g.firstFrameFileName());
    }

    @Test @SuppressWarnings("unchecked")
    void inputTypeEntriesIgnored() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}",
                "{\"p1\":{\"status\":{\"completed\":true},\"outputs\":{\"7\":{\"images\":[{\"filename\":\"in.mp4\",\"subfolder\":\"\",\"type\":\"input\"}]},\"12\":{\"images\":[{\"filename\":\"out.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf4");
        when(storage.getAccessUrl("cf4")).thenReturn("/images/cf4.mp4");
        newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"));
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofByteArray()));
        String viewUri = cap.getAllValues().get(cap.getAllValues().size() - 1).uri().toString();
        assertTrue(viewUri.contains("filename=out.mp4"), viewUri);
    }

    @Test void findOutputNodeKeepsMetadata() throws Exception {
        // A1 排产加固：outputs 项带 metadata{width,height,fps,duration,frame_count}（手册 §5.1），提取须保留全节点
        ComfyUiVideoGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplate(), 60, 1);
        JsonNode entry = om.readTree("{\"outputs\":{\"12\":{\"images\":[{\"filename\":\"out.mp4\",\"subfolder\":\"\",\"type\":\"output\","
                + "\"metadata\":{\"width\":1280,\"height\":704,\"fps\":24,\"duration\":2.04,\"frame_count\":49}}]}}}");
        JsonNode node = g.parseVideoOutputNode(entry);
        assertEquals(49, node.path("metadata").path("frame_count").asInt());
        assertArrayEquals(new String[]{"out.mp4", "", "output"}, g.parseVideoOutput(entry)); // 存量 seam 回归
    }

    @Test void videoSpecMismatchLenientAndDetects() throws Exception {
        // metadata 缺失宽容跳过（旧版 ComfyUI 无 metadata 不阻断）
        JsonNode noMeta = om.readTree("{\"filename\":\"o.mp4\",\"type\":\"output\"}");
        assertNull(ComfyUiVideoGenerator.videoSpecMismatch(noMeta, 49, 1280, 704));
        JsonNode ok = om.readTree("{\"metadata\":{\"width\":1280,\"height\":704,\"frame_count\":49}}");
        assertNull(ComfyUiVideoGenerator.videoSpecMismatch(ok, 49, 1280, 704));
        JsonNode bad = om.readTree("{\"metadata\":{\"width\":832,\"height\":480,\"frame_count\":29}}");
        String m = ComfyUiVideoGenerator.videoSpecMismatch(bad, 49, 1280, 704);
        assertTrue(m.contains("frame_count"), m);
        assertTrue(m.contains("width"), m);
        assertTrue(m.contains("height"), m);
    }

    @Test @SuppressWarnings("unchecked")
    void metadataMismatchWarnsButSucceeds() throws Exception {
        // 规格不符仅告警不阻断（合成层 ffprobe 实测时长兜底）；免解码核帧不引入新失败模式
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"name\":\"u.png\"}", "{\"prompt_id\":\"p1\"}",
                "{\"p1\":{\"status\":{\"completed\":true},\"outputs\":{\"12\":{\"images\":[{\"filename\":\"o.mp4\",\"subfolder\":\"\",\"type\":\"output\",\"metadata\":{\"frame_count\":999}}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok, ok, ok);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("cf5");
        when(storage.getAccessUrl("cf5")).thenReturn("/images/cf5.mp4");
        GeneratedVideo v = newGen(http, storage, writeTemplate(), 60, 1)
                .generate(new VideoGenerationRequest("p", "http://a/1.png", null, 2, "720P"));
        assertEquals("cf5", v.getFileId());
    }

    @Test void repoTemplateCalibrated() throws Exception {
        // 锁定本机实测校准（手册 §3.1/§5.2-B）：tiled 强制、无 VHS、shift8/CFG5；防回退裸 VAEDecode（本机假死）
        ComfyUiVideoGenerator g = new ComfyUiVideoGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", "docs/comfyui/wan22_i2v_5b.template.json", 8080, 24, 60, 1);
        var wf = g.buildWorkflow(new VideoGenerationRequest("p", "http://a/1.png", null, 5, "720P"), "up1.png");
        java.util.Set<String> types = new java.util.HashSet<>();
        wf.fields().forEachRemaining(e -> types.add(e.getValue().path("class_type").asText()));
        assertTrue(types.contains("VAEDecodeTiled"));
        assertTrue(types.contains("SaveVideo"));
        assertTrue(types.contains("CreateVideo"));
        assertTrue(types.contains("ModelSamplingSD3"));
        assertFalse(types.contains("VHS_VideoCombine"));
        assertFalse(types.contains("VAEDecode"));
        assertEquals(8.0, wf.at("/4/inputs/shift").asDouble());
        assertEquals(5.0, wf.at("/9/inputs/cfg").asDouble());
    }
}