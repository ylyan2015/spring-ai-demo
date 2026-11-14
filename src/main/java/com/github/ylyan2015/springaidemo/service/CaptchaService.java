package com.github.ylyan2015.springaidemo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpSession;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证码服务
 * 生成并验证4位字母数字随机验证码（排除易混淆字符 0/O/o/z/Z/2），答案仅存 Session，
 * 接口返回干扰线图片（release 审查 M1：明文返回答案会使防爆破形同虚设）
 */
@Service
public class CaptchaService {

    // 排除易混淆字符：0, O, o, z, Z, 2
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYabcdefghjkmnpqrstuvwxy13456789";
    private static final int CAPTCHA_LENGTH = 4;
    private static final String SESSION_CAPTCHA_KEY = "CAPTCHA";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    /** dev 调试开关：仅 e2e 自动化需要时开启，生产必须保持 false */
    @Value("${auth.captcha.debug-text:false}")
    private boolean debugTextEnabled;

    public void setDebugTextEnabled(boolean debugTextEnabled) {
        this.debugTextEnabled = debugTextEnabled;
    }

    /**
     * 生成验证码并保存到 Session，返回 base64 图片；答案不随响应返回（除非显式开启 dev 调试开关）
     */
    public Map<String, String> generateCaptcha(HttpSession session) {
        StringBuilder sb = new StringBuilder(CAPTCHA_LENGTH);
        for (int i = 0; i < CAPTCHA_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        String captcha = sb.toString();
        session.setAttribute(SESSION_CAPTCHA_KEY, captcha);
        Map<String, String> result = new HashMap<>();
        result.put("image", renderImage(captcha));
        if (debugTextEnabled) {
            result.put("captcha", captcha);
        }
        return result;
    }

    /** 绘制带干扰线/噪点/随机旋转的验证码 PNG，返回 data URI */
    private String renderImage(String captcha) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 246, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // 干扰线
            for (int i = 0; i < 5; i++) {
                g.setColor(new Color(RANDOM.nextInt(160), RANDOM.nextInt(160), RANDOM.nextInt(160)));
                g.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }
            // 字符：随机字体/颜色/旋转
            String[] fonts = {"Arial", "Verdana", "Georgia", "Tahoma"};
            for (int i = 0; i < captcha.length(); i++) {
                g.setColor(new Color(RANDOM.nextInt(100), RANDOM.nextInt(100), RANDOM.nextInt(100)));
                g.setFont(new Font(fonts[RANDOM.nextInt(fonts.length)], Font.BOLD, 26 + RANDOM.nextInt(6)));
                double theta = (RANDOM.nextDouble() - 0.5) * 0.5;
                int x = 12 + i * 26;
                int y = 28 + RANDOM.nextInt(6);
                g.rotate(theta, x, y);
                g.drawString(String.valueOf(captcha.charAt(i)), x, y);
                g.rotate(-theta, x, y);
            }
            // 噪点
            for (int i = 0; i < 40; i++) {
                image.setRGB(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), RANDOM.nextInt(0xFFFFFF));
            }
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
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
