package com.github.ylyan2015.springaidemo.config;

import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * booru 专用 HttpClient：禁跟随重定向（spec §4.2 闸 1）+ 10s 连接超时。
 * 策略必须复用 BooruProvider.newHttpClient()——不得在此重写一份 10s/NEVER：
 * 两处安全配置日后只改一处即漂移（Task 10 审查 S-3，由 BooruConfigTest 钉住）。
 */
@Configuration
public class BooruConfig {

    @Bean
    public HttpClient booruHttpClient() {
        return BooruProvider.newHttpClient();
    }
}
