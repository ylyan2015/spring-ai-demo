package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.exception.PublishRejectException;
import com.github.ylyan2015.springaidemo.service.PublishMetaDeriver;
import com.github.ylyan2015.springaidemo.service.PublishMetaLlmService;
import com.github.ylyan2015.springaidemo.service.PublishMetaService;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布元数据端点的 HTTP 边界测试。standaloneSetup 足够：{@code /api/video/**} 在 SecurityConfig 里
 * 是 permitAll，登录与归属由服务层 {@code getCurrentUserId()} 兜（沿用本项目既有鉴权口径），
 * 所以这里要卡的是「状态码分流」与「字段形状」，不是安全链路本身。
 */
class PublishMetaControllerTest {

    private final PublishMetaService metaService = mock(PublishMetaService.class);
    private final PublishMetaDeriver deriver = mock(PublishMetaDeriver.class);
    private final PublishMetaLlmService llmService = mock(PublishMetaLlmService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PublishMetaController(metaService, deriver, llmService)).build();

    private static Map<String, Object> draft() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("platform", "BILIBILI");
        d.put("title", "萌系番的每个笑点，都是作者的爆仓记录");
        d.put("description", "【本片事实】\n· 峰值亏 1400 万日元\n【素材出处】\n- 官网 fxkurumi-info.com");
        d.put("tags", List.of("福贺久留美", "动漫杂谈"));
        d.put("categoryTid", 253);
        d.put("approxTimestamps", false);
        return d;
    }

    // ==================== GET /spec ====================

    @Test
    void specExposesLimitsCategoriesAndTidHint() throws Exception {
        MvcResult r = mvc.perform(get("/api/video/publish/spec").param("platform", "BILIBILI"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 前端计数器与分区下拉只吃这个响应，JS 里不许再硬编码一份平台规则
        assertTrue(body.contains("\"titleMax\":80"), body);
        assertTrue(body.contains("\"tagMax\":10"), body);
        assertTrue(body.contains("\"descMax\":2000"), body);
        assertTrue(body.contains("253"), body);
        assertTrue(body.contains("\"tid\":182"), body);
        assertTrue(body.contains("?tid="), "灰度期 ?tid= 兜底提示要由服务端下发：" + body);
    }

    @Test
    void specOfUnsupportedPlatformIs400Not500() throws Exception {
        // specFor 是静态方法（规格表本就服务端唯一一份），这里直接跑真实现：
        // /spec 返的就是 PublishSpec 里那张表，测它比测一个被桩坏的中间层更真
        MvcResult r = mvc.perform(get("/api/video/publish/spec").param("platform", "DOUYIN"))
                .andExpect(status().isBadRequest()).andReturn();
        assertTrue(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("DOUYIN"));
    }

    // ==================== GET /{scriptId}/draft ====================

    @Test
    void draftEnrichesTitleCandidatesByDefault() throws Exception {
        when(deriver.derive(738L, "BILIBILI")).thenReturn(draft());
        when(llmService.generateTitles(738L, "BILIBILI")).thenReturn(List.of("候选一", "候选二"));
        when(llmService.generateHook(738L, "BILIBILI")).thenReturn("画久留美的人，爆仓了。");

        MvcResult r = mvc.perform(get("/api/video/publish/738/draft").param("platform", "BILIBILI"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"titleCandidates\""), body);
        assertTrue(body.contains("候选一"), body);
        assertTrue(body.contains("\"hook\""), body);
        // 派生器自己那份事实节不能被 LLM 结果挤掉
        assertTrue(body.contains("1400 万日元"), body);
        verify(llmService).generateTitles(738L, "BILIBILI");
    }

    @Test
    void draftWithoutLlmNeverCallsTheModel() throws Exception {
        when(deriver.derive(738L, "BILIBILI")).thenReturn(draft());
        MvcResult r = mvc.perform(get("/api/video/publish/738/draft")
                        .param("platform", "BILIBILI").param("llm", "false"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"titleCandidates\":[]"), body);
        verifyNoInteractions(llmService);
    }

    // ==================== PUT /{scriptId}/meta ====================

    @Test
    void putReturns400WithStructuredViolationCodes() throws Exception {
        when(metaService.save(eq(738L), eq("BILIBILI"), any())).thenThrow(new PublishRejectException(
                "发布字段不符合 BILIBILI 规格",
                List.of(new PublishSpec.Violation("title", PublishSpec.TITLE_TOO_LONG, "标题超过 80 字符"))));
        MvcResult r = mvc.perform(put("/api/video/publish/738/meta")
                        .param("platform", "BILIBILI")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "久".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 前端要按 code 定位到具体输入框，只回一句 message 等于让用户自己猜哪儿错了
        assertTrue(body.contains("\"code\":\"TITLE_TOO_LONG\""), body);
        assertTrue(body.contains("\"field\":\"title\""), body);
    }

    @Test
    void putPassesRequestBodyThroughAndReturnsSavedRow() throws Exception {
        PublishMeta entity = new PublishMeta();
        entity.setScriptId(738L);
        Map<String, Object> saved = draft();
        saved.put("source", "MANUAL");
        when(metaService.save(eq(738L), eq("BILIBILI"), any())).thenReturn(entity);
        when(metaService.toMap(entity)).thenReturn(saved);
        mvc.perform(put("/api/video/publish/738/meta")
                        .param("platform", "BILIBILI")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 只发改动字段：部分更新语义是 T2 定的，这层不能偷偷补空键
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(metaService).save(eq(738L), eq("BILIBILI"), captor.capture());
        assertEquals(Map.of("title", "新标题"), captor.getValue(),
                "请求体原样透传即可，多余的键会让服务层把未改动字段判成显式清空：" + captor.getValue());
    }

    // ==================== GET /{scriptId}/meta ====================

    @Test
    void metaReturnsStoredRowWithoutDerivingAgain() throws Exception {
        PublishMeta entity = new PublishMeta();
        entity.setScriptId(738L);
        Map<String, Object> stored = draft();
        stored.put("source", "MANUAL");
        when(metaService.find(738L, "BILIBILI")).thenReturn(Optional.of(entity));
        when(metaService.toMap(entity)).thenReturn(stored);
        mvc.perform(get("/api/video/publish/738/meta").param("platform", "BILIBILI"))
                .andExpect(status().isOk());
        verifyNoInteractions(deriver);
        verifyNoInteractions(llmService);
    }

    @Test
    void metaFallsBackToDeterministicDraftWhenNothingStored() throws Exception {
        when(metaService.find(738L, "BILIBILI")).thenReturn(Optional.empty());
        when(deriver.derive(738L, "BILIBILI")).thenReturn(draft());
        MvcResult r = mvc.perform(get("/api/video/publish/738/meta").param("platform", "BILIBILI"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"fallbackToDraft\":true"), body);
        // /meta 是面板打开就会调的读接口，绝不能顺手烧一次模型
        verifyNoInteractions(llmService);
    }

    // ==================== 失败分流 ====================

    @Test
    void anonymousRequestIs400WithBusinessMessage() throws Exception {
        when(deriver.derive(anyLong(), anyString())).thenThrow(new PublishRejectException("用户未登录"));
        MvcResult r = mvc.perform(get("/api/video/publish/738/draft").param("platform", "BILIBILI"))
                .andExpect(status().isBadRequest()).andReturn();
        assertTrue(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("用户未登录"));
    }

    @Test
    void unexpectedFailureIs500AndDoesNotLeakInternals() throws Exception {
        when(deriver.derive(anyLong(), anyString())).thenThrow(new RuntimeException(
                "could not execute statement [ERROR: column publish_meta.title_x not exist]"));
        MvcResult r = mvc.perform(get("/api/video/publish/738/draft").param("platform", "BILIBILI"))
                .andExpect(status().isInternalServerError()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("column"), "内部故障原文不得回显：" + body);
        assertFalse(body.contains("publish_meta"), body);
    }
}
