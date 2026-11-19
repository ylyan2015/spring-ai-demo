package com.github.ylyan2015.springaidemo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 评论工作台页面渲染测试（Phase 5 Track B Task 2，沿用 VideoStudioPageRenderTest idiom）
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:commentarypage"})
@AutoConfigureMockMvc
class CommentaryPageRenderTest {

    @Autowired MockMvc mockMvc;

    @Test void authenticatedPageRendersCoreControls() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("page-tester", null, null)));
        mockMvc.perform(get("/commentary-studio").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("commentaryScriptList")))
                .andExpect(content().string(containsString("function importCommentaryJson")))
                .andExpect(content().string(containsString("function saveShot")))
                .andExpect(content().string(containsString("cExportBtn")));
    }

    @Test void anonymousRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/commentary-studio")).andExpect(status().is3xxRedirection());
    }

    /**
     * 发布面板（计划 Task 7）。只卡「结构存在 + 限额来自 /spec 端点」：
     * 计数器颜色、复制整包这类行为在本页无框架 JS 里，判据只能卡在「它读的是服务端规格」这一条上
     * （否则平台改了上限，前端还拿着写死的 80 计数，就是本项目反复强调的「两套真源」）。
     */
    @Test void publishPanelRendersAndTakesLimitsFromSpecEndpoint() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("page-tester", null, null)));
        mockMvc.perform(get("/commentary-studio").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"publishPanel\"")))
                .andExpect(content().string(containsString("id=\"pubTitle\"")))
                .andExpect(content().string(containsString("id=\"pubTags\"")))
                .andExpect(content().string(containsString("id=\"pubDesc\"")))
                .andExpect(content().string(containsString("id=\"pubCategory\"")))
                .andExpect(content().string(containsString("id=\"pubDeclaration\"")))
                .andExpect(content().string(containsString("id=\"pubPinned\"")))
                .andExpect(content().string(containsString("function openPublishPanel")))
                .andExpect(content().string(containsString("function loadPublishDraft")))
                .andExpect(content().string(containsString("function copyPublishPack")))
                .andExpect(content().string(containsString("/api/video/publish/spec")))
                // 计数器必须用端点下发的规格值，不得在 JS 里再抄一份平台规则
                .andExpect(content().string(containsString("publishSpec.titleMax")))
                .andExpect(content().string(containsString("publishSpec.tagMax")))
                .andExpect(content().string(containsString("publishSpec.descMax")));
    }
}