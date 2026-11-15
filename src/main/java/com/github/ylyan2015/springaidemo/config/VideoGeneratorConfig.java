package com.github.ylyan2015.springaidemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.DashScopeWanVideoGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 视频生成器注册（Phase 4 切片 2）
 * 空 Key 不可用（干净启动安全）；Kling/Seedance 为预留扩展，本期不注册。
 */
@Configuration
public class VideoGeneratorConfig {

    @Bean
    public DashScopeWanVideoGenerator dashScopeWanVideoGenerator(
            StorageService storageService,
            @Value("${video.wan.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${video.wan.model:wan2.7-i2v}") String model,
            @Value("${video.wan.resolution:720P}") String resolution,
            @Value("${video.wan.timeout-seconds:600}") int timeoutSeconds,
            @Value("${video.wan.poll-interval-ms:5000}") long pollIntervalMs) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new DashScopeWanVideoGenerator(storageService, new ObjectMapper(), http,
                baseUrl, apiKey, model, resolution, timeoutSeconds, pollIntervalMs);
    }
}