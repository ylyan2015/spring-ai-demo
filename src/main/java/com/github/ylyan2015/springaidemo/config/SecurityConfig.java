package com.github.ylyan2015.springaidemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // 使用HttpSession存储SecurityContext，确保跨请求保持登录状态
            .securityContext(ctx -> ctx
                .securityContextRepository(new HttpSessionSecurityContextRepository())
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/login", "/video-studio", "/css/**", "/js/**", "/test-timezone.html",
                    "/api/auth/**",
                    // /error 必须放行：未放行时任何未处理异常的错误转发会被鉴权层
                    // 掩码为匿名 403 空响应，掩盖真实故障（UI 验证闭环审查修复）
                    "/error",
                    "/api/model/**",
                    "/api/timezone/**",
                    "/api/video/**",
                    // 本地存储静态资源：文件名 UUID 不可枚举；compose 服务端自下载依赖匿名可读
                    "/images/**",
                    // Actuator 仅公开健康检查，敏感端点（env/loggers 等）需鉴权
                    "/actuator/health", "/actuator/info"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, resp, authentication) -> {
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write("{\"success\":true,\"message\":\"已退出登录\"}");
                })
            );
        return http.build();
    }
}
