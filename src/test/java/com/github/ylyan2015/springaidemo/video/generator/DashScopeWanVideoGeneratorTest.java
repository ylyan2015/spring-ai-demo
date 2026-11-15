package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashScopeWanVideoGeneratorTest {

    private HttpClient http;
    private StorageService storage;
    private DashScopeWanVideoGenerator gen;

    @BeforeEach
    void setUp() throws Exception {
        http = mock(HttpClient.class);
        storage = mock(StorageService.class);
        gen = new DashScopeWanVideoGenerator(storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com/api/v1", "sk-test", "wan2.7-i2v", "720P", 600, 10);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("fv1");
        when(storage.getAccessUrl("fv1")).thenReturn("/images/fv1.mp4");
    }

    @SuppressWarnings("unchecked")
    private void stubHttp(String submitBody, String pollBody) throws Exception {
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn(submitBody);
        HttpResponse<String> poll = mock(HttpResponse.class);
        when(poll.statusCode()).thenReturn(200);
        when(poll.body()).thenReturn(pollBody);
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{1, 2, 3});
        doReturn(submit).when(http).send(any(), any());
        doReturn(poll).when(http).send(argThat(r -> r != null && r.uri().toString().contains("/tasks/")), any());
        doReturn(dl).when(http).send(any(), eq(HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static String bodyOf(HttpRequest request) {
        StringBuilder sb = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { sb.append(StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onComplete() { }
        });
        return sb.toString();
    }

    @Test
    void submitPollDownloadTransferWithFirstLastFrame() throws Exception {
        stubHttp("{\"output\":{\"task_id\":\"t1\",\"task_status\":\"PENDING\"}}",
                "{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"https://tmp/out.mp4\"}}");
        GeneratedVideo out = gen.generate(new VideoGenerationRequest(
                "girl walks, subtle motion", "https://x/first.png", "https://x/last.png", 5, "720P"));
        assertEquals("/images/fv1.mp4", out.getVideoUrl());
        assertEquals("dashscope-wan", out.getGeneratorType());

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(cap.capture(), any());
        String body = bodyOf(cap.getAllValues().get(0));
        assertTrue(body.contains("\"type\":\"first_frame\""), body);
        assertTrue(body.contains("\"type\":\"last_frame\""), body);
        assertTrue(body.contains("\"resolution\":\"720P\""));
        assertTrue(body.contains("\"duration\":5"));
        assertTrue(cap.getAllValues().get(0).headers().firstValue("X-DashScope-Async").orElse("").equals("enable"));
    }

    @Test
    void wan26UsesImgUrlAndIgnoresLastFrame() {
        DashScopeWanVideoGenerator wan26 = new DashScopeWanVideoGenerator(storage, new ObjectMapper(), http,
                "https://x", "sk", "wan2.6-i2v", "720P", 600, 10);
        String body = wan26.buildSubmitBody(new VideoGenerationRequest(
                "p", "https://x/first.png", "https://x/last.png", 5, "720P"), 5).toString();
        assertTrue(body.contains("\"img_url\":\"https://x/first.png\""));
        assertFalse(body.contains("last_frame"));
    }

    @Test
    void failedTaskThrows() throws Exception {
        stubHttp("{\"output\":{\"task_id\":\"t1\"}}",
                "{\"output\":{\"task_status\":\"FAILED\",\"message\":\"boom\"}}");
        assertThrows(RuntimeException.class, () -> gen.generate(new VideoGenerationRequest(
                "p", "https://x/f.png", null, 5, "720P")));
    }

    @Test
    void timeoutThrows() throws Exception {
        DashScopeWanVideoGenerator shortTimeout = new DashScopeWanVideoGenerator(storage, new ObjectMapper(), http,
                "https://x", "sk", "wan2.7-i2v", "720P", 0, 10);
        stubHttp("{\"output\":{\"task_id\":\"t1\"}}",
                "{\"output\":{\"task_status\":\"RUNNING\"}}");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> shortTimeout.generate(
                new VideoGenerationRequest("p", "https://x/f.png", null, 5, "720P")));
        assertTrue(ex.getMessage().contains("超时"));
    }

    @Test
    void durationClamped() {
        String body = gen.buildSubmitBody(new VideoGenerationRequest(
                "p", "https://x/f.png", null, 99, "720P"), Math.max(2, Math.min(15, 99))).toString();
        assertTrue(body.contains("\"duration\":15"));
    }
}