package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.VideoExport;
import com.github.ylyan2015.springaidemo.service.VideoExportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 批量打包下载的 HTTP 边界（T9 端到端核验抓到的存量缺陷）。
 *
 * <p>事故：方法签名写成 {@code ResponseEntity<?>}，Spring MVC 的
 * {@code StreamingResponseBodyReturnValueHandler} 靠<b>声明的泛型实参</b>判定返回类型，
 * 通配符擦成 Object 后它不认领，请求落到普通消息转换器 → 分发阶段
 * {@code HttpMessageNotWritableException: No converter for [class ...$$Lambda] with preset
 * Content-Type 'application/zip'} → 500。工作台的「打包下载全部」按钮自 2026-11-14 起就没通过，
 * 而 service 层单测（VideoExportServiceZipTest）直接调 {@code writeBatchZip}，看不见这一层。
 *
 * <p>所以三条例子都必须走真实 MVC 分发，而不是直接调 controller 方法。
 */
class VideoExportBatchZipControllerTest {

    private final VideoExportService service = mock(VideoExportService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VideoExportController(service)).build();

    private static VideoExport record(long id) {
        VideoExport r = new VideoExport();
        r.setId(id);
        r.setScriptId(7L);
        r.setUserId(1L);
        r.setResolution("1080p");
        r.setAspectRatio("16:9");
        r.setStatus("SUCCESS");
        r.setVideoUrl("/images/2026-11-19/video_" + id + ".mp4");
        return r;
    }

    /** 分发 +（若启用异步）一次 asyncDispatch，拿到最终响应 */
    private MvcResult download(String json) throws Exception {
        MvcResult first = mvc.perform(post("/api/video/export/batch-download")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(first)).andReturn();
        }
        return first;
    }

    @Test
    void batchDownloadServesZipBytesThroughRealMvcDispatch() throws Exception {
        when(service.prepareBatchZip(List.of(12L))).thenReturn(List.of(record(12L)));
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write(new byte[]{'P', 'K', 3, 4, 'x', 'y'});
            return null;
        }).when(service).writeBatchZip(anyList(), any(OutputStream.class));

        MvcResult r = download("{\"exportIds\":[12]}");

        assertEquals(HttpStatus.OK.value(), r.getResponse().getStatus(),
                "500 即为原缺陷：响应体不是 ZIP，而是分发阶段的消息转换器异常");
        String disposition = r.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.startsWith("attachment; filename=video_exports_"),
                "Content-Disposition: " + disposition);
        assertArrayEquals(new byte[]{'P', 'K', 3, 4, 'x', 'y'},
                r.getResponse().getContentAsByteArray(),
                "ZIP 字节必须由 StreamingResponseBody 真的写进响应体");
    }

    @Test
    void batchDownloadContentTypeIsZipNotInferredFromBody() throws Exception {
        when(service.prepareBatchZip(List.of(12L))).thenReturn(List.of(record(12L)));
        doAnswer(inv -> {
            ((OutputStream) inv.getArgument(1)).write(new byte[]{'P', 'K', 3, 4});
            return null;
        }).when(service).writeBatchZip(anyList(), any(OutputStream.class));

        MvcResult r = download("{\"exportIds\":[12]}");
        assertEquals("application/zip", r.getResponse().getContentType(),
                "浏览器按 content-type 决定是存文件还是当页面渲染");
    }

    @Test
    void validationFailureStillReachesTheClientAsReadableJson() throws Exception {
        when(service.prepareBatchZip(List.of())).thenThrow(new RuntimeException("请至少选择一条导出记录"));

        MvcResult r = download("{\"exportIds\":[]}");

        assertEquals(HttpStatus.BAD_REQUEST.value(), r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 前端 downloadBatchZip 走 err.message 分支弹 toast：响应体没有 message 就等于只报 HTTP 500
        assertTrue(body.contains("请至少选择一条导出记录"), body);
        assertTrue(String.valueOf(r.getResponse().getContentType()).contains("application/json"),
                "错误响应不得继续自称 zip: " + r.getResponse().getContentType());
    }

    /**
     * 结构钉：把签名改回 {@code ResponseEntity<?>} 只会让这条红，其余例子在某些 MVC 配置下
     * 可能被兜底转换器蒙过去——泛型实参是这个端点唯一可靠的契约面。
     */
    @Test
    void batchDownloadDeclaresStreamingResponseBodyAsBodyType() throws Exception {
        Method m = VideoExportController.class.getDeclaredMethod("batchDownload", java.util.Map.class);
        ParameterizedType type = (ParameterizedType) m.getGenericReturnType();
        assertEquals(StreamingResponseBody.class, type.getActualTypeArguments()[0],
                "ResponseEntity 的泛型实参必须是 StreamingResponseBody，"
                        + "写 ? 或 Object 会让 Spring MVC 不认领该返回值处理器，整端点 500");
    }
}
