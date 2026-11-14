package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.ImageSizeMapper;
import com.github.ylyan2015.springaidemo.video.generator.OpenAiCompatibleImageGenerator;
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
}