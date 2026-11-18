package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * safebooru.donmai.us 备源（spec §4.1）：posts.json 接口，国内可直连，免费档限 2 tag 组合。
 * tags 强制追加 rating:g（闸：即使调用方漏传也不会取回 questionable/explicit），服务层再复核一次。
 */
@Component
public class DanbooruSafeProvider implements BooruProvider {

    private static final String BASE = "https://safebooru.donmai.us";
    /** 注册域：API 走 safebooru.donmai.us，图片实际托管在 cdn.donmai.us（/original|sample/xx/xx/md5.ext） */
    private static final String MEDIA_DOMAIN = "donmai.us";

    private final HttpClient http;
    private final ObjectMapper om;

    /** Spring 装配用（显式钉住选择，Task 11 引入 HttpClient Bean 后也不会静默换客户端）；测试走双参构造注入 mock */
    @Autowired
    public DanbooruSafeProvider() {
        this(BooruProvider.newHttpClient(), new ObjectMapper());
    }

    public DanbooruSafeProvider(HttpClient http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override
    public String name() {
        return "danbooru-safe";
    }

    @Override
    public List<BooruPost> search(String tags, int limit, int page) throws Exception {
        String query = (tags == null || tags.isBlank()) ? "rating:g" : tags + "+rating:g";
        String url = BASE + "/posts.json?tags="
                + BooruProvider.encodeTagQuery(query)
                + "&limit=" + limit + "&page=" + (page + 1);   // donmai 页码 1 基
        JsonNode arr = om.readTree(BooruProvider.fetchJson(http, url, name()));
        List<BooruPost> posts = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return posts;
        }
        for (JsonNode n : arr) {
            BooruPost post = toPost(n);
            if (post != null) {
                posts.add(post);
            }
        }
        return posts;
    }

    /** 入库重查走单对象端点（spec §4.2 闸 2）：服务端只认 postId，不认客户端传来的任何 URL */
    @Override
    public BooruPost findById(long postId) throws Exception {
        JsonNode node = om.readTree(BooruProvider.fetchJson(http, BASE + "/posts/" + postId + ".json", name()));
        if (node == null || !node.isObject()) {
            throw new RuntimeException("danbooru 帖子不存在: " + postId);
        }
        BooruPost post = toPost(node);
        if (post == null) {
            throw new RuntimeException("danbooru 帖子无可用文件: " + postId);
        }
        return post;
    }

    private BooruPost toPost(JsonNode n) {
        long id = n.path("id").asLong();
        String fileUrl = n.path("file_url").asText("");
        // 同注册域护栏：这是全链路唯一采信远端 URL 的地方，跳域的 file_url 不得往下载环节传。
        // 不得改回 startsWith(BASE + "/")：图片域与 API 域分离，那样备源会整源落空且不报错（Task 12 实测）
        if (id <= 0 || !isDonmaiMediaUrl(fileUrl)) {
            return null;
        }
        String preview = BooruProvider.toAbsoluteUrl(BASE, n.path("preview_file_url").asText(""), fileUrl);
        return new BooruPost(name(), id, preview, fileUrl,
                n.path("tag_string").asText(""), n.path("rating").asText(""),
                n.path("image_width").asInt(), n.path("image_height").asInt(),
                BASE + "/posts/" + id);
    }

    /**
     * https + 注册域判定（donmai.us 本身或其子域）：cdn.donmai.us 存活，
     * 而 cdn.donmai.us.evil.example / donmai.us.evil.example / 明文 http 一律拒。
     * 这里只做「不往下载环节传跳域 URL」的早筛，精确 host 白名单仍由服务层
     * video.assets.booru.allowed-hosts 把关，两侧必须同步放宽（只改一侧等于没改）。
     */
    static boolean isDonmaiMediaUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(uri.getScheme()) && host != null
                && (host.equals(MEDIA_DOMAIN) || host.endsWith("." + MEDIA_DOMAIN));
    }
}
