package com.github.ylyan2015.springaidemo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * 提供前端页面路由
 */
@Controller
public class PageController {

    /**
     * 首页 - 聊天页面
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
