package com.github.ylyan2015.springaidemo.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * 提供前端页面路由
 */
@Controller
public class PageController {

    /** 脚本生成前端等待预算（毫秒）：含自我精修 2 轮实测 ≈13 分钟，默认 900s 覆盖全链路+余量 */
    @Value("${video.script.client-timeout-ms:900000}")
    private long scriptClientTimeoutMs;

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

    /**
     * AI 短视频工作室页面
     */
    @GetMapping("/video-studio")
    public String videoStudio(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "redirect:/login";
        }
        // 页面 postLong 的唯一预算来源：不注入则前端退回硬编码默认，改配置不生效
        model.addAttribute("scriptGenTimeoutMs", scriptClientTimeoutMs);
        return "video-studio";
    }

    /**
     * Phase 5 Track B：评论工作台页面（与导演工作台分离）
     */
    @GetMapping("/commentary-studio")
    public String commentaryStudio() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "redirect:/login";
        }
        return "commentary-studio";
    }
}
