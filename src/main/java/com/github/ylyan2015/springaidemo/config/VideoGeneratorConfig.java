package com.github.ylyan2015.springaidemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.ComfyUiVideoGenerator;
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

    /**
     * Phase 5 切片 1：本地 ComfyUI i2v（零成本路线）。
     * 未启动 ComfyUI/工作流未配置时 isAvailable=false，不阻断干净启动；超时/轮询/fps 可配置。
     */
    @Bean
    public ComfyUiVideoGenerator comfyUiVideoGenerator(
            StorageService storageService,
            @Value("${video.comfyui.base-url:http://127.0.0.1:8188}") String baseUrl,
            @Value("${video.comfyui.workflow-path:docs/comfyui/wan22_i2v_5b.template.json}") String workflowPath,
            @Value("${server.port:8080}") int serverPort,
            @Value("${video.comfyui.fps:24}") int fps,
            @Value("${video.comfyui.timeout-seconds:1800}") int timeoutSeconds,
            @Value("${video.comfyui.poll-interval-ms:3000}") long pollIntervalMs) {
        // 本机有系统代理（flclash）：localhost ComfyUI 调用强制 NO_PROXY，防代理劫持
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .proxy(HttpClient.Builder.NO_PROXY).build();
        return new ComfyUiVideoGenerator(storageService, new ObjectMapper(), http,
                baseUrl, workflowPath, serverPort, fps, timeoutSeconds, pollIntervalMs);
    }
}