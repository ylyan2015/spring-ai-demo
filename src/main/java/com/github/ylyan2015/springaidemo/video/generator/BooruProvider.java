package com.github.ylyan2015.springaidemo.video.generator;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * booru 图站适配器（spec §4.1）：归一化 BooruPost，双源 List 注入自动收录。
 * 结构红线（spec §4.2.3）：适配器只产出 URL 字符串与元数据，绝不落库/碰存储——
 * 入库必须走 BooruSearchService 的「服务端按 postId 重查元数据 + 下载转存」路径，
 * 外部 fileUrl 永不进 MediaAsset.url，因此也永不进生成链路。
 */
public interface BooruProvider {

    /**
     * 归一化帖元数据。字段序即跨 Task 契约（Task 11 ingest / Task 12 前端网格依赖）。
     * rating 为源站原值透传，编码随源，但实际词表不是 spec 里写的那两个（Task 12 浏览器实测）：
     * safebooru.org dapi = **general**（长式，不是 safe）、safebooru.donmai.us = g/s/q/e。
     * 消费方判据必须同时含 safe / g / general 三形（见 BooruSearchService.SAFE_RATINGS）：少一个不是报错，
     * 而是「搜索成功、空列表」的整源静默落空。注意 s 在 donmai 系 = sensitive（不是 safe），不得当 safe 收。
     * previewUrl/fileUrl：相对串已在适配器补齐为同域绝对串；异域绝对串不在这里拦，由服务层 host 白名单闸拒掉。
     */
    record BooruPost(String provider, long postId, String previewUrl, String fileUrl,
                     String tags, String rating, int width, int height, String sourcePageUrl) {}

    String name();

    /** tags 已归一化（下划线，多 tag 以 + 连接）；page 0 基；limit ≤20 */
    List<BooruPost> search(String tags, int limit, int page) throws Exception;

    /** 入库重查专用（spec §4.2 闸 2：不信任客户端 URL） */
    BooruPost findById(long postId) throws Exception;

    /** booru tag 一律小写 + 下划线分词（空格输入转下划线，与站内 tag 习惯对齐） */
    static String normalizeTags(String q) {
        return q == null ? "" : q.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    /**
     * tags 查询串编码：以 + 为 tag 分隔符（booru 的 AND 约定），逐段编码后原样保留 +。
     * 整串 URLEncoder.encode 会把分隔符变成 %2B，服务端解成字面加号 → 查询恒空。
     * 不保证空段/前导分隔符的往返（"+a" → "a"）：调用方若需追加固定 tag，拼接串不得以 + 开头。
     */
    static String encodeTagQuery(String tags) {
        if (tags == null || tags.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String tag : tags.split("\\+", -1)) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(URLEncoder.encode(tag, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 双源共用的 GET 样板（30s 请求超时 + 固定 UA：donmai 对无 UA 请求直接 403）。
     * HttpClient 由构造器注入，禁跟随重定向在建实例时已设定（spec §4.2 闸 1）。
     */
    static String fetchJson(HttpClient http, String url, String label) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "spring-ai-demo-asset-library").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException(label + " 响应异常: HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 同域绝对化：源站部分字段返相对串，不补齐就会被下游 https 白名单闸误拒（自家主源整批落空） */
    static String toAbsoluteUrl(String base, String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return base + "/" + value.replaceFirst("^/+", "");
    }

    /** 连接 10s 超时 + 禁重定向（spec §4.2 闸 1：重定向可以把请求带出白名单域） */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
