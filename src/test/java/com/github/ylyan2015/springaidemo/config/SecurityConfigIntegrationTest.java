package com.github.ylyan2015.springaidemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全配置集成测试（release 审查修复 C2/C3/M7）
 * 走真实 Spring Security 过滤链，覆盖单元测试盲区：
 * - /images/** 必须放行（local 存储下 compose 服务端自下载依赖，匿名 403 会导致全部视频导出失败）
 * - /h2-console 必须关闭且不放行（空密码 console = 未授权 RCE）
 * - Actuator 仅 health/info 暴露，敏感端点需鉴权
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:securitytest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void imagesPathIsPermittedForServerSideSelfDownload() throws Exception {
        // 匿名访问 /images/** 不得被安全过滤链拦截（401/403）；
        // 文件不存在时资源处理返回 404，证明已通过鉴权层
        mockMvc.perform(get("/images/2026/01/01/nonexistent.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void h2ConsoleIsDisabledAndNotPermitted() throws Exception {
        assertEquals("false", environment.getProperty("spring.h2.console.enabled"),
                "H2 Console 必须关闭（空密码 console 暴露 = 未授权 RCE）");
        int status = mockMvc.perform(get("/h2-console")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "/h2-console 不应放行，实际: " + status);
    }

    @Test
    void actuatorSensitiveEndpointsRequireAuthentication() throws Exception {
        int envStatus = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();
        assertTrue(envStatus == 401 || envStatus == 403, "/actuator/env 泄漏配置，必须拦截，实际: " + envStatus);
        int loggersStatus = mockMvc.perform(get("/actuator/loggers")).andReturn().getResponse().getStatus();
        assertTrue(loggersStatus == 401 || loggersStatus == 403, "/actuator/loggers 可被未授权修改，必须拦截，实际: " + loggersStatus);
    }

    @Test
    void actuatorHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}