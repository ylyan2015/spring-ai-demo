package com.github.ylyan2015.springaidemo.service;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证码服务
 * 生成并验证5位字母数字随机验证码，存储在Session中
 */
@Service
public class CaptchaService {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CAPTCHA_LENGTH = 5;
    private static final String SESSION_CAPTCHA_KEY = "CAPTCHA";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成验证码并保存到Session，返回验证码文本（用于调试，实际生产不应返回）
     */
    public Map<String, String> generateCaptcha(HttpSession session) {
        StringBuilder sb = new StringBuilder(CAPTCHA_LENGTH);
        for (int i = 0; i < CAPTCHA_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        String captcha = sb.toString();
        session.setAttribute(SESSION_CAPTCHA_KEY, captcha);
        Map<String, String> result = new HashMap<>();
        result.put("captcha", captcha);
        return result;
    }

    /**
     * 验证验证码，不区分大小写
     */
    public boolean validateCaptcha(HttpSession session, String inputCaptcha) {
        if (inputCaptcha == null) return false;
        String stored = (String) session.getAttribute(SESSION_CAPTCHA_KEY);
        if (stored == null) return false;
        boolean valid = stored.equalsIgnoreCase(inputCaptcha);
        // 验证后立即清除，防止重复使用
        session.removeAttribute(SESSION_CAPTCHA_KEY);
        return valid;
    }
}
