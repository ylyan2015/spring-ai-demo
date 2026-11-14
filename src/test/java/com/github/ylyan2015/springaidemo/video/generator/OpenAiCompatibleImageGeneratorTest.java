package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.ylyan2015.springaidemo.exception.ImageGenerationException;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 通用 OpenAI 兼容图像生成器测试（智谱/硅基流动/OpenAI/火山方舟共用实现）：
 * 负面词拼接进 prompt（协议无 negative 参数）、尺寸映射、url/b64_json 双响应格式、
 * 转存 StorageService、错误传播与 Key fail-fast。
 */
class OpenAiCompatibleImageGeneratorTest {

    private StorageService storageService;
    private HttpClient httpClient;
    private OpenAiCompatibleImageGenerator generator;

    @BeforeEach
    void setUp() {
        storageService = mock(StorageService.class);
        httpClient = mock(HttpClient.class);
        generator = new OpenAiCompatibleImageGenerator(
                "zhipu", "智谱 CogView", "https://open.bigmodel.cn/api/paas/v4",
                "sk-test", "cogview-3-flash",
                List.of("1024x1024", "1344x768", "768x1344"),
                storageService, httpClient);
        when(storageService.upload(any(InputStream.class), anyString(), anyString())).thenReturn("file-1");
        when(storageService.getAccessUrl("file-1")).thenReturn("/images/2026/11/14/file-1.png");
    }

    @SuppressWarnings("unchecked")
    private void stubApiThenDownload(String apiBody, byte[] downloaded) throws Exception {
        HttpResponse<String> apiResp = mock(HttpResponse.class);
        when(apiResp.statusCode()).thenReturn(200);
        when(apiResp.body()).thenReturn(apiBody);
        doReturn(apiResp).when(httpClient).send(any(), any());
        if (downloaded != null) {
            HttpResponse<byte[]> dlResp = mock(HttpResponse.class);
            when(dlResp.statusCode()).thenReturn(200);
            when(dlResp.body()).thenReturn(downloaded);
            doReturn(apiResp).doReturn(dlResp).when(httpClient).send(any(), any());
        }
    }

    @Test
    void negativePromptIsAppendedToPromptAndSizeMapped() {
        ObjectNode body = generator.buildRequestBody("a cat", "blurry, low quality", "1344x768");

        assertEquals("cogview-3-flash", body.get("model").asText());
        assertTrue(body.get("prompt").asText().startsWith("a cat"));
        assertTrue(body.get("prompt").asText().contains("blurry, low quality"),
                "OpenAI images 协议无 negative 参数，负面词应拼接进 prompt");
        assertEquals("1344x768", body.get("size").asText(), "固定尺寸提供者应按比例映射");
        assertEquals(1, body.get("n").asInt());
    }

    @Test
    void blankNegativePromptLeavesPromptUntouched() {
        ObjectNode body = generator.buildRequestBody("a cat", "  ", "1024x1024");
        assertEquals("a cat", body.get("prompt").asText());
    }

    @Test
    void mappedSizeIsReturnedAsActualDimensions() throws Exception {
        stubApiThenDownload("{\"data\":[{\"url\":\"https://tmp.example/img.png\"}]}", new byte[]{1});

        GeneratedImage image = generator.generate("a cat", null, 1280, 720);

        // 审查 minor 修复：返回映射后实际生成尺寸（1344x768），而非请求尺寸 1280x720
        assertEquals(1344, image.getWidth());
        assertEquals(768, image.getHeight());
    }

    @Test
    void nullOrBlankPromptThrows() {
        assertThrows(ImageGenerationException.class, () -> generator.generate(null, null, 1024, 1024));
        assertThrows(ImageGenerationException.class, () -> generator.generate("  ", null, 1024, 1024));
    }

    @Test
    void invalidBase64IsWrapped() throws Exception {
        stubApiThenDownload("{\"data\":[{\"b64_json\":\"!!!not-base64!!!\"}]}", null);

        ImageGenerationException e = assertThrows(ImageGenerationException.class,
                () -> generator.generate("a cat", null, 1024, 1024));
        assertTrue(e.getMessage().contains("base64"), "b64 解码失败应包装为 ImageGenerationException: " + e.getMessage());
    }

    @Test
    void downloadNon200Throws() throws Exception {
        HttpResponse<String> apiResp = mock(HttpResponse.class);
        when(apiResp.statusCode()).thenReturn(200);
        when(apiResp.body()).thenReturn("{\"data\":[{\"url\":\"https://tmp.example/img.png\"}]}");
        HttpResponse<byte[]> dlResp = mock(HttpResponse.class);
        when(dlResp.statusCode()).thenReturn(404);
        doReturn(apiResp).doReturn(dlResp).when(httpClient).send(any(), any());

        ImageGenerationException e = assertThrows(ImageGenerationException.class,
                () -> generator.generate("a cat", null, 1024, 1024));
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    void urlResponseIsDownloadedAndStored() throws Exception {
        stubApiThenDownload("{\"data\":[{\"url\":\"https://tmp.example/img.png\"}]}", new byte[]{1, 2, 3});

        GeneratedImage image = generator.generate("a cat", null, 1280, 720);

        assertEquals("/images/2026/11/14/file-1.png", image.getImageUrl());
        assertEquals("file-1", image.getFileId());
        assertEquals("zhipu", image.getGeneratorType());
        verify(storageService).upload(any(InputStream.class), anyString(), eq("image/png"));
    }

    @Test
    void b64JsonResponseIsDecodedAndStored() throws Exception {
        byte[] png = new byte[]{9, 8, 7};
        String b64 = Base64.getEncoder().encodeToString(png);
        stubApiThenDownload("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}", null);

        GeneratedImage image = generator.generate("a cat", null, 1024, 1024);

        assertEquals("file-1", image.getFileId());
        // 仅一次 HTTP 调用（无下载），b64 直接解码转存
        verify(httpClient, times(1)).send(any(), any());
        verify(storageService).upload(any(InputStream.class), anyString(), eq("image/png"));
    }

    @Test
    void non200ResponseThrowsWithProviderName() throws Exception {
        HttpResponse<String> apiResp = mock(HttpResponse.class);
        when(apiResp.statusCode()).thenReturn(401);
        when(apiResp.body()).thenReturn("{\"error\":\"invalid key\"}");
        doReturn(apiResp).when(httpClient).send(any(), any());

        ImageGenerationException e = assertThrows(ImageGenerationException.class,
                () -> generator.generate("a cat", null, 1024, 1024));
        assertTrue(e.getMessage().contains("智谱 CogView"), "错误应含提供者名: " + e.getMessage());
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void emptyDataThrows() throws Exception {
        stubApiThenDownload("{\"data\":[]}", null);
        assertThrows(ImageGenerationException.class, () -> generator.generate("a cat", null, 1024, 1024));
    }

    @Test
    void missingApiKeyFailsFastAndUnavailable() {
        OpenAiCompatibleImageGenerator noKey = new OpenAiCompatibleImageGenerator(
                "zhipu", "智谱 CogView", "https://open.bigmodel.cn/api/paas/v4",
                "", "cogview-3-flash", List.of(), storageService, httpClient);

        assertFalse(noKey.isAvailable());
        assertThrows(ImageGenerationException.class, () -> noKey.generate("a cat", null, 1024, 1024));
        assertTrue(generator.isAvailable());
    }
}