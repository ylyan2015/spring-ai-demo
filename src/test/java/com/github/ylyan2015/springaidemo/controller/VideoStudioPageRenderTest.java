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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("upscaleToggle")))
                // Task 12 素材库面板：面板容器 + 三入口函数都得在（历史故障形态是后半 JS 整段丢失）
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"assetPanel\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("async function booruIngestSelected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("async function uploadRefAsset")))
                // esc() 的双引号转义必须以原样落到响应里：一是页内属性注入收口不能逆回，
                // 二是兼验 <script> 体没被 Thymeleaf 当实体引用解码（&quot; 被解成 " 就是 JS 语法错误）
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".replace(/\"/g, '&quot;')")));
    }

    @Test
    void videoStudioRedirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/video-studio"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * 脚本自我精修（2 轮实测 ≈13 分钟）前端长等待接线：服务端注入可配置预算（默认 900s）
     * + 页面 AbortController 包裹的 postLong。历史隐患：客户端先断连而服务端照常落库，
     * 用户误判生成失败——三锚点缺一即接线断裂。
     */
    @Test
    void scriptGenerateLongWaitWiringRendered() throws Exception {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("page-tester", null, null));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", ctx);

        mockMvc.perform(get("/video-studio").session(session))
                .andExpect(status().isOk())
                // 预算由 controller 注入（非硬编码），默认 900000ms 覆盖 13 分钟全链路+余量
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-gen-timeout-ms=\"900000\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function postLong")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AbortController")))
                // 两条生成路由（自由创作/模板应用）都得切长等待，漏一条则模板模式照旧先断
                .andExpect(content().string(org.hamcrest.Matchers.containsString("await postLong('/api/video/scripts/generate'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("await postLong('/api/video/templates/'")));
    }
}