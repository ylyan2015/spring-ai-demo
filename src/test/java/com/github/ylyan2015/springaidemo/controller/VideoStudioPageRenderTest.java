package com.github.ylyan2015.springaidemo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 视频工作台页面渲染回归（UI 验证闭环审查修复 C1）：
 * Thymeleaf 3 默认解析文本中的 [[...]] 内联表达式，纯客户端 JS 的数组字面量
 * 曾导致模板解析失败、页面截断渲染（后半 JS 全部丢失、按钮无响应）。
 * 修复为 script th:inline="none"；本测试断言整页完整渲染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pagetest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class VideoStudioPageRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void videoStudioRendersFullTemplateForLoggedInUser() throws Exception {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("page-tester", null, null));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", ctx);

        mockMvc.perform(get("/video-studio").session(session))
                .andExpect(status().isOk())
                // 截断渲染时丢失的后半 JS 与 Phase 4 新 UI 必须完整存在
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function submitInpaint")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function saveShotEdit")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("restylePanel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("upscaleToggle")));
    }

    @Test
    void videoStudioRedirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/video-studio"))
                .andExpect(status().is3xxRedirection());
    }
}