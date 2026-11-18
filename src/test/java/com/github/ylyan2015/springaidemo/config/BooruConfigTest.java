package com.github.ylyan2015.springaidemo.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4 闸 1 的装配点核验（Task 10 审查 S-3）：booru HttpClient 的安全策略必须复用
 * {@code BooruProvider.newHttpClient()} 这一份，否则两处配置日后只改一处即漂移。
 */
class BooruConfigTest {

    @Test
    void booruHttpClientCarriesTheProviderSecurityPolicy() {
        HttpClient c = new BooruConfig().booruHttpClient();
        // 10s 连接超时是「是否复用同一份」的可判别证据：裸 HttpClient.newBuilder().build() 的 connectTimeout 为空
        assertEquals(Duration.ofSeconds(10), c.connectTimeout().orElseThrow(),
                "connectTimeout 缺失说明本 Bean 自建了一份 client，安全策略出现第二处副本");
        // NEVER 断言的作用是钉住「不得改成 NORMAL」；注意 JDK HttpClient.Builder 默认已是 NEVER（Task 10 实测），
        // 因此本条不能证明声明存在，只能证明策略没被放宽
        assertEquals(HttpClient.Redirect.NEVER, c.followRedirects());
    }
}
