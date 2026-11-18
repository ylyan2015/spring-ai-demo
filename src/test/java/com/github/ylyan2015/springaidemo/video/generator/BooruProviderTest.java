package com.github.ylyan2015.springaidemo.video.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.lang.reflect.Field;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BooruProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    /**
     * 先打完 HttpResponse 自身的桩，再打 http.send 的桩。
     * 计划原写法把建桩逻辑内联在 thenReturn(json(..)) 参数位，会在外层 when 未完成时开新桩 →
     * UnfinishedStubbingException（7 例同因，实测复现）。
     */
    @SuppressWarnings("unchecked")
    private void stubJson(HttpClient http, int status, String body) throws Exception {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(r);
    }

    @SuppressWarnings("unchecked")
    private void stubJson(HttpClient http, String body) throws Exception {
        stubJson(http, 200, body);
    }

    @SuppressWarnings("unchecked")
    private HttpRequest sentRequest(HttpClient http) throws Exception {
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        return cap.getValue();
    }

    private String sentUri(HttpClient http) throws Exception {
        return sentRequest(http).uri().toString();
    }

    @Test
    void normalizeTagsLowercasesAndUnderscores() {
        assertEquals("hatsune_miku", BooruProvider.normalizeTags("  Hatsune Miku "));
        assertEquals("1girl", BooruProvider.normalizeTags("1girl"));
        assertEquals("", BooruProvider.normalizeTags(null));
    }

    @Test
    void encodeTagQueryKeepsPlusAsLiteralSeparator() {
        // booru AND 约定用 + 连接；整串 encode 会把 + 变成 %2B，服务端解成字面加号 → 查询恒空
        assertEquals("1girl+hatsune_miku", BooruProvider.encodeTagQuery("1girl+hatsune_miku"));
        assertEquals("rating%3Ag", BooruProvider.encodeTagQuery("rating:g"));
        assertEquals("", BooruProvider.encodeTagQuery(null));
        assertEquals("", BooruProvider.encodeTagQuery("  "));
    }

    /** 闸 1 唯一代码落点：禁重定向 + 连接超时只存在于 newHttpClient，mock 注入路径永远碰不到 */
    @Test
    void productionClientDisablesRedirectsAndBoundsConnectTimeout() {
        HttpClient c = BooruProvider.newHttpClient();
        assertEquals(HttpClient.Redirect.NEVER, c.followRedirects());
        assertEquals(Optional.of(Duration.ofSeconds(10)), c.connectTimeout());
    }

    @Test @SuppressWarnings("unchecked")
    void safebooruOrgParsesDapiAndBuildsFileUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":123,\"directory\":\"aa\",\"image\":\"bb.png\",\"tags\":\"1girl hatsune_miku\","
                + "\"rating\":\"safe\",\"width\":800,\"height\":600,"
                + "\"preview_url\":\"https://safebooru.org/samples/aa/sample_bb.jpg\"}]");
        SafebooruOrgProvider p = new SafebooruOrgProvider(http, om);
        var posts = p.search("hatsune_miku", 20, 0);
        assertEquals(1, posts.size());
        BooruProvider.BooruPost post = posts.get(0);
        assertEquals("safebooru", post.provider());
        assertEquals(123, post.postId());
        assertEquals("https://safebooru.org/images/aa/bb.png", post.fileUrl());
        assertEquals("safe", post.rating());
        assertEquals("https://safebooru.org/samples/aa/sample_bb.jpg", post.previewUrl());
        assertEquals("https://safebooru.org/index.php?page=post&s=view&id=123", post.sourcePageUrl());
        assertEquals(800, post.width());
        assertEquals(600, post.height());
        assertEquals("1girl hatsune_miku", post.tags());
        String uri = sentUri(http);
        assertTrue(uri.startsWith("https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1"));
        assertTrue(uri.contains("tags=hatsune_miku") && uri.contains("limit=20") && uri.contains("pid=0"));
        // 请求级护栏：30s 读取超时 + 固定 UA（donmai 无 UA 直接 403）
        HttpRequest req = sentRequest(http);
        assertEquals(Optional.of(Duration.ofSeconds(30)), req.timeout());
        assertEquals("spring-ai-demo-asset-library", req.headers().firstValue("User-Agent").orElse(""));
    }

    @Test @SuppressWarnings("unchecked")
    void safebooruPreviewUrlMissingFallsBackToFileUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":1,\"directory\":\"aa\",\"image\":\"bb.png\",\"tags\":\"t\",\"rating\":\"safe\","
                + "\"width\":10,\"height\":10}]");
        BooruProvider.BooruPost post = new SafebooruOrgProvider(http, om).search("t", 20, 0).get(0);
        assertEquals(post.fileUrl(), post.previewUrl());
    }

    /** dapi 对主图只给 directory+image 两段，preview_url 极可能是相对串；不补齐就会被下游 https 白名单误拒 */
    @Test @SuppressWarnings("unchecked")
    void safebooruRelativePreviewUrlBecomesSameHostAbsolute() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":2,\"directory\":\"aa\",\"image\":\"bb.png\",\"rating\":\"safe\","
                + "\"preview_url\":\"samples/aa/preview_bb.jpg\"}]");
        BooruProvider.BooruPost post = new SafebooruOrgProvider(http, om).search("t", 20, 0).get(0);
        assertEquals("https://safebooru.org/samples/aa/preview_bb.jpg", post.previewUrl());

        HttpClient http2 = mock(HttpClient.class);
        stubJson(http2, "[{\"id\":3,\"directory\":\"aa\",\"image\":\"bb.png\",\"rating\":\"safe\","
                + "\"preview_url\":\"/samples/aa/p.jpg\"}]");
        assertEquals("https://safebooru.org/samples/aa/p.jpg",
                new SafebooruOrgProvider(http2, om).search("t", 20, 0).get(0).previewUrl());
    }

    @Test @SuppressWarnings("unchecked")
    void danbooruSafeForcesRatingGAndParsesPostsJson() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":456,\"file_url\":\"https://safebooru.donmai.us/data/cc.png\","
                + "\"preview_file_url\":\"https://safebooru.donmai.us/data/preview/cc.jpg\","
                + "\"tag_string\":\"1girl hatsune_miku\",\"rating\":\"g\",\"image_width\":800,\"image_height\":600}]");
        DanbooruSafeProvider p = new DanbooruSafeProvider(http, om);
        var posts = p.search("hatsune_miku", 20, 0);
        assertEquals("https://safebooru.donmai.us/data/cc.png", posts.get(0).fileUrl());
        assertEquals("g", posts.get(0).rating());
        assertEquals("https://safebooru.donmai.us/data/preview/cc.jpg", posts.get(0).previewUrl());
        assertEquals("danbooru-safe", posts.get(0).provider());
        assertEquals("https://safebooru.donmai.us/posts/456", posts.get(0).sourcePageUrl());
        assertEquals(800, posts.get(0).width());
        assertEquals(600, posts.get(0).height());
        assertEquals("1girl hatsune_miku", posts.get(0).tags());
        String uri = sentUri(http);
        assertTrue(uri.startsWith("https://safebooru.donmai.us/posts.json"));
        assertTrue(uri.contains("rating%3Ag"));   // 强制追加 rating:g
        assertTrue(uri.contains("page=1"));       // 0 基页码转 1 基
    }

    @Test @SuppressWarnings("unchecked")
    void danbooruNullTagsStillForcesRatingG() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[]");
        new DanbooruSafeProvider(http, om).search(null, 20, 0);
        String uri = sentUri(http);
        assertTrue(uri.contains("tags=rating%3Ag"), uri);   // 不得拼出字面 "null" tag
        assertFalse(uri.contains("null"), uri);
    }

    /**
     * 实测：donmai 系现代实例把图片放在 cdn.donmai.us（/original/xx/xx/md5.ext 与 /sample/...），
     * API 域与图片域分离。早筛若只认 https://safebooru.donmai.us/ 前缀，备源会**不报错而整源落空**
     * ——这是 Task 10 台账写给 Task 12 的收口前置核验。放宽必须与 yml allowed-hosts 同步（只改一侧等于没改）。
     */
    @Test @SuppressWarnings("unchecked")
    void danbooruCdnSubdomainFileUrlSurvivesWhileForeignHostStillDropped() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":601,\"file_url\":\"https://cdn.donmai.us/original/7f/90/aa.jpg\"," 
                + "\"preview_file_url\":\"https://cdn.donmai.us/sample/7f/90/aa.jpg\"," 
                + "\"tag_string\":\"1girl hatsune_miku\",\"rating\":\"g\",\"image_width\":1920,\"image_height\":1080},"
                // 后缀拼接伪装（注册域出现在 host 中间/末尾之前）必须仍被拒
                + "{\"id\":602,\"file_url\":\"https://cdn.donmai.us.evil.example/a.jpg\",\"rating\":\"g\"},"
                + "{\"id\":603,\"file_url\":\"https://donmai.us.evil.example/a.jpg\",\"rating\":\"g\"},"
                // 同域但明文 http：不得因为「注册域匹配」就放过降级协议
                + "{\"id\":604,\"file_url\":\"http://cdn.donmai.us/a.jpg\",\"rating\":\"g\"}]");
        var posts = new DanbooruSafeProvider(http, om).search("t", 20, 0);
        assertEquals(1, posts.size(), "仅 cdn.donmai.us 一条应存活: " + posts);
        BooruProvider.BooruPost post = posts.get(0);
        assertEquals(601, post.postId());
        assertEquals("https://cdn.donmai.us/original/7f/90/aa.jpg", post.fileUrl());
        assertEquals("https://cdn.donmai.us/sample/7f/90/aa.jpg", post.previewUrl());
        // 出处页固定指 API 域，不跟随 CDN 走（版权可追溯：sourceNote 里的链接必须能打开帖子页）
        assertEquals("https://safebooru.donmai.us/posts/601", post.sourcePageUrl());
    }

    /**
     * 两道闸的不对称是故意的（审查 S-1）：适配器早筛按「同注册域」而不是按白名单形状筛，
     * 因为它丢人是**静默**的，而服务层丢人会 WARN 并带上 host。根域 donmai.us 不在白名单里，
     * 但必须活到服务层——否则站点一旦把图片改回根域，用户看到的是「搜索成功、0 结果」而不是任何线索。
     */
    @Test @SuppressWarnings("unchecked")
    void danbooruRootDomainFileUrlSurvivesTheAdapterScreen() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":610,\"file_url\":\"https://donmai.us/data/x.png\",\"rating\":\"g\"}]");
        var posts = new DanbooruSafeProvider(http, om).search("t", 20, 0);
        assertEquals(1, posts.size(), "早筛不得比白名单更窄：宁可让带 WARN 的那一道拒");
        assertEquals("https://donmai.us/data/x.png", posts.get(0).fileUrl());
    }

    /** 全链路唯一采信远端 URL 的地方：跳域 file_url 必须在适配器就被丢掉，不下传给下载环节 */
    @Test @SuppressWarnings("unchecked")
    void danbooruOffHostFileUrlIsDropped() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":501,\"file_url\":\"https://evil.example/x.png\",\"rating\":\"g\"},"
                + "{\"id\":502,\"file_url\":\"http://safebooru.donmai.usdata/y.png\",\"rating\":\"g\"},"
                + "{\"id\":503,\"file_url\":\"https://safebooru.donmai.us/data/ok.png\",\"rating\":\"g\"}]");
        var posts = new DanbooruSafeProvider(http, om).search("t", 20, 0);
        assertEquals(1, posts.size());
        assertEquals(503, posts.get(0).postId());
    }

    @Test @SuppressWarnings("unchecked")
    void danbooruFindByIdParsesSingleObject() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "{\"id\":456,\"file_url\":\"https://safebooru.donmai.us/data/cc.png\","
                + "\"tag_string\":\"1girl\",\"rating\":\"g\",\"image_width\":800,\"image_height\":600}");
        BooruProvider.BooruPost post = new DanbooruSafeProvider(http, om).findById(456);
        assertEquals(456, post.postId());
        assertEquals("https://safebooru.donmai.us/data/cc.png", post.fileUrl());
        assertEquals("1girl", post.tags());
        assertTrue(sentUri(http).endsWith("/posts/456.json"));
    }

    @Test @SuppressWarnings("unchecked")
    void danbooruFindByIdThrowsWhenPostHasNoFile() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "{\"id\":457,\"file_url\":\"\",\"rating\":\"g\"}");
        assertThrows(RuntimeException.class, () -> new DanbooruSafeProvider(http, om).findById(457));
    }

    @Test @SuppressWarnings("unchecked")
    void findByIdUsesIdTagAndThrowsWhenMissing() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[]");
        SafebooruOrgProvider p = new SafebooruOrgProvider(http, om);
        assertThrows(RuntimeException.class, () -> p.findById(999));
        assertTrue(sentUri(http).contains("tags=id%3A999"));
    }

    /** 闸 2 语义：重查拿回的不是那一帖就必须失败，否则 sourceNote 会把别人的图记成此 id */
    @Test @SuppressWarnings("unchecked")
    void safebooruFindByIdRejectsDifferentPost() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":777,\"directory\":\"aa\",\"image\":\"other.png\",\"rating\":\"safe\"}]");
        SafebooruOrgProvider p = new SafebooruOrgProvider(http, om);
        RuntimeException e = assertThrows(RuntimeException.class, () -> p.findById(999));
        assertTrue(e.getMessage().contains("999"), e.getMessage());
    }

    @Test @SuppressWarnings("unchecked")
    void malformedEntriesAreSkippedNotFatal() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"id\":7,\"directory\":\"\",\"image\":\"x.png\",\"rating\":\"safe\"},"
                + "{\"id\":8,\"directory\":\"aa\",\"image\":\"ok.png\",\"rating\":\"safe\"}]");
        var posts = new SafebooruOrgProvider(http, om).search("t", 20, 0);
        assertEquals(1, posts.size());          // 无 directory 的脏条目丢弃，合法条目仍取回
        assertEquals(8, posts.get(0).postId());

        HttpClient http2 = mock(HttpClient.class);
        stubJson(http2, "[{\"id\":9,\"file_url\":\"\",\"rating\":\"g\"},"
                + "{\"id\":10,\"file_url\":\"https://safebooru.donmai.us/data/d.png\",\"rating\":\"g\"}]");
        var posts2 = new DanbooruSafeProvider(http2, om).search("t", 20, 0);
        assertEquals(1, posts2.size());
        assertEquals(10, posts2.get(0).postId());
    }

    /** fixture 必须是「对象包着一帖」的真实形态：若只喂标量字段，脏条目过滤会替 isArray 守卫挡下所有变异 */
    @Test @SuppressWarnings("unchecked")
    void nonArrayBodyYieldsEmptyListNotException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "{\"post\":{\"id\":5,\"directory\":\"aa\",\"image\":\"b.png\",\"rating\":\"safe\"}}");
        assertTrue(new SafebooruOrgProvider(http, om).search("nothing", 20, 0).isEmpty());

        HttpClient http2 = mock(HttpClient.class);
        stubJson(http2, "{\"post\":{\"id\":6,\"file_url\":\"https://safebooru.donmai.us/data/e.png\",\"rating\":\"g\"}}");
        assertTrue(new DanbooruSafeProvider(http2, om).search("nothing", 20, 0).isEmpty());
    }

    @Test @SuppressWarnings("unchecked")
    void non200Throws() throws Exception {
        HttpClient http = mock(HttpClient.class);
        // body 必须是可解析的合法载荷：否则 Jackson 对 null body 抛的 IAE 会冒充「状态码守卫生效」
        stubJson(http, 403, "[{\"id\":1,\"file_url\":\"https://safebooru.donmai.us/data/x.png\",\"rating\":\"g\"}]");
        assertThrows(RuntimeException.class, () -> new DanbooruSafeProvider(http, om).search("x", 20, 0));

        HttpClient http2 = mock(HttpClient.class);
        stubJson(http2, 500, "[{\"id\":2,\"directory\":\"aa\",\"image\":\"b.png\",\"rating\":\"safe\"}]");
        assertThrows(RuntimeException.class, () -> new SafebooruOrgProvider(http2, om).search("x", 20, 0));
    }

    /**
     * 真实 dapi 响应形态（Task 12 浏览器验证取回）：directory 是**数字**而不是 "aa" 这种字符串、
     * rating 是 "general"、width/height 是 1424x2048。夹具写成字符串目录会把真实形态漏掉，
     * 而数字目录一旦被当成 blank 过滤，主源同样是「搜索成功但 0 结果」。
     */
    @Test @SuppressWarnings("unchecked")
    void safebooruRealDapiShapeHasNumericDirectoryAndLongFormRating() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubJson(http, "[{\"preview_url\":\"https://safebooru.org/thumbnails/602/thumbnail_382d.jpg\","
                + "\"file_url\":\"https://safebooru.org/images/602/382d.jpg\",\"directory\":602,"
                + "\"width\":1424,\"height\":2048,\"id\":7320795,\"image\":\"382d.jpg\","
                + "\"rating\":\"general\",\"tags\":\"1girl hatsune_miku\"}]");
        var post = new SafebooruOrgProvider(http, om).search("hatsune_miku", 20, 0).get(0);
        assertEquals(7320795, post.postId());
        // 自拼的 fileUrl 与响应里的 file_url 必须同一目标
        assertEquals("https://safebooru.org/images/602/382d.jpg", post.fileUrl());
        assertEquals("general", post.rating());
        assertEquals("https://safebooru.org/thumbnails/602/thumbnail_382d.jpg", post.previewUrl());
        assertEquals(1424, post.width());
        assertEquals(2048, post.height());
    }

    /** 结构红线（spec §4.2.3 外链永不进生成链路）：适配器不得持有任何入库/存储/服务定位依赖 */
    @Test
    void adaptersNeverHoldStorageOrRepository() {
        List<String> banned = List.of("MediaAssetRepository", "MediaAssetService", "StorageService",
                "ApplicationContext", "BeanFactory", "ObjectProvider");
        for (Class<?> c : List.of(SafebooruOrgProvider.class, DanbooruSafeProvider.class)) {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    String type = f.getGenericType().getTypeName();   // 泛型擦除后的字段名也要能看见
                    for (String word : banned) {
                        assertFalse(type.contains(word),
                                c.getSimpleName() + " 字段 " + f.getName() + " 类型为 " + type
                                        + "：外部 fileUrl 只能经服务端重查+下载转存入库");
                    }
                }
            }
        }
    }
}
