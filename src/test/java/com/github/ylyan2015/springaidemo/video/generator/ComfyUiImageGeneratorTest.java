package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5 切片 3：ComfyUiImageGenerator（SD1.5+LoRA 分镜；三模板选择；720→704 实测）
 */
class ComfyUiImageGeneratorTest {

    @TempDir Path temp;
    private final ObjectMapper om = new ObjectMapper();

    private String writeTemplates() throws Exception {
        Path plain = temp.resolve("plain.json");
        Files.writeString(plain, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "3":{"class_type":"CLIPTextEncode","inputs":{"text":"{{NEGATIVE}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}},"batch_size":1}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}}}
            """);
        Path lora = temp.resolve("lora.json");
        Files.writeString(lora, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "3":{"class_type":"CLIPTextEncode","inputs":{"text":"{{NEGATIVE}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "9":{"class_type":"LoraLoader","inputs":{"lora_name":"{{LORA_NAME}}","strength_model":0.8}}}
            """);
        Path canny = temp.resolve("canny.json");
        Files.writeString(canny, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"LoadImage","inputs":{"image":"{{CN_IMAGE}}"}},
             "6":{"class_type":"Canny","inputs":{"low_threshold":0.4,"high_threshold":0.8}},
             "8":{"class_type":"ControlNetApplyAdvanced","inputs":{"strength":0.8}},
             "9":{"class_type":"LoraLoader","inputs":{"lora_name":"{{LORA_NAME}}"}}}
            """);
        return plain + "|" + lora + "|" + canny;
    }

    private ComfyUiImageGenerator newGen(HttpClient http, StorageService storage, String paths,
                                         int timeout, long poll) {
        String[] p = paths.split("\\|");
        return new ComfyUiImageGenerator(storage, om, http, "http://127.0.0.1:8188",
                p[0], p[1], p[2], 8080, timeout, poll);
    }

    private static java.util.Set<String> types(com.fasterxml.jackson.databind.JsonNode wf) {
        java.util.Set<String> s = new java.util.HashSet<>();
        wf.fields().forEachRemaining(e -> s.add(e.getValue().path("class_type").asText()));
        return s;
    }

    @Test void plainWorkflowNoLoraAnd704() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("girl \"waves\"\nsmiles", "bad quality", 1280, 720, null, null);
        assertFalse(types(wf).contains("LoraLoader"));
        assertEquals("girl \"waves\"\nsmiles", wf.at("/2/inputs/text").asText());
        assertEquals("bad quality", wf.at("/3/inputs/text").asText());
        assertEquals(1280, wf.at("/4/inputs/width").asInt());
        assertEquals(704, wf.at("/4/inputs/height").asInt()); // 本机实测 704 高
        assertTrue(wf.at("/5/inputs/seed").isNumber());
    }

    @Test void loraWorkflowSubstitutesName() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", null, 1280, 720, "saki_chan_lora.safetensors", null);
        assertTrue(types(wf).contains("LoraLoader"));
        assertFalse(types(wf).contains("ControlNetApplyAdvanced"));
        assertEquals("saki_chan_lora.safetensors", wf.at("/9/inputs/lora_name").asText());
    }

    @Test void cannyWorkflowWiresControlNet() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", null, 1280, 720, "saki_chan_lora.safetensors", "cn1.png");
        assertTrue(types(wf).contains("ControlNetApplyAdvanced"));
        assertTrue(types(wf).contains("Canny"));
        assertEquals("cn1.png", wf.at("/5/inputs/image").asText());
    }

    @Test void promptLiteralTokenSurvives() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        String prompt = "keep {{WIDTH}} and {{LORA_NAME}} literal";
        var wf = g.buildWorkflow(prompt, null, 1280, 720, null, null);
        assertEquals(prompt, wf.at("/2/inputs/text").asText());
    }

    @Test @SuppressWarnings("unchecked")
    void happyPathTransfersPng() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},"
                + "\"outputs\":{\"7\":{\"images\":[{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> view = mock(HttpResponse.class);
        when(view.statusCode()).thenReturn(200); when(view.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(view);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci1");
        when(storage.getAccessUrl("ci1")).thenReturn("/images/ci1.png");

        GeneratedImage img = newGen(http, storage, writeTemplates(), 60, 1)
                .generate("p", null, 1280, 720);
        assertEquals("/images/ci1.png", img.getImageUrl());
        assertEquals("ci1", img.getFileId());
        assertEquals("comfyui", img.getGeneratorType());
        verify(storage).upload(any(), startsWith("comfyui_img_"), eq("image/png"));
    }

    @Test @SuppressWarnings("unchecked")
    void cnRefFetchesSameOriginAndUploads() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"cn1.png\"}");
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},"
                + "\"outputs\":{\"7\":{\"images\":[{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> bytes = mock(HttpResponse.class);
        when(bytes.statusCode()).thenReturn(200); when(bytes.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(bytes, bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci2");
        when(storage.getAccessUrl("ci2")).thenReturn("/images/ci2.png");

        newGen(http, storage, writeTemplates(), 60, 1)
                .generateWithLora("p", null, 1280, 720, "saki_chan_lora.safetensors", "/images/ref.png");

        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals("http://127.0.0.1:8080/images/ref.png", cap.getAllValues().get(0).uri().toString());
    }

    @Test void unavailableWhenTemplatesMissing() {
        HttpClient http = mock(HttpClient.class);
        ComfyUiImageGenerator g = new ComfyUiImageGenerator(mock(StorageService.class), om, http,
                "http://127.0.0.1:8188", "", "", "", 8080, 60, 1);
        assertFalse(g.isAvailable());
        verifyNoInteractions(http);
    }

    @Test @SuppressWarnings("unchecked")
    void availabilityProbesSystemStats() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok);
        assertTrue(newGen(http, mock(StorageService.class), writeTemplates(), 60, 1).isAvailable());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("refused"));
        assertFalse(newGen(http, mock(StorageService.class), writeTemplates(), 60, 1).isAvailable());
    }

    @Test void capabilityFlags() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        assertTrue(g.supportsLora());
        assertFalse(g.supportsReferences());
        assertEquals("comfyui", g.getType());
    }

    @Test void repoTemplatesCalibrated() throws Exception {
        // 锁定手册 §6 配方：22 步 CFG7 euler/normal；canny 0.4/0.8；SD1.5 用裸 VAEDecode（tiled 强制仅 Wan）
        ComfyUiImageGenerator g = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", "docs/comfyui/sd15_storyboard.template.json",
                "docs/comfyui/sd15_lora_storyboard.template.json", "docs/comfyui/sd15_lora_canny.template.json",
                8080, 60, 1);
        var plain = g.buildWorkflow("p", "n", 1280, 720, null, null);
        assertFalse(types(plain).contains("LoraLoader"));
        assertTrue(types(plain).contains("SaveImage"));
        assertTrue(types(plain).contains("VAEDecode"));
        var lora = g.buildWorkflow("p", "n", 1280, 720, "saki_chan_lora.safetensors", null);
        assertEquals("saki_chan_lora.safetensors", lora.at("/2/inputs/lora_name").asText());
        // 手册 §5.2-A 身份 LoRA 实测配方 1.0/1.0（校准小修 #0）
        assertEquals(1.0, lora.at("/2/inputs/strength_model").asDouble());
        assertEquals(1.0, lora.at("/2/inputs/strength_clip").asDouble());
        var canny = g.buildWorkflow("p", "n", 1280, 720, "saki_chan_lora.safetensors", "cn.png");
        assertTrue(types(canny).contains("ControlNetApplyAdvanced"));
        assertEquals(0.4, canny.at("/6/inputs/low_threshold").asDouble());
        assertEquals(0.8, canny.at("/6/inputs/high_threshold").asDouble());
        // 手册 §5.2-D：end_percent 0.8 + vae 连线（校准小修 #0）
        assertEquals(0.8, canny.at("/8/inputs/end_percent").asDouble());
        assertEquals("1", canny.at("/8/inputs/vae/0").asText());
        assertEquals(2, canny.at("/8/inputs/vae/1").asInt());
        assertEquals(1.0, canny.at("/2/inputs/strength_model").asDouble());
        // KSampler 配方（三模板一致）：节点 5=plain/6=lora/10=canny 不固定，遍历找 KSampler
        for (var wf : new com.fasterxml.jackson.databind.JsonNode[]{plain, lora, canny}) {
            var it = wf.fields();
            while (it.hasNext()) {
                var e = it.next();
                if ("KSampler".equals(e.getValue().path("class_type").asText())) {
                    assertEquals(22, e.getValue().path("inputs").path("steps").asInt());
                    assertEquals(7.0, e.getValue().path("inputs").path("cfg").asDouble());
                    assertEquals("euler", e.getValue().path("inputs").path("sampler_name").asText());
                    assertEquals("normal", e.getValue().path("inputs").path("scheduler").asText());
                }
            }
        }
    }
}