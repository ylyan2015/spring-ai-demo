package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置本地存储的静态资源映射，使得 /images/** 映射到 ./uploads/ 目录
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final StorageService storageService;

    public WebMvcConfig(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 仅当使用本地存储时，注册静态资源映射
        if ("local".equalsIgnoreCase(storageService.getStorageType())) {
            String uploadDir = System.getProperty("user.dir") + "/uploads/";
            registry.addResourceHandler("/images/**")
                    .addResourceLocations("file:" + uploadDir);
        }
    }
}
