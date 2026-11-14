package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置本地存储的静态资源映射：accessPath（默认 /images/**）映射到 basePath（默认 ./uploads/）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final StorageService storageService;
    private final StorageProperties storageProperties;

    public WebMvcConfig(StorageService storageService, StorageProperties storageProperties) {
        this.storageService = storageService;
        this.storageProperties = storageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 仅当使用本地存储时，注册静态资源映射；路径取自配置，改 storage.local.* 后不再 404（release 审查 Minor#2）
        if ("local".equalsIgnoreCase(storageService.getStorageType())) {
            String uploadDir = java.nio.file.Paths.get(storageProperties.getLocal().getBasePath())
                    .toAbsolutePath().normalize().toString().replace('\\', '/') + "/";
            String accessPath = storageProperties.getLocal().getAccessPath();
            registry.addResourceHandler(accessPath + "/**")
                    .addResourceLocations("file:" + uploadDir);
        }
    }
}
