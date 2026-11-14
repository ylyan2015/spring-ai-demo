package com.github.ylyan2015.springaidemo.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证码服务测试（release 审查 M1：不得明文返回验证码答案）
 */
class CaptchaServiceTest {

    private CaptchaService captchaService;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        captchaService = new CaptchaService();
        session = new MockHttpSession();
    }

    @Test
    void generateReturnsBase64ImageWithoutPlainTextAnswer() {
        Map<String, String> out = captchaService.generateCaptcha(session);
        assertTrue(out.containsKey("image"), "应返回图片字段");
        assertTrue(out.get("image").startsWith("data:image/png;base64,"), "应为 PNG data URI");
        assertFalse(out.containsKey("captcha"), "不得明文返回验证码答案");
        assertNotNull(session.getAttribute("CAPTCHA"), "答案应仅存于 Session");
    }

    @Test
    void validateStillWorksWithSessionStoredAnswer() {
        captchaService.generateCaptcha(session);
        String answer = (String) session.getAttribute("CAPTCHA");
        assertTrue(captchaService.validateCaptcha(session, answer.toLowerCase()), "不区分大小写");
        // 验证后立即清除，防重复使用
        assertFalse(captchaService.validateCaptcha(session, answer));
    }

    @Test
    void debugTextOnlyWhenExplicitlyEnabled() {
        captchaService.setDebugTextEnabled(true);
        Map<String, String> out = captchaService.generateCaptcha(session);
        assertEquals(session.getAttribute("CAPTCHA"), out.get("captcha"),
                "仅 dev 调试开关开启时才返回明文（e2e 自动化用）");
    }
}