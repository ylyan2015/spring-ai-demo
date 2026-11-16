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
}