package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.repository.VideoExportRepository;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import com.github.ylyan2015.springaidemo.video.subtitle.SubtitleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 异步导出测试（Phase 5 Track B Task 6）
 * 复用转绘 idiom：提交即返 jobId/RUNNING → await 得 SUCCESS 结果 map；futures 终态清理；jobStatus 终态
 */
class VideoExportAsyncTest {

    private VideoExportService spy;

    @BeforeEach void setUp() {
        UserRepository userRepo = mock(UserRepository.class);
        User u = new User(); u.setId(1L); u.setUsername("async-user");
        when(userRepo.findByUsername("async-user")).thenReturn(Optional.of(u));
        VideoExportService real = new VideoExportService(mock(StoryScriptRepository.class),
                mock(StoryShotRepository.class), mock(VideoExportRepository.class), userRepo,
                mock(VideoComposeService.class), mock(BgmService.class), mock(SubtitleService.class),
                mock(StoryCharacterRepository.class), mock(SfxService.class), mock(PublishPackService.class));
        spy = spy(real);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("async-user", null, List.of()));
    }

    @AfterEach void tearDown() { SecurityContextHolder.clearContext(); spy.shutdown(); }

    private void stubExport(Map<String, Object> result) {
        doReturn(result).when(spy).exportHdWithPost(any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test void asyncSubmitAwaitReturnsSuccess() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoUrl", "/x.mp4");
        result.put("status", "SUCCESS");
        stubExport(result);
        Map<String, Object> job = spy.exportHdAsync(1L, "720p", "16:9", null, true, false, "zh",
                VideoExportService.PostFlags.NONE);
        assertEquals("RUNNING", job.get("status"));
        long jobId = ((Number) job.get("jobId")).longValue();
        Map<String, Object> got = spy.awaitAndGetForTest(jobId);
        assertEquals("/x.mp4", got.get("videoUrl"));
    }

    @Test void doneFuturesEvictedOnNewSubmit() throws Exception {
        stubExport(new LinkedHashMap<>(Map.of("status", "SUCCESS")));
        Map<String, Object> j1 = spy.exportHdAsync(1L, "720p", "16:9", null, true, false, "zh",
                VideoExportService.PostFlags.NONE);
        spy.awaitAndGetForTest(((Number) j1.get("jobId")).longValue());
        Map<String, Object> j2 = spy.exportHdAsync(2L, "720p", "16:9", null, true, false, "zh",
                VideoExportService.PostFlags.NONE);
        Map<?, ?> futures = (Map<?, ?>) ReflectionTestUtils.getField(spy, "futures");
        assertEquals(1, futures.size());
        spy.awaitAndGetForTest(((Number) j2.get("jobId")).longValue());
    }

    @Test void jobStatusSuccessAfterDone() throws Exception {
        stubExport(new LinkedHashMap<>(Map.of("videoUrl", "/y.mp4")));
        Map<String, Object> job = spy.exportHdAsync(1L, "720p", "16:9", null, true, false, "zh",
                VideoExportService.PostFlags.NONE);
        long jobId = ((Number) job.get("jobId")).longValue();
        spy.awaitAndGetForTest(jobId);
        Map<String, Object> st = spy.jobStatus(jobId);
        assertEquals("SUCCESS", st.get("status"));
    }

    @Test void jobStatusFailedCarriesMessage() throws Exception {
        doThrow(new RuntimeException("引用占比超限")).when(spy).exportHdWithPost(any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
        Map<String, Object> job = spy.exportHdAsync(1L, "720p", "16:9", null, true, false, "zh",
                VideoExportService.PostFlags.NONE);
        long jobId = ((Number) job.get("jobId")).longValue();
        // 等待终态（异常被 worker 捕获，future 正常完成为 FAILED 结果）
        spy.awaitAndGetForTest(jobId);
        Map<String, Object> st = spy.jobStatus(jobId);
        assertEquals("FAILED", st.get("status"));
        assertTrue(String.valueOf(st.get("message")).contains("引用占比超限"), String.valueOf(st));
    }
}