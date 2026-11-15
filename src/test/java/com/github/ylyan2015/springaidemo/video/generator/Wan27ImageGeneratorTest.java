package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Wan27ImageGeneratorTest {

    private HttpClient http;
    private StorageService storage;
    private Wan27ImageGenerator gen;

    @BeforeEach
    void setUp() throws Exception {
        http = mock(HttpClient.class);
        storage = mock(StorageService.class);
        gen = new Wan27ImageGenerator(storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com/api/v1", "sk-test", "wan2.7-image", 60);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("fid1");
        when(storage.getAccessUrl("fid1")).thenReturn("/images/fid1.png");
    }

    @SuppressWarnings("unchecked")
    private void stubHttp(String submitBody, int submitStatus) throws Exception {
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(submitStatus);
        when(submit.body()).thenReturn(submitBody);
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{1, 2, 3});
        doReturn(submit).when(http).send(any(), any());
        doReturn(dl).when(http).send(any(), eq(HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static String bodyOf(HttpRequest request) {
        StringBuilder sb = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { sb.append(StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) { throw new RuntimeException(throwable); }
            @Override public void onComplete() { }
        });
        return sb.toString();
    }

    @Test
    void referencesSerializedAndTruncatedToNine() throws Exception {
        stubHttp("{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://tmp/out.png\"}]}}]}}", 200);
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            refs.add("data:image/png;base64,REF" + i);
        }
        generatedImageWithRefs(refs);

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), any());
        String body = bodyOf(cap.getAllValues().get(0));
        assertTrue(body.contains("\"size\":\"1280*720\""), body);
        for (int i = 0; i < 9; i++) {
            assertTrue(body.contains("REF" + i), "ref " + i + " missing");
        }
        assertFalse(body.contains("REF9"), "第 10 张参考图应被截断");
    }

    @Test
    void resultsFormFallbackParsed() throws Exception {
        stubHttp("{\"output\":{\"results\":[{\"url\":\"https://tmp/out.png\"}]}}", 200);
        GeneratedImage img = generatedImageWithRefs(List.of());
        assertEquals("/images/fid1.png", img.getImageUrl());
        assertEquals("wan27", img.getGeneratorType());
    }

    @Test
    void httpErrorThrows() throws Exception {
        stubHttp("{\"code\":\"InvalidParameter\"}", 400);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> generatedImageWithRefs(List.of()));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void emptyKeyUnavailable() {
        Wan27ImageGenerator noKey = new Wan27ImageGenerator(storage, new ObjectMapper(), http,
                "https://x", "", "wan2.7-image", 60);
        assertFalse(noKey.isAvailable());
        assertThrows(RuntimeException.class, () -> noKey.generate("p", null, 64, 64));
    }

    private GeneratedImage generatedImageWithRefs(List<String> refs) {
        return gen.generateWithReferences("a girl", null, 1280, 720, refs);
    }
}