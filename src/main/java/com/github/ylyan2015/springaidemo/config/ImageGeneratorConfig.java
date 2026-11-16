package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.ComfyUiImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageSizeMapper;
import com.github.ylyan2015.springaidemo.video.generator.OpenAiCompatibleImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.Wan27ImageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * OpenAI 兼容图像生成器注册（智谱 / 硅基流动 / OpenAI / 火山方舟）
 * 四个 Bean 由 ImageGeneratorFactory 的 List 注入自动收录，
 * 配置驱动（image.generator）与前端运行时切换沿用现有机制；万相保持默认。
 * Key 一律经环境变量注入（本文件 git 跟踪，禁止写入真实 Key）；空 Key 时该提供者不可用。
 */
@Configuration
public class ImageGeneratorConfig {

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    public OpenAiCompatibleImageGenerator zhipuImageGenerator(
            StorageService storageService,
            @Value("${image.compat.zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${image.compat.zhipu.api-key:}") String apiKey,
            @Value("${image.compat.zhipu.model:cogview-3-flash}") String model,
            @Value("${image.compat.zhipu.fixed-sizes:1024x1024,1344x768,768x1344,1152x864,864x1152,1440x720,720x1440}") String fixedSizes) {
        return new OpenAiCompatibleImageGenerator("zhipu", "智谱 CogView (" + model + ")",
                baseUrl, apiKey, model, ImageSizeMapper.parse(fixedSizes), storageService, defaultHttpClient());
    }

    @Bean
    public OpenAiCompatibleImageGenerator siliconflowImageGenerator(
            StorageService storageService,
            @Value("${image.compat.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${image.compat.siliconflow.api-key:}") String apiKey,
            @Value("${image.compat.siliconflow.model:Kwai-Kolors/Kolors}") String model,
            @Value("${image.compat.siliconflow.fixed-sizes:}") String fixedSizes) {
        return new OpenAiCompatibleImageGenerator("siliconflow", "硅基流动 (" + model + ")",
                baseUrl, apiKey, model, ImageSizeMapper.parse(fixedSizes), storageService, defaultHttpClient());
    }

    @Bean
    public OpenAiCompatibleImageGenerator openAiImageGenerator(
            StorageService storageService,
            @Value("${image.compat.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${image.compat.openai.api-key:}") String apiKey,
            @Value("${image.compat.openai.model:gpt-image-1}") String model,
            @Value("${image.compat.openai.fixed-sizes:1024x1024,1536x1024,1024x1536}") String fixedSizes) {
        return new OpenAiCompatibleImageGenerator("openai", "OpenAI (" + model + ")",
                baseUrl, apiKey, model, ImageSizeMapper.parse(fixedSizes), storageService, defaultHttpClient());
    }

    @Bean
    public OpenAiCompatibleImageGenerator arkImageGenerator(
            StorageService storageService,
            @Value("${image.compat.ark.base-url:https://ark.cn-beijing.volces.com/api/v3}") String baseUrl,
            @Value("${image.compat.ark.api-key:}") String apiKey,
            @Value("${image.compat.ark.model:doubao-seedream-4-0-250828}") String model,
            @Value("${image.compat.ark.fixed-sizes:}") String fixedSizes) {
        return new OpenAiCompatibleImageGenerator("ark", "火山方舟 Seedream (" + model + ")",
                baseUrl, apiKey, model, ImageSizeMapper.parse(fixedSizes), storageService, defaultHttpClient());
    }

    /**
     * Phase 4 切片 1：万相 2.7 参考图生成器（复用 DASHSCOPE_API_KEY；空 Key 不可用）
     */
    @Bean
    public Wan27ImageGenerator wan27ImageGenerator(
            StorageService storageService,
            @Value("${image.wan27.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${image.wan27.model:wan2.7-image}") String model,
            @Value("${image.wan27.timeout-seconds:120}") int timeoutSeconds) {
        return new Wan27ImageGenerator(storageService, new ObjectMapper(), defaultHttpClient(),
                baseUrl, apiKey, model, timeoutSeconds);
    }

    /**
     * Phase 5 切片 3：本地 ComfyUI SD1.5+LoRA 分镜（base-url 复用 video.comfyui.base-url；NO_PROXY 防系统代理劫持）
     */
    @Bean
    public ComfyUiImageGenerator comfyUiImageGenerator(
            StorageService storageService,
            @Value("${video.comfyui.base-url:http://127.0.0.1:8188}") String baseUrl,
            @Value("${image.comfyui.workflow-plain:docs/comfyui/sd15_storyboard.template.json}") String plainPath,
            @Value("${image.comfyui.workflow-lora:docs/comfyui/sd15_lora_storyboard.template.json}") String loraPath,
            @Value("${image.comfyui.workflow-canny:docs/comfyui/sd15_lora_canny.template.json}") String cannyPath,
            @Value("${server.port:8080}") int serverPort,
            @Value("${image.comfyui.timeout-seconds:120}") int timeoutSeconds,
            @Value("${image.comfyui.poll-interval-ms:1000}") long pollIntervalMs) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .proxy(HttpClient.Builder.NO_PROXY).build();
        return new ComfyUiImageGenerator(storageService, new ObjectMapper(), http, baseUrl,
                plainPath, loraPath, cannyPath, serverPort, timeoutSeconds, pollIntervalMs);
    }
}