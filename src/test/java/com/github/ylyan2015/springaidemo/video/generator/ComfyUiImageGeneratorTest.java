package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        Path ipa = temp.resolve("ipadapter.json");
        Files.writeString(ipa, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "10":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE}}"}},
             "11":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE2}}"}},
             "12":{"class_type":"ImageBatch","inputs":{}},
             "13":{"class_type":"IPAdapter","inputs":{"weight":{{IP_WEIGHT}}}}}
            """);
        Path ipaLora = temp.resolve("ipadapter_lora.json");
        Files.writeString(ipaLora, """
            {"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{PROMPT}}"}},
             "4":{"class_type":"EmptyLatentImage","inputs":{"width":{{WIDTH}},"height":{{HEIGHT}}}},
             "5":{"class_type":"KSampler","inputs":{"seed":{{SEED}}}},
             "9":{"class_type":"LoraLoader","inputs":{"lora_name":"{{LORA_NAME}}"}},
             "10":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE}}"}},
             "11":{"class_type":"LoadImage","inputs":{"image":"{{REF_IMAGE2}}"}},
             "13":{"class_type":"IPAdapter","inputs":{"weight":{{IP_WEIGHT}}}}}
            """);
        return plain + "|" + lora + "|" + canny + "|" + ipa + "|" + ipaLora;
    }

    private ComfyUiImageGenerator newGen(HttpClient http, StorageService storage, String paths,
                                         int timeout, long poll) {
        String[] p = paths.split("\\|");
        return new ComfyUiImageGenerator(storage, om, http, "http://127.0.0.1:8188",
                p[0], p[1], p[2], 8080, timeout, poll, p[3], p[4], 0.7);
    }

    private static java.util.Set<String> types(com.fasterxml.jackson.databind.JsonNode wf) {
        java.util.Set<String> s = new java.util.HashSet<>();
        wf.fields().forEachRemaining(e -> s.add(e.getValue().path("class_type").asText()));
        return s;
    }

    /**
     * 按请求路径统计 send 次数。比裸次数断言强：ofString 与 ofByteArray 的 handler 都能被
     * any(BodyHandler.class) 匹配（且后声明的 ofByteArray 桩会抢走 /view），只数总次数无法区分
     * 「上传了几次参考图」和「下载了一次产物」，故必须落到路径维度。
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Long> sendsByPath(HttpClient http) throws Exception {
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        return cap.getAllValues().stream().collect(java.util.stream.Collectors.groupingBy(
                r -> java.net.URI.create(r.uri().toString()).getPath(),
                java.util.stream.Collectors.counting()));
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
                "http://127.0.0.1:8188", "", "", "", 8080, 60, 1, "", "", 0.7);
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
        assertTrue(g.supportsReferences());   // newGen 现在含 ipadapter 模板；未就位变体由 supportsReferencesFollowsTemplatePresence 覆盖
        assertEquals("comfyui", g.getType());
    }

    @Test void ipadapterWorkflowSubstitutesRefsAndNumericWeight() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", "n", 1280, 720, null, null, java.util.List.of("r1.png", "r2.png"), 0.7);
        assertTrue(types(wf).contains("IPAdapter"));
        assertTrue(types(wf).contains("ImageBatch"));
        assertEquals("r1.png", wf.at("/10/inputs/image").asText());
        assertEquals("r2.png", wf.at("/11/inputs/image").asText());
        assertTrue(wf.at("/13/inputs/weight").isNumber());   // 数值占位符不带引号回归用例
        assertEquals(0.7, wf.at("/13/inputs/weight").asDouble());
        assertEquals(704, wf.at("/4/inputs/height").asInt());
    }

    @Test void combinedWorkflowHasLoraAndIpAdapter() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        var wf = g.buildWorkflow("p", "n", 1280, 720, "saki.safetensors", null, java.util.List.of("r1.png", "r2.png"), 0.7);
        assertTrue(types(wf).contains("LoraLoader"));
        assertTrue(types(wf).contains("IPAdapter"));
        assertEquals("saki.safetensors", wf.at("/9/inputs/lora_name").asText());
    }

    @Test void supportsReferencesFollowsTemplatePresence() throws Exception {
        String[] p = writeTemplates().split("\\|");
        ComfyUiImageGenerator with = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, p[3], p[4], 0.7);
        assertTrue(with.supportsReferences());
        ComfyUiImageGenerator without = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, "", "", 0.7);
        assertFalse(without.supportsReferences());  // 模板未就位自动降级，不阻塞启动
        // 生产真实形态：@Value 默认值给出非空路径但文件不在盘上——Files.exists 这一段必须也能判 false
        ComfyUiImageGenerator notOnDisk = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1,
                temp.resolve("absent_ipadapter.json").toString(), p[4], 0.7);
        assertFalse(notOnDisk.supportsReferences(), "路径已配置但文件缺失同样必须判 false");
    }

    @Test @SuppressWarnings("unchecked")
    void singleRefUploadsTwiceToFillBothSlots() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"ref.png\"}");
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
                .thenReturn(upload, upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci3");
        when(storage.getAccessUrl("ci3")).thenReturn("/images/ci3.png");

        newGen(http, storage, writeTemplates(), 60, 1)
                .generateWithReferences("p", null, 1280, 720, java.util.List.of("data:image/png;base64,AQ=="));
        var paths = sendsByPath(http);
        // 单张重复上传补第二槽位：模板 ImageBatch 固定双 LoadImage，只传一次则第二槽为空
        assertEquals(2L, paths.getOrDefault("/upload/image", 0L));
        assertEquals(1L, paths.getOrDefault("/prompt", 0L));
        assertEquals(1L, paths.getOrDefault("/history/p1", 0L));
        assertEquals(1L, paths.getOrDefault("/view", 0L));
        // data: URI 无需回源下载 → 全局恰好 5 个请求，多一个即说明有额外往返
        assertEquals(5L, paths.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test @SuppressWarnings("unchecked")
    void moreThanTwoRefsTakesFirstTwoOnly() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"ref.png\"}");
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
                .thenReturn(upload, upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci5");
        when(storage.getAccessUrl("ci5")).thenReturn("/images/ci5.png");

        newGen(http, storage, writeTemplates(), 60, 1).generateWithReferences("p", null, 1280, 720,
                java.util.List.of("http://127.0.0.1:8080/r1.png", "http://127.0.0.1:8080/r2.png",
                        "http://127.0.0.1:8080/r3.png"));
        // spec §6.1「超 2 跳过」：只取前 2 张——第 3 张连回源都不应该发生
        var paths = sendsByPath(http);
        assertEquals(1L, paths.getOrDefault("/r1.png", 0L));
        assertEquals(1L, paths.getOrDefault("/r2.png", 0L));
        assertFalse(paths.containsKey("/r3.png"), () -> "第 3 张不应被取用: " + paths);
        assertEquals(2L, paths.getOrDefault("/upload/image", 0L));
    }

    /**
     * 组合模板缺失 → 必须降级纯 LoRA。两种缺失形态都跑：配置项为空，以及
     * 「已配非空路径但文件不在盘上」（= @Value 默认值 + Task 9 尚未校准的生产现状）。
     */
    @Test
    void combinedDegradesToLoraWhenCombinedTemplateMissing() throws Exception {
        String[] p = writeTemplates().split("\\|");
        // 模板选择层：六参委托（无 refs）不得命中 ipadapter
        ComfyUiImageGenerator chooser = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", p[0], p[1], p[2], 8080, 60, 1, p[3], "", 0.7);
        var wf = chooser.buildWorkflow("p", "n", 1280, 720, "saki.safetensors", null);
        assertFalse(types(wf).contains("IPAdapter"));
        assertTrue(types(wf).contains("LoraLoader"));

        assertDegradesToPlainLora(p, "");
        assertDegradesToPlainLora(p, temp.resolve("absent_ipadapter_lora.json").toString());
    }

    /**
     * 行为层判据：降级后参考图根本不上传（只发生 submit+history+view 三个往返）。
     * 删掉降级分支或删掉 Files.exists 守卫都会红。
     */
    @SuppressWarnings("unchecked")
    private void assertDegradesToPlainLora(String[] p, String combinedTemplatePath) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"prompt_id\":\"p1\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"p1\":{\"status\":{\"completed\":true},"
                + "\"outputs\":{\"7\":{\"images\":[{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submit, history);
        HttpResponse<byte[]> view = mock(HttpResponse.class);
        when(view.statusCode()).thenReturn(200); when(view.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(view);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci4");
        when(storage.getAccessUrl("ci4")).thenReturn("/images/ci4.png");

        ComfyUiImageGenerator g = new ComfyUiImageGenerator(storage, om, http, "http://127.0.0.1:8188",
                p[0], p[1], p[2], 8080, 60, 1, p[3], combinedTemplatePath, 0.7);
        GeneratedImage img = g.generateWithLoraAndReferences("p", null, 1280, 720,
                "saki.safetensors", null, java.util.List.of("data:image/png;base64,AQ=="));
        assertEquals("/images/ci4.png", img.getImageUrl(), "组合模板路径=" + combinedTemplatePath);
        var paths = sendsByPath(http);
        assertFalse(paths.containsKey("/upload/image"),
                () -> "降级后不应上传参考图: " + combinedTemplatePath + " -> " + paths);
        assertEquals(1L, paths.getOrDefault("/prompt", 0L));
        assertEquals(1L, paths.getOrDefault("/history/p1", 0L));
        assertEquals(1L, paths.getOrDefault("/view", 0L));
        // submit + history + view，没有第 4 个往返
        assertEquals(3L, paths.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test void fixedSeedReachesWorkflowWhileDefaultPathStillRandomizes() throws Exception {
        ComfyUiImageGenerator g = newGen(mock(HttpClient.class), mock(StorageService.class), writeTemplates(), 60, 1);
        // 九参版：同 seed 对照 seam（spec §6.3）必须把调用方给的 seed 原值写进 KSampler
        var fixed = g.buildWorkflow("p", "n", 1280, 720, null, null, java.util.List.of("r1.png"), 0.7, 20261118L);
        assertEquals(20261118L, fixed.at("/5/inputs/seed").asLong());
        assertTrue(fixed.at("/5/inputs/seed").isNumber());
        // 八参版仍重掷 seed：对照 seam 不得把生产路径钉死（同 seed 只服务于冒烟）
        var rnd = g.buildWorkflow("p", "n", 1280, 720, null, null, java.util.List.of("r1.png"), 0.7);
        assertNotEquals(20261118L, rnd.at("/5/inputs/seed").asLong());
    }

    /**
     * 转发层守护：generateWithSeed 必须把 seed 一路送到九参 buildWorkflow。
     * 若它误调七参 doGenerate（丢掉 seed），上面的装配层用例仍全绿，只有 -Dipadapter=true 真跑才能发现；
     * 这里用同包子类拦下九参重载直接取证。
     */
    @Test @SuppressWarnings("unchecked")
    void generateWithSeedForwardsSeedDownToWorkflow() throws Exception {
        String[] p = writeTemplates().split("\\|");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> upload = mock(HttpResponse.class);
        when(upload.statusCode()).thenReturn(200);
        when(upload.body()).thenReturn("{\"name\":\"ref.png\"}");
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
                .thenReturn(upload, upload, submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("ci6");
        when(storage.getAccessUrl("ci6")).thenReturn("/images/ci6.png");

        long[] seen = new long[1];
        ComfyUiImageGenerator spy = new ComfyUiImageGenerator(storage, om, http, "http://127.0.0.1:8188",
                p[0], p[1], p[2], 8080, 60, 1, p[3], p[4], 0.7) {
            @Override
            ObjectNode buildWorkflow(String prompt, String negativePrompt, int width, int height,
                                     String loraName, String uploadedCnName,
                                     java.util.List<String> uploadedRefNames, double ipWeight, long seed) {
                seen[0] = seed;
                return super.buildWorkflow(prompt, negativePrompt, width, height, loraName, uploadedCnName,
                        uploadedRefNames, ipWeight, seed);
            }
        };
        spy.generateWithSeed("p", null, 1280, 720, java.util.List.of("data:image/png;base64,AQ=="), 20261118L);
        assertEquals(20261118L, seen[0], "generateWithSeed 必须把 seed 递到九参 buildWorkflow");

        // 对照组形态（refs=null）同样递送 seed，且根本不上传参考图
        // 同一 http mock 的响应序列会被第一次调用消完，此处重打桩（无上传 → submit+history 两个）。
        // 重打顺序必须「先通用后专用」：Mockito 取最后匹配的桩，而 any(BodyHandler.class) 也能匹配
        // ofByteArray()，通用桩若声明在最后会抢走 /view 下载 → ClassCast String→[B（Task 8 同款坑）
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submit, history);
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray()))).thenReturn(bytes);
        seen[0] = -1L;
        spy.generateWithSeed("p", null, 1280, 720, null, 20261118L);
        assertEquals(20261118L, seen[0]);
        assertEquals(2L, sendsByPath(http).getOrDefault("/upload/image", 0L),
                "refs=null 的对照组不应产生任何参考图上传（仍为第一次的 2 次）");
    }

    @Test void repoTemplatesCalibrated() throws Exception {
        // 锁定手册 §6 配方：22 步 CFG7 euler/normal；canny 0.4/0.8；SD1.5 用裸 VAEDecode（tiled 强制仅 Wan）
        ComfyUiImageGenerator g = new ComfyUiImageGenerator(mock(StorageService.class), om, mock(HttpClient.class),
                "http://127.0.0.1:8188", "docs/comfyui/sd15_storyboard.template.json",
                "docs/comfyui/sd15_lora_storyboard.template.json", "docs/comfyui/sd15_lora_canny.template.json",
                8080, 60, 1, "", "", 0.7);
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