package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.config.RsaKeyPairGenerator;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证服务
 * 处理注册、登录、密码验证
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 密码规则：至少一个大写、一个小写、一个数字，长度6-50
     */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{6,50}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RsaKeyPairGenerator rsaKeyPairGenerator;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       RsaKeyPairGenerator rsaKeyPairGenerator) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rsaKeyPairGenerator = rsaKeyPairGenerator;
    }

    /**
     * 注册用户
     *
     * @param username          用户名
     * @param encryptedPassword RSA加密的密码
     * @param confirmPassword   RSA加密的确认密码
     * @param captcha           验证码
     * @param session           HttpSession
     * @return 结果
     */
    public Map<String, Object> register(String username, String encryptedPassword,
                                        String confirmPassword, String captcha,
                                        HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        // 1. 验证码校验（由调用方提前验证，这里再做一次保险）
        // 实际上验证码在Controller层已经验证了，这里不再重复

        // 2. RSA解密密码
        String rawPassword;
        String rawConfirmPassword;
        try {
            rawPassword = rsaKeyPairGenerator.decrypt(encryptedPassword);
            rawConfirmPassword = rsaKeyPairGenerator.decrypt(confirmPassword);
        } catch (Exception e) {
            log.error("注册密码RSA解密失败", e);
            result.put("success", false);
            result.put("message", "密码解密失败，请重试");
            return result;
        }

        // 3. 两次密码一致性校验
        if (!rawPassword.equals(rawConfirmPassword)) {
            result.put("success", false);
            result.put("message", "两次输入的密码不一致");
            return result;
        }

        // 4. 密码强度校验
        if (!PASSWORD_PATTERN.matcher(rawPassword).matches()) {
            result.put("success", false);
            result.put("message", "密码必须包含大写字母、小写字母和数字，长度6-50位");
            return result;
        }

        // 5. 用户名格式校验
        if (username == null || username.trim().isEmpty() || username.length() > 50) {
            result.put("success", false);
            result.put("message", "用户名不能为空且长度不超过50个字符");
            return result;
        }

        // 6. 用户名唯一性校验
        if (userRepository.existsByUsername(username.trim())) {
            result.put("success", false);
            result.put("message", "用户名已存在");
            return result;
        }

        // 7. BCrypt加密存储
        String encodedPassword = passwordEncoder.encode(rawPassword);
        User user = new User(username.trim(), encodedPassword);
        userRepository.save(user);

        // 8. 注册成功后自动登录
        loginUser(user, session);

        result.put("success", true);
        result.put("message", "注册成功");
        result.put("username", user.getUsername());
        return result;
    }

    /**
     * 登录
     */
    public Map<String, Object> login(String username, String encryptedPassword,
                                     String captcha, HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        // RSA解密
        String rawPassword;
        try {
            rawPassword = rsaKeyPairGenerator.decrypt(encryptedPassword);
        } catch (Exception e) {
            log.error("登录密码RSA解密失败", e);
            result.put("success", false);
            result.put("message", "密码解密失败，请重试");
            return result;
        }

        // 查找用户
        User user = userRepository.findByUsername(username)
                .orElse(null);

        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        // 登录成功，建立Spring Security上下文
        loginUser(user, session);

        result.put("success", true);
        result.put("message", "登录成功");
        result.put("username", user.getUsername());
        return result;
    }

    /**
     * 建立用户登录状态
     */
    private void loginUser(User user, HttpSession session) {
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, null);
        SecurityContextHolder.getContext().setAuthentication(authToken);
        // 将SecurityContext保存到Session，让Spring Security跨请求保持登录状态
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
    }
}
