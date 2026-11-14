package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.config.RsaKeyPairGenerator;
import com.github.ylyan2015.springaidemo.service.AuthService;
import com.github.ylyan2015.springaidemo.service.CaptchaService;
import com.github.ylyan2015.springaidemo.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 * 处理注册、登录、验证码、公钥获取
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final RsaKeyPairGenerator rsaKeyPairGenerator;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService,
                          CaptchaService captchaService,
                          RsaKeyPairGenerator rsaKeyPairGenerator,
                          LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.rsaKeyPairGenerator = rsaKeyPairGenerator;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * 获取RSA公钥（前端用于加密密码）
     */
    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        Map<String, String> result = new HashMap<>();
        result.put("publicKey", rsaKeyPairGenerator.getPublicKeyBase64());
        return ResponseEntity.ok(result);
    }

    /**
     * 生成验证码（4位字母数字，返回干扰线图片；答案仅存 Session，release 审查 M1）
     */
    @GetMapping("/captcha")
    public ResponseEntity<Map<String, Object>> getCaptcha(HttpSession session) {
        Map<String, String> captchaData = captchaService.generateCaptcha(session);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("image", captchaData.get("image"));
        // dev 调试开关开启时才附带明文（e2e 自动化用，生产默认关闭）
        if (captchaData.containsKey("captcha")) {
            result.put("captcha", captchaData.get("captcha"));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request,
                                                         HttpSession session,
                                                         HttpServletRequest httpRequest) {
        // 1. 验证码校验
        if (!captchaService.validateCaptcha(session, request.getCaptcha())) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "验证码错误");
            return ResponseEntity.badRequest().body(result);
        }

        // 2. 调用注册服务
        Map<String, Object> result = authService.register(
                request.getUsername(),
                request.getPassword(),
                request.getConfirmPassword(),
                request.getCaptcha(),
                session
        );

        if ((boolean) result.get("success")) {
            // 防会话固定：登录态建立后更换 Session ID（release 审查 M2）
            httpRequest.changeSessionId();
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                      HttpSession session,
                                                      HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        // 0. 登录限速：60 秒内同用户名+IP 最多 5 次失败（release 审查 M1）
        if (!loginRateLimiter.tryAcquire(request.getUsername(), ip)) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "尝试过于频繁，请 1 分钟后再试");
            return ResponseEntity.status(429).body(result);
        }

        // 1. 验证码校验
        if (!captchaService.validateCaptcha(session, request.getCaptcha())) {
            loginRateLimiter.recordFailure(request.getUsername(), ip);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "验证码错误");
            return ResponseEntity.badRequest().body(result);
        }

        // 2. 调用登录服务
        Map<String, Object> result = authService.login(
                request.getUsername(),
                request.getPassword(),
                request.getCaptcha(),
                session
        );

        if ((boolean) result.get("success")) {
            loginRateLimiter.recordSuccess(request.getUsername(), ip);
            // 防会话固定：登录态建立后更换 Session ID（release 审查 M2）
            httpRequest.changeSessionId();
            return ResponseEntity.ok(result);
        } else {
            loginRateLimiter.recordFailure(request.getUsername(), ip);
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取当前登录用户
     */
    @GetMapping("/user")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Map<String, Object> result = new HashMap<>();

        if (auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser")) {
            result.put("loggedIn", true);
            result.put("username", auth.getName());
        } else {
            result.put("loggedIn", false);
        }
        return ResponseEntity.ok(result);
    }

    // ---- DTOs ----

    public static class RegisterRequest {
        private String username;
        private String password;        // RSA encrypted
        private String confirmPassword; // RSA encrypted
        private String captcha;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getConfirmPassword() { return confirmPassword; }
        public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
        public String getCaptcha() { return captcha; }
        public void setCaptcha(String captcha) { this.captcha = captcha; }
    }

    public static class LoginRequest {
        private String username;
        private String password; // RSA encrypted
        private String captcha;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getCaptcha() { return captcha; }
        public void setCaptcha(String captcha) { this.captcha = captcha; }
    }
}
