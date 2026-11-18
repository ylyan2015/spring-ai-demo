package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * safebooru.org 主源（spec §4.1）：Gelbooru 引擎 dapi + json=1，全站 safe 但服务层仍复核 rating。
 * 图 URL 由 directory+image 两段自拼，同域故 previewUrl/fileUrl 均落在白名单内。
 */
@Component
public class SafebooruOrgProvider implements BooruProvider {

    private static final String BASE = "https://safebooru.org";

    private final HttpClient http;
    private final ObjectMapper om;

    /** Spring 装配用（显式钉住选择，Task 11 引入 HttpClient Bean 后也不会静默换客户端）；测试走双参构造注入 mock */
    @Autowired
    public SafebooruOrgProvider() {
        this(BooruProvider.newHttpClient(), new ObjectMapper());
    }

    public SafebooruOrgProvider(HttpClient http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override
    public String name() {
        return "safebooru";
    }

    @Override
    public List<BooruPost> search(String tags, int limit, int page) throws Exception {
        String url = BASE + "/index.php?page=dapi&s=post&q=index&json=1&tags="
                + BooruProvider.encodeTagQuery(tags) + "&limit=" + limit + "&pid=" + page;
        return parse(om.readTree(BooruProvider.fetchJson(http, url, name())));
    }

    @Override
    public BooruPost findById(long postId) throws Exception {
        List<BooruPost> posts = search("id:" + postId, 1, 0);
        if (posts.isEmpty()) {
            throw new RuntimeException("safebooru 帖子不存在: " + postId);
        }
        // 闸 2 语义：重查必须确认拿回的就是这一帖（上游忽略 id: 谓词时会错归到首条）
        BooruPost post = posts.get(0);
        if (post.postId() != postId) {
            throw new RuntimeException("safebooru 重查未命中帖 " + postId + "，实际返回 " + post.postId());
        }
        return post;
    }

    private List<BooruPost> parse(JsonNode arr) {
        List<BooruPost> posts = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            // dapi 无结果/限流时返回对象包装，视为空结果交由服务层降级，不抛
            return posts;
        }
        for (JsonNode n : arr) {
            long id = n.path("id").asLong();
            String dir = n.path("directory").asText("");
            String image = n.path("image").asText("");
            if (id <= 0 || dir.isBlank() || image.isBlank()) {
                continue;
            }
            String fileUrl = BASE + "/images/" + dir + "/" + image;
            // 相对串补齐为同域绝对 URL，否则 Task 11 的 https 白名单闸会把主源结果全量误拒
            String preview = BooruProvider.toAbsoluteUrl(BASE, n.path("preview_url").asText(""), fileUrl);
            posts.add(new BooruPost(name(), id, preview, fileUrl,
                    n.path("tags").asText(""), n.path("rating").asText(""),
                    n.path("width").asInt(), n.path("height").asInt(),
                    BASE + "/index.php?page=post&s=view&id=" + id));
        }
        return posts;
    }
}
