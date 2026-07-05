package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储服务工厂配置
 * 根据 application.yml 中的 storage.type 配置自动注入对应的 StorageService 实现
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final StorageProperties storageProperties;

    public StorageConfig(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * 根据配置创建对应的 StorageService Bean
     */
    @Bean
    public StorageService storageService() {
        String type = storageProperties.getType().toLowerCase();
        StorageService service = switch (type) {
            case "oss" -> new OssStorageService(storageProperties.getOss());
            case "minio" -> new MinioStorageService(storageProperties.getMinio());
            case "fastdfs" -> new FastDfsStorageService(storageProperties.getFastdfs());
            default -> {
                log.info("存储类型未指定或为 local，使用本地存储");
                yield new LocalStorageService(storageProperties.getLocal());
            }
        };
        log.info("✓ 存储服务已初始化，类型: {}", service.getStorageType());
        return service;
    }
}
