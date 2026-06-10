package com.github.ylyan2015.springaidemo.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * 提供前端页面路由
 */
@Controller
public class PageController {

    /**
     * 首页 - 聊天页面（需要登录）
     */
    @GetMapping("/")
    public String index() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "redirect:/login";
        }
        return "index";
    }

    /**
     * 登录/注册页面
     */
    @GetMapping("/login")
    public String login() {
        // 已登录则跳转首页
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return "redirect:/";
        }
        return "login";
    }
}
