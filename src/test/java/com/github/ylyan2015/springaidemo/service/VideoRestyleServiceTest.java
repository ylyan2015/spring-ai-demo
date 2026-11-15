package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoRestyleServiceTest {

    private RestyleTaskRepository taskRepo;
    private StorageService storage;
    private HttpClient http;
    private VideoRestyleService service;
    private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(8);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        taskRepo = mock(RestyleTaskRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        storage = mock(StorageService.class);
        http = mock(HttpClient.class);
        service = new VideoRestyleService(taskRepo, userRepo, storage, new ObjectMapper(), http,
                "https://dashscope.aliyuncs.com", "sk-test", "wan2.7-videoedit", 600, 10);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(storage.upload(any(), anyString(), anyString())).thenReturn("src1");
        when(storage.getAccessUrl("src1")).thenReturn("http://pub/src1.mp4");
        when(taskRepo.save(any())).thenAnswer(inv -> {
            RestyleTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId(idSeq.incrementAndGet());
            return t;
        });
        HttpResponse<String> submit = mock(HttpResponse.class);
        when(submit.statusCode()).thenReturn(200);
        when(submit.body()).thenReturn("{\"output\":{\"task_id\":\"t1\"}}");
        HttpResponse<String> poll = mock(HttpResponse.class);
        when(poll.statusCode()).thenReturn(200);
        when(poll.body()).thenReturn("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"http://v/out.mp4\"}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(submit, poll, submit, poll);
        HttpResponse<byte[]> dl = mock(HttpResponse.class);
        when(dl.statusCode()).thenReturn(200);
        when(dl.body()).thenReturn(new byte[]{1});
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn((HttpResponse) dl);
        when(storage.upload(any(), eq("restyle_out_9.mp4"), anyString())).thenReturn("out1");
        when(storage.getAccessUrl("out1")).thenReturn("/images/out1.mp4");
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void submitRunsAsyncToSucceeded() throws Exception {
        Map<String, Object> out = service.submit(new ByteArrayInputStream(new byte[]{1}),
                "a.mp4", "video/mp4", "anime style, cel shading");
        assertEquals(9L, out.get("id"));
        RestyleTask last = service.awaitAndGetForTest(9L);
        assertEquals("SUCCEEDED", last.getStatus());
        assertEquals("/images/out1.mp4", last.getResultUrl());
    }

    @Test
    void nonVideoContentTypeRejected() {
        assertThrows(RuntimeException.class, () -> service.submit(
                new ByteArrayInputStream(new byte[]{1}), "a.png", "image/png", "anime"));
    }

    @Test
    void listMineRequiresLogin() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.listMine());
    }

    @Test
    void doneFuturesEvictedOnNewSubmit() throws Exception {
        // 审查修复回归：已终态 future 条目在新提交时清理，防内存无限增长
        service.submit(new ByteArrayInputStream(new byte[]{1}), "a.mp4", "video/mp4", "anime");
        service.awaitAndGetForTest(9L);
        service.submit(new ByteArrayInputStream(new byte[]{1}), "b.mp4", "video/mp4", "anime");
        Map<?, ?> futures = (Map<?, ?>) ReflectionTestUtils.getField(service, "futures");
        assertEquals(1, futures.size());
        assertEquals("SUCCEEDED", service.awaitAndGetForTest(10L).getStatus());
    }
}