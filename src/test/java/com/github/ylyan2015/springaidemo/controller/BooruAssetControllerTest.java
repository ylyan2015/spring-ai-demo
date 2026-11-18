package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.service.BooruSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 11 复审 S-15 遗留的 HTTP 边界：那 24 例是 service 单测，看不见 controller 的参数绑定与状态码分流。
 * standaloneSetup 足够——三端点的正确性全在这一层自身（SecurityConfig 对 /api/video/** 放行，已由
 * SecurityConfigIntegrationTest 覆盖），无需整上下文启动。
 */
class BooruAssetControllerTest {

    private final BooruSearchService service = mock(BooruSearchService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BooruAssetController(service)).build();

    /** 4 字节头 + 补齐到 12 字节：嗅探只看魔数前缀，但短于 12 字节的响应必须走兜底分支 */
    private static byte[] png() {
        return tail(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});
    }

    private static byte[] jpeg() {
        return tail(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    private static byte[] webp() {
        return tail(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
    }

    /** GIF87a/GIF89a 共用前缀 GIF8（审查 W-3：两源都有大量动图帖） */
    private static byte[] gif() {
        return tail(new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0});
    }

    private static byte[] tail(byte[] b) {
        byte[] out = new byte[b.length + 8];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    // ==================== 入参收口：客户端错误不得被当成服务端故障 ====================

    @Test
    void ingestWithoutPostIdIs400AndNeverTouchesTheService() throws Exception {
        MvcResult r = mvc.perform(post("/api/video/assets/booru/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"safebooru\",\"category\":\"CHARACTER\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":false"), body);
        // 原文可回前端（RejectException 分支）：这条是「可行动」提示，不是 500 的固定文案
        assertTrue(body.contains("postId"), body);
        verifyNoInteractions(service);
    }

    @Test
    void ingestWithGarbagePostIdIs400WithBoundedEcho() throws Exception {
        // 4000 字符而不是 400：变异复证 MUT-H 发现短载荷下「截断」根本不成判据（整串回显也不到 2000 字节）
        String garbage = "12abc" + "x".repeat(4000);
        MvcResult r = mvc.perform(post("/api/video/assets/booru/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"safebooru\",\"postId\":\"" + garbage + "\",\"category\":\"CHARACTER\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("postId"), body);
        // 回显远端可控串必须截断：否则一次请求就能把响应体撑到任意长（400 页反射式放大）
        assertFalse(body.contains("x".repeat(64)), "超长入参被整串回显进 400 响应体: " + body.length());
        assertTrue(body.length() < 2000, "错误响应被超长入参撑大: " + body.length());
        verifyNoInteractions(service);
    }

    /** 非 String 的 category/name 不得走 ClassCastException → 500；一律字符串化后交服务层判 value */
    @Test
    void ingestWithStringifiedNonStringFieldsReachesServiceAsStrings() throws Exception {
        MediaAsset asset = new MediaAsset();
        asset.setId(7L);
        when(service.ingest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(asset);
        mvc.perform(post("/api/video/assets/booru/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        // postId 数字与字符串两种形态都收（前端 JSON 直接放数字）
                        .content("{\"provider\":\"safebooru\",\"postId\":456,\"category\":\"SCENE\",\"name\":789}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(service).ingest(eq("safebooru"), eq(456L), eq("SCENE"), name.capture(), isNull());
        assertEquals("789", name.getValue());
    }

    @Test
    void previewWithNonNumericPostIdIs400FromBinding() throws Exception {
        mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "abc"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void previewWithoutProviderIs400EmptyBody() throws Exception {
        MvcResult r = mvc.perform(get("/api/video/assets/booru/preview").param("postId", "1"))
                .andExpect(status().isBadRequest()).andReturn();
        // Spring 的参数缺失错误：空体。前端按「图片加载失败」渲染，不依赖这里的 message
        assertEquals("", r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        verifyNoInteractions(service);
    }

    // ==================== 400/500 分流的 HTTP 侧证据 ====================

    @Test
    void strategyRejectionKeepsOriginalMessageWhileInternalFailureStaysGeneric() throws Exception {
        when(service.search(anyString(), any(), anyInt()))
                .thenThrow(new BooruSearchService.RejectException("未知 booru 源: gelbooru"))
                .thenThrow(new RuntimeException("could not execute statement [ERROR: column \"source_note\" of relation \"media_asset\"]"));
        MvcResult rejected = mvc.perform(get("/api/video/assets/booru/search").param("q", "x").param("provider", "gelbooru"))
                .andExpect(status().isBadRequest()).andReturn();
        assertTrue(rejected.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("gelbooru"));

        MvcResult failed = mvc.perform(get("/api/video/assets/booru/search").param("q", "x"))
                .andExpect(status().isInternalServerError()).andReturn();
        String body = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 内部故障原文（含 SQL 与列名）绝不出现在响应里
        assertFalse(body.contains("source_note"), body);
        assertFalse(body.contains("column"), body);
        assertTrue(body.contains("素材取用失败"), body);
    }

    // ==================== preview 的 Content-Type 嗅探（Task 11 记账：原先硬编码 image/jpeg） ====================

    @Test
    void previewContentTypeComesFromMagicBytes() throws Exception {
        when(service.preview("safebooru", 1L)).thenReturn(png());
        when(service.preview("safebooru", 2L)).thenReturn(jpeg());
        when(service.preview("safebooru", 3L)).thenReturn(webp());
        when(service.preview("safebooru", 4L)).thenReturn(new byte[]{1, 2, 3});
        when(service.preview("safebooru", 5L)).thenReturn(gif());

        assertEquals(MediaType.IMAGE_PNG.toString(),
                mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentType());
        assertEquals(MediaType.IMAGE_JPEG.toString(),
                mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "2"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentType());
        assertEquals("image/webp",
                mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "3"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentType());
        // GIF 必须单独认：改嗅探之前 JPEG 兜底至少能让浏览器自己猜出 GIF，改完直接给 octet-stream 就是功能回退
        assertEquals("image/gif",
                mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "5"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentType());
        // 认不出的字节：不得继续谎报 image/jpeg（浏览器会按 JPEG 解码出破图），改八位字节流让前端走加载失败分支
        assertEquals(MediaType.APPLICATION_OCTET_STREAM.toString(),
                mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "4"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentType());
    }

    @Test
    void previewRejectionIs400WithEmptyBodyAndSearchKeepsListShape() throws Exception {
        when(service.preview("safebooru", 9L)).thenThrow(new BooruSearchService.RejectException("URL 不在白名单或非 https"));
        mvc.perform(get("/api/video/assets/booru/preview").param("provider", "safebooru").param("postId", "9"))
                .andExpect(status().isBadRequest());

        var post = new com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost(
                "safebooru", 1, "https://safebooru.org/s.jpg", "https://safebooru.org/f.png",
                "t", "safe", 800, 600, "https://safebooru.org/index.php?page=post&s=view&id=1");
        when(service.search(eq("hatsune_miku"), isNull(), eq(0))).thenReturn(List.of(post));
        MvcResult r = mvc.perform(get("/api/video/assets/booru/search").param("q", "hatsune_miku").param("page", "0"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 前端网格依赖的字段名（postId/previewUrl/provider）必须在响应里——改名即破坏 Task 12 面板
        assertTrue(body.contains("\"postId\":1"), body);
        assertTrue(body.contains("\"provider\":\"safebooru\""), body);
        assertTrue(body.contains("previewUrl"), body);
    }
}
