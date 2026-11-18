package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S4 网搜入库：三道闸（host 白名单+https / ingest 服务端重查 / 下载护栏）+ 中文转 tag 降级。
 */
class BooruSearchServiceTest {

    BooruProvider provider = mock(BooruProvider.class);
    StorageService storage = mock(StorageService.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    ModelService modelService = mock(ModelService.class);
    HttpClient http = mock(HttpClient.class);
    BooruSearchService service;

    private static final BooruPost POST = new BooruPost("safebooru", 123,
            "https://safebooru.org/samples/aa/sample_bb.jpg", "https://safebooru.org/images/aa/bb.png",
            "1girl hatsune_miku", "safe", 800, 600,
            "https://safebooru.org/index.php?page=post&s=view&id=123");

    @BeforeEach
    void setUp() {
        when(provider.name()).thenReturn("safebooru");
        service = new BooruSearchService(List.of(provider), storage, assetRepo, userRepo, modelService, http,
                "safebooru.org,safebooru.donmai.us", "safebooru");
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    /** ChatClient 用分层 mock（非 RETURNS_DEEP_STUBS：深桩会把整链中间调用计入 verify 并让 captor 混入 null） */
    private void stubLlm(String response) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(modelService.getChatClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);
    }

    @Test
    void validateUrlRejectsNonWhitelistHttpAndRedirectProneForms() {
        Set<String> hosts = Set.of("safebooru.org");
        assertDoesNotThrow(() -> BooruSearchService.validateUrl("https://safebooru.org/images/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("https://evil.com/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("http://safebooru.org/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("https://safebooru.org.evil.com/a.png", hosts));
        assertThrows(RuntimeException.class, () -> BooruSearchService.validateUrl("not-a-url", hosts));
    }

    /**
     * 三处同步核验（Task 10 台账 → Task 12 前置核验收口）：donmai 系现代实例的图片实际托管在
     * cdn.donmai.us，适配器会产出该域的 file_url，因此 yml 的 allowed-hosts 必须同时列它。
     * 只改一侧的后果：放宽适配器 → 服务层闸把结果丢掉；放宽 yml → 适配器早筛先丢掉（整源落空且不报错）。
     * 本例走真实构造路径（把 yml 原串直接喂给 service），而不是在这里重演一遍 split/trim，否则测的是副本。
     */
    @Test
    void yamlAllowedHostsActuallyAcceptTheCdnUrlsProvidersEmit() throws Exception {
        String raw = yamlProperty("video.assets.booru.allowed-hosts");
        assertNotNull(raw, "application.yml 缺 video.assets.booru.allowed-hosts");
        BooruProvider donmai = mock(BooruProvider.class);
        when(donmai.name()).thenReturn("danbooru-safe");
        BooruSearchService fromYaml = new BooruSearchService(List.of(donmai), storage, assetRepo, userRepo,
                modelService, http, raw, "danbooru-safe");
        // 与 BooruProviderTest.danbooruCdnSubdomain... 同一形状的 URL：一侧改了另一侧没改就红在这里
        BooruPost cdn = new BooruPost("danbooru-safe", 601,
                "https://cdn.donmai.us/sample/7f/90/aa.jpg", "https://cdn.donmai.us/original/7f/90/aa.jpg",
                "1girl hatsune_miku", "g", 1920, 1080, "https://safebooru.donmai.us/posts/601");
        when(donmai.search(eq("t"), eq(20), eq(0))).thenReturn(List.of(cdn));
        assertEquals(1, fromYaml.search("t", "danbooru-safe", 0).size(),
                "yml allowed-hosts=" + raw + "（cdn.donmai.us 未列入则备源整源落空）");
    }

    /** @Value 默认值与 yml 是同一份清单的两个副本：yml 被删/被 profile 覆盖时走这里，不得落后于 yml */
    @Test
    void ctorValueDefaultListsAtLeastWhatYamlLists() throws Exception {
        String yaml = yamlProperty("video.assets.booru.allowed-hosts");
        // 键被删/改名正是本例最该抓的场景：不先判空就会在 yaml.split 处报 NPE，写好的诊断消息反而输不出来
        assertNotNull(yaml, "application.yml 里的 allowed-hosts 键消失（改名或删除）");
        // 按「哪个形参的 @Value 键名是 allowed-hosts」定位，不按形参下标：下标在构造器重排后会指错参数，
        // 失败信息看上去像「yml 变了」，实则测的是另一个默认值（审查 S-2）
        String fallback = null;
        var ctors = BooruSearchService.class.getConstructors();
        assertEquals(1, ctors.length, "出现多个 public 构造器，按 [0] 取会不稳定（JLS 不保证顺序）");
        for (java.lang.annotation.Annotation[] ps : ctors[0].getParameterAnnotations()) {
            for (java.lang.annotation.Annotation a : ps) {
                if (a instanceof Value v && v.value().contains("allowed-hosts:")) {
                    String s = v.value();
                    int colon = s.indexOf(':');
                    if (colon >= 0) {
                        fallback = s.substring(colon + 1);
                        // 削掉占位符的收尾花括号：${key:a,b} 的默认值段以 } 结束
                        fallback = fallback.endsWith("}") ? fallback.substring(0, fallback.length() - 1) : fallback;
                    }
                }
            }
        }
        assertNotNull(fallback, "没有任何形参带 allowed-hosts 的 @Value（键名改了？）");
        assertTrue(fallback.replace(" ", "").contains("cdn.donmai.us"),
                "@Value 默认值落后于 yml: " + fallback);
        for (String h : yaml.split(",")) {
            String v = h.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) {
                assertTrue(fallback.toLowerCase(Locale.ROOT).contains(v), "@Value 默认值缺 yml 项: " + v);
            }
        }
    }

    /**
     * 语义边界（审查 S-3）：这里读的是 base application.yml 的 **shipped default**，不是运行时生效值——
     * profile（application-h2.yml 等）里的覆盖不会红。本例要钉的是「默认发货配置自带 cdn 域」。
     */
    private static String yamlProperty(String key) throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env.getProperty(key);
    }

    @Test
    void searchFiltersUnsafeRatingAndNonWhitelistHost() throws Exception {
        BooruPost unsafe = new BooruPost("safebooru", 1, "https://safebooru.org/p.jpg",
                "https://safebooru.org/f.jpg", "t", "explicit", 1, 1, "https://safebooru.org/index.php?page=post&s=view&id=1");
        BooruPost evilHost = new BooruPost("safebooru", 2, "https://evil.com/p.jpg",
                "https://evil.com/f.jpg", "t", "safe", 1, 1, "https://safebooru.org/index.php?page=post&s=view&id=2");
        when(provider.search(eq("hatsune_miku"), eq(20), eq(0))).thenReturn(List.of(POST, unsafe, evilHost));
        var result = service.search("hatsune_miku", "safebooru", 0);
        assertEquals(1, result.size());
        assertEquals(123, result.get(0).postId());
        // spec §4.1 只对中文走翻译：非 CJK 查询必须零 LLM 调用（否则每次搜索白打一次模型，token+延迟+把原串塞进 prompt）
        verifyNoInteractions(modelService);
    }

    /** 删掉 CJK 区间判据后本例必红（上一例的零 LLM 断言拦不住中文路径被误拆） */
    @Test
    void cjkQueryIsTheOnlyOneThatTouchesTheLlm() throws Exception {
        stubLlm("hatsune_miku");
        when(provider.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        service.search("初音未来", "safebooru", 0);
        verify(modelService).getChatClient();
    }

    /** LLM 常无视「只输出 tag」：围栏/语言标记/逗号必须归一为 + 分隔，否则逗号被 donmai 当 OR、围栏字符变未知 tag */
    @Test
    void translatedTagsAreSanitizedToFenceFreePlusSeparated() throws Exception {
        stubLlm("```json\n1girl, hatsune_miku\n```");
        when(provider.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        service.search("初音未来", "safebooru", 0);
        verify(provider).search(eq("1girl+hatsune_miku"), eq(20), eq(0));
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("```json\n1girl, hatsune_miku\n```"));
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("1girl hatsune_miku"));
        // + 必须当分隔符而不是删掉：这行是 system prompt 要求的输出形态，合并后变成不存在的 tag、检索恒空
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("1girl+hatsune_miku"));
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("1girl,hatsune_miku"));
        assertEquals("hatsune_miku", BooruSearchService.sanitizeTags("\"Hatsune_Miku\""));
        // 分隔符类必须与 limit() 同口径：U+00A0/U+3000 不列进 split 就会被后面的字符白名单直接删掉，
        // 两个 tag 粘连成一个假 tag（Task 11 复审 N-1 同一失效机制，只是触发字符不同，复审 N-6）
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("1girl\u00A0hatsune_miku"));
        assertEquals("1girl+hatsune_miku", BooruSearchService.sanitizeTags("1girl\u3000hatsune_miku"));
        assertEquals("", BooruSearchService.sanitizeTags("```\n```"));
    }

    /** limit() 的两件事各自都要钉住：先折叠空白（远端串要进 AssetMatch 的 prompt 行），再按列宽截断 */
    @Test
    void limitCollapsesWhitespaceThenTruncates() {
        assertEquals("a b c d", BooruSearchService.limit("a\nb\tc  d", 500));
        assertEquals("abcde", BooruSearchService.limit("  abcdefgh  ", 5));
        assertNull(BooruSearchService.limit(null, 5));
        assertEquals("a b", BooruSearchService.limit("a b", 5));
        // Java 的 \\s 只有 ASCII 空白：远端 tag 里的 ESC/NUL（控制符）与 U+00A0/U+3000（LLM 与中文场景常吐）
        // 不折掉会原样入 DB，再污染下一镜的 AssetMatch prompt（审查 S-4）
        assertEquals("a b c", BooruSearchService.limit("a\u0000b\u001Bc", 500));
        assertEquals("a b", BooruSearchService.limit("a\u00A0\u3000b", 500));
    }

    /** 白名单配置侧 trim + 大小写归一：yml 写成带空格/大写域名不得让整源静默落空 */
    @Test
    void hostWhitelistIsTrimmedAndCaseInsensitive() throws Exception {
        BooruSearchService spaced = new BooruSearchService(List.of(provider), storage, assetRepo, userRepo,
                modelService, http, " SAFEBOORU.ORG , safebooru.donmai.us ", "safebooru");
        BooruPost upper = new BooruPost("safebooru", 9, "https://SAFEBOORU.ORG/p.jpg",
                "https://safebooru.org/f.jpg", "t", "safe", 1, 1, "https://safebooru.org/x");
        when(provider.search(eq("t"), eq(20), eq(0))).thenReturn(List.of(upper));
        assertEquals(1, spaced.search("t", "safebooru", 0).size());
    }

    /** search 侧 fileUrl 与 previewUrl 各自单独过闸：一黑一白也必须整条丢弃 */
    @Test
    void searchRejectsPostWhenEitherUrlEscapesWhitelist() throws Exception {
        BooruPost previewEvil = new BooruPost("safebooru", 8, "https://safebooru.org/f.jpg",
                "https://evil.com/p.jpg", "t", "safe", 1, 1, "https://safebooru.org/x");
        when(provider.search(eq("t"), eq(20), eq(0))).thenReturn(List.of(previewEvil, POST));
        var r = service.search("t", "safebooru", 0);
        assertEquals(1, r.size());
        assertEquals(123, r.get(0).postId());
    }

    /** rating 判据的 trim/大小写/null 三分支：null 必须走 fail-closed（Set.of().contains(null) 会抛 NPE） */
    @Test
    void ratingComparisonIsTrimmedCaseInsensitiveAndFailClosedOnNull() throws Exception {
        BooruPost padded = new BooruPost("safebooru", 10, "https://safebooru.org/p.jpg",
                "https://safebooru.org/f.jpg", "t", " SAFE ", 1, 1, "https://safebooru.org/x");
        BooruPost nullRating = new BooruPost("safebooru", 11, "https://safebooru.org/p.jpg",
                "https://safebooru.org/f.jpg", "t", null, 1, 1, "https://safebooru.org/x");
        when(provider.search(eq("t"), eq(20), eq(0))).thenReturn(List.of(padded, nullRating));
        var r = service.search("t", "safebooru", 0);
        assertEquals(1, r.size());
        assertEquals(10, r.get(0).postId());
    }

    /** 闸 3 本体：非 200 一律拒。NEVER 重定向策略下 3xx 回到应用层，这一行是「禁重定向」唯一执行点 */
    @Test @SuppressWarnings("unchecked")
    void downloadRejectsNon200SoRedirectsCannotEscapeWhitelist() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> moved = mock(HttpResponse.class);
        when(moved.statusCode()).thenReturn(302);
        when(moved.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(moved.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(moved);
        BooruSearchService.RejectException e = assertThrows(BooruSearchService.RejectException.class,
                () -> service.preview("safebooru", 123));
        // 判据必须钉在「302 被拒」这一条，而不是「反正抛了个异常」：删掉非 200 检查后流程会继续过
        // Content-Type/体积闸并把 1 字节当成图片返回（本例的 moved 就为此准备）。
        // 原来这里写的是 verifyNoInteractions(storage)，而 preview 路径从不引用 storage（最终审查 M-1）。
        assertTrue(e.getMessage().startsWith("下载响应异常"), () -> "实际: " + e.getMessage());
    }

    /** 下载请求本身必须带 UA（donmai 无 UA 直 403）+ 30s 读取超时；
     *  体积上限不在这里验：JDK 17 的 HttpRequest 无任何体积 accessor，上限在 readCapped 的读取循环里 */
    @Test @SuppressWarnings("unchecked")
    void downloadRequestCarriesUaAndRequestTimeout() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);
        service.preview("safebooru", 123);
        var cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofInputStream()));
        assertEquals("spring-ai-demo-asset-library", cap.getValue().headers().firstValue("User-Agent").orElseThrow());
        assertEquals(Duration.ofSeconds(30), cap.getValue().timeout().orElseThrow());
    }

    /** 入库字段长度收口 + 段序：远端 720 字符 tag 不得撞破列宽，被截的只能是 tags 尾部而非出处/分级 */
    @Test @SuppressWarnings("unchecked")
    void ingestTruncatesRemoteTextAndKeepsProvenanceFirst() throws Exception {
        BooruPost chatty = new BooruPost("safebooru", 123, "https://safebooru.org/s.jpg",
                "https://safebooru.org/f.png", "hatsune_miku ".repeat(60), "safe", 800, 600,
                "https://safebooru.org/view?id=123");
        when(provider.findById(123)).thenReturn(chatty);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);
        when(storage.upload(any(), anyString(), eq("image/png"))).thenReturn("bf1");
        when(storage.getAccessUrl("bf1")).thenReturn("/images/bf1.png");

        MediaAsset a = service.ingest("safebooru", 123, "CHARACTER", null, null);
        assertTrue(a.getTags().length() <= 500, "tags 必须按列宽截断，否则 save 抛 DataIntegrityViolation 并把 SQL 泄给前端");
        assertTrue(a.getSourceNote().length() <= 500);
        assertTrue(a.getSourceNote().startsWith("https://safebooru.org/view?id=123 | rating=safe"),
                () -> "出处与分级必须在前：" + a.getSourceNote());
        // name 回落不取整串 tag（它既是 UI 标题也是 AssetMatch 的 prompt 行）
        assertEquals("booru-123", a.getName());
        assertEquals("/images/bf1.png", a.getUrl());
    }

    /** 写库失败必须回收已落盘字节，否则 uploads 目录留孤儿文件 */
    @Test @SuppressWarnings("unchecked")
    void ingestFailureDeletesUploadedFileToAvoidOrphan() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);
        when(storage.upload(any(), anyString(), eq("image/png"))).thenReturn("bf9");
        when(assetRepo.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));
        verify(storage).delete("bf9");
    }

    @Test
    void danbooruRatingCodingIsAlsoAccepted() throws Exception {
        // 双源 rating 编码不同（safebooru=safe / donmai=g）：复核若只认 "safe"，备源会整源落空。
        // 实测修正（2026-11-18 Task 11）：计划原版只注了一个 name()="safebooru" 的 mock 却查 "danbooru-safe"，
        // resolveProvider 必抛「未知 booru 源」；此处按生产形态（两个 @Component 同时入 List）重建 service
        BooruProvider donmai = mock(BooruProvider.class);
        when(donmai.name()).thenReturn("danbooru-safe");
        BooruSearchService twoSource = new BooruSearchService(List.of(provider, donmai), storage, assetRepo,
                userRepo, modelService, http, "safebooru.org,safebooru.donmai.us", "safebooru");
        BooruPost gPost = new BooruPost("danbooru-safe", 3, "https://safebooru.donmai.us/p.jpg",
                "https://safebooru.donmai.us/f.jpg", "t", "g", 1, 1, "https://safebooru.donmai.us/posts/3");
        BooruPost qPost = new BooruPost("danbooru-safe", 4, "https://safebooru.donmai.us/p2.jpg",
                "https://safebooru.donmai.us/f2.jpg", "t", "q", 1, 1, "https://safebooru.donmai.us/posts/4");
        when(donmai.search(eq("hatsune_miku"), eq(20), eq(0))).thenReturn(List.of(gPost, qPost));
        var result = twoSource.search("hatsune_miku", "danbooru-safe", 0);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0).postId());
    }

    /**
     * 实测（Task 12 浏览器验证仍到了真数据）：safebooru.org 的 dapi 返的是 rating:"general"，
     * 不是 spec 里写的 safe——而 donmai 返单字母 g。只认 {safe,g} 时主源整源落空且不报错（搜索成功、空列表）。
     * 三分支都得认，但 questionable/explicit/q/e 仍必须拒（分级闸的判据只能由真实响应形态定）。
     */
    @Test
    void safebooruLongFormGeneralRatingIsTheRealSiteVocabulary() throws Exception {
        BooruPost general = new BooruPost("safebooru", 7320795,
                "https://safebooru.org/thumbnails/602/thumbnail_a.jpg", "https://safebooru.org/images/602/a.jpg",
                "1girl hatsune_miku", "general", 1424, 2048, "https://safebooru.org/index.php?page=post&s=view&id=7320795");
        BooruPost questionable = new BooruPost("safebooru", 2, "https://safebooru.org/t2.jpg",
                "https://safebooru.org/f2.jpg", "t", "questionable", 1, 1, "https://safebooru.org/x");
        BooruPost explicitE = new BooruPost("safebooru", 3, "https://safebooru.org/t3.jpg",
                "https://safebooru.org/f3.jpg", "t", "e", 1, 1, "https://safebooru.org/x");
        when(provider.search(eq("hatsune_miku"), eq(20), eq(0))).thenReturn(List.of(general, questionable, explicitE));
        var r = service.search("hatsune_miku", "safebooru", 0);
        assertEquals(1, r.size(), "general 必须存活、questionable/e 必须拒: " + r);
        assertEquals(7320795, r.get(0).postId());
    }

    @Test
    void chineseQueryTranslatesViaLlmWithFallback() throws Exception {
        stubLlm("hatsune_miku");
        when(provider.search(eq("hatsune_miku"), anyInt(), anyInt())).thenReturn(List.of(POST));
        assertEquals(1, service.search("初音未来", "safebooru", 0).size());
        // LLM 挂 → 降级原文下划线化直查，不报错
        when(modelService.getChatClient()).thenThrow(new RuntimeException("LLM 不可用"));
        when(provider.search(eq("初音未来"), anyInt(), anyInt())).thenReturn(List.of());
        assertTrue(service.search("初音未来", "safebooru", 0).isEmpty());
    }

    @Test @SuppressWarnings("unchecked")
    void ingestRequeriesProviderAndWritesSourceNote() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);
        when(storage.upload(any(), anyString(), eq("image/png"))).thenReturn("bf1");
        when(storage.getAccessUrl("bf1")).thenReturn("/images/bf1.png");

        MediaAsset a = service.ingest("safebooru", 123, "CHARACTER", "初音", null);
        assertEquals("IMAGE", a.getKind());
        assertEquals("CHARACTER", a.getCategory());
        assertEquals("bf1", a.getFileId());
        assertTrue(a.getSourceNote().contains("https://safebooru.org/index.php?page=post&s=view&id=123"));
        assertTrue(a.getSourceNote().contains("hatsune_miku"));
        verify(provider).findById(123); // 闸 2：服务端重查，不信任客户端 URL
        // 红线硬断言（Task 10 审查 S-4）：外部 fileUrl 绝不可进 MediaAsset.url
        assertEquals("/images/bf1.png", a.getUrl());
        assertFalse(a.getUrl().contains("safebooru"), "url 必须是自家存储访问路径，不是外链直链");
    }

    /**
     * 写侧格式清单必须跟读侧嗅探同一份（复审 N-1）：GIF 帖在两源很常见，而 contentType 在
     * oss/minio/fastdfs 会写进对象元数据——不列 .gif 就会把动图标成 JPEG。
     * local 存储不报错（LocalStorageService 不读 contentType 参数），所以只有这一例能抖出漂移。
     */
    @Test @SuppressWarnings("unchecked")
    void ingestMapsGifExtensionToImageGifNotJpeg() throws Exception {
        BooruPost gifPost = new BooruPost("safebooru", 808,
                "https://safebooru.org/samples/602/sample_x.gif", "https://safebooru.org/images/602/x.gif",
                "1girl animated", "general", 800, 600, "https://safebooru.org/index.php?page=post&s=view&id=808");
        when(provider.findById(808)).thenReturn(gifPost);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/gif")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{'G', 'I', 'F', '8'}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);
        when(storage.upload(any(), anyString(), eq("image/gif"))).thenReturn("bg1");
        when(storage.getAccessUrl("bg1")).thenReturn("/images/bg1.gif");

        MediaAsset a = service.ingest("safebooru", 808, "SCENE", "动图", null);
        var nameCap = ArgumentCaptor.forClass(String.class);
        var ctCap = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(any(), nameCap.capture(), ctCap.capture());
        assertTrue(nameCap.getValue().endsWith(".gif"), "存储文件名后缀丢了真实格式: " + nameCap.getValue());
        assertEquals("image/gif", ctCap.getValue(), "contentType 不得兜底成 jpeg（oss/minio 会把它写进对象元数据）");
        assertTrue(a.getUrl().endsWith(".gif"), a.getUrl());
    }

    @Test
    void ingestRejectsInsertCategory() {
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "INSERT", "x", null));
    }

    @Test @SuppressWarnings("unchecked")
    void downloadGuardRejectsOversizeAndNonImage() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> big = mock(HttpResponse.class);
        when(big.statusCode()).thenReturn(200);
        when(big.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        when(big.body()).thenReturn(new ByteArrayInputStream(new byte[20 * 1024 * 1024 + 1]));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(big);
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));

        HttpResponse<InputStream> html = mock(HttpResponse.class);
        when(html.statusCode()).thenReturn(200);
        when(html.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")), (a, b) -> true));
        when(html.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(html);
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));
    }

    /** 超限必须在读取循环里就断掉，而不是「先全量入堆再比长度」（Task 11 审查 Important#2）。
     *  故意用「有限但超限」的载荷而不是无限流：守卫被删时本例自己变红（返回了字节），
     *  而不是把整批测试连坐成 fork 进程 OOM（复审 N-4） */
    @Test
    void oversizeStreamIsAbortedWhileReadingNotAfterBuffering() throws Exception {
        int max = 1024 * 1024;
        byte[] oversize = new byte[max + 16 * 1024];
        assertThrows(BooruSearchService.RejectException.class,
                () -> BooruSearchService.readCapped(new ByteArrayInputStream(oversize), max));
        assertThrows(BooruSearchService.RejectException.class,
                () -> BooruSearchService.readCapped(new ByteArrayInputStream(new byte[0]), 1024));
        assertThrows(BooruSearchService.RejectException.class,
                () -> BooruSearchService.readCapped(null, 1024));
        assertArrayEquals(new byte[]{1, 2}, BooruSearchService.readCapped(new ByteArrayInputStream(new byte[]{1, 2}), 1024));
    }

    @Test
    void pageCapAtFive() throws Exception {
        when(provider.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        service.search("x", "safebooru", 99);
        verify(provider).search(eq("x"), eq(20), eq(4)); // ≤5 页（0 基最大 4）
    }

    /**
     * 闸 3 的「30s 读取」在 ofInputStream 语义下不覆盖 body（HttpRequest.timeout 只保到响应头到达），
     *  trickle 源（每次几十字节）会无限占住请求线程。预算过期必须在读取循环里断（审查 W-1）。
     * 两参重载保留并委托同一判据：存量用例（含 readCapped(null)）不受影响。
     */
    @Test
    void readCappedHonoursTheDeadlineForSlowTrickle() throws Exception {
        byte[] payload = new byte[64 * 1024];
        // 预算已过期：字节完全合法、远未触体积上限也必须断。类型上两者同为 RejectException，
        // 只能靠消息前缀区分（否则删掉体积上限后本例仍绿）
        var slow = assertThrows(BooruSearchService.RejectException.class, () -> BooruSearchService.readCapped(
                new ByteArrayInputStream(payload), 1024 * 1024, System.nanoTime() - 1));
        assertTrue(slow.getMessage().startsWith("下载读取超时"), () -> "疑似走了另一条闸: " + slow.getMessage());
        // 预算充裕：正常读完
        assertEquals(payload.length, BooruSearchService.readCapped(
                new ByteArrayInputStream(payload), 1024 * 1024, System.nanoTime() + Duration.ofMinutes(5).toNanos()).length);
        // 体积上限仍然优先：预算没过期也不能超限，且必须报「超限」而不是「超时」
        var over = assertThrows(BooruSearchService.RejectException.class, () -> BooruSearchService.readCapped(
                new ByteArrayInputStream(new byte[2 * 1024 * 1024]), 1024 * 1024,
                System.nanoTime() + Duration.ofMinutes(5).toNanos()));
        assertTrue(over.getMessage().startsWith("图片大小超限"), () -> "两条闸的消息串了: " + over.getMessage());
    }

    /** 诚实的 Content-Length 先拒（省掉整段读取）；不老实的 chunked 由 readCapped 的上限兜 */
    @Test @SuppressWarnings("unchecked")
    void declaredContentLengthOverCapIsRejectedBeforeReading() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> huge = mock(HttpResponse.class);
        when(huge.statusCode()).thenReturn(200);
        when(huge.headers()).thenReturn(HttpHeaders.of(Map.of(
                "Content-Type", List.of("image/png"),
                "Content-Length", List.of(String.valueOf(20L * 1024 * 1024 + 1))), (a, b) -> true));
        when(huge.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(huge);
        var e = assertThrows(BooruSearchService.RejectException.class, () -> service.preview("safebooru", 123));
        // 不用 verifyNoInteractions(storage) 做证据：preview 全路径从不引用 storageService（那是 ingest 的闸），
        // 它永远为真。body 只有 1 字节不可能触体积上限，所以「声明体积超限」这个前缀是唯一能证明「在 read 之前就拒了」的证据
        assertTrue(e.getMessage().startsWith("声明体积超限"), () -> "疑似走了读取分支: " + e.getMessage());
    }

    /** 预览代理同样过闸 1+2：服务端重查拿 previewUrl，非白名单 host 直接拒，绝不接受客户端传 URL */
    @Test @SuppressWarnings("unchecked")
    void previewRequeriesProviderAndValidatesHost() throws Exception {
        when(provider.findById(123)).thenReturn(POST);
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/jpeg")), (a, b) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[]{9, 9}));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(resp);

        byte[] bytes = service.preview("safebooru", 123);
        assertArrayEquals(new byte[]{9, 9}, bytes);
        verify(provider).findById(123);
        var cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(cap.capture(), eq(HttpResponse.BodyHandlers.ofInputStream()));
        assertEquals("https://safebooru.org/samples/aa/sample_bb.jpg", cap.getValue().uri().toString());

        // previewUrl 落到白名单外 → 拒绝且不发下载请求
        BooruPost evil = new BooruPost("safebooru", 7, "https://evil.com/p.jpg",
                "https://safebooru.org/images/aa/bb.png", "t", "safe", 1, 1, "https://safebooru.org/x");
        when(provider.findById(7)).thenReturn(evil);
        assertThrows(RuntimeException.class, () -> service.preview("safebooru", 7));
    }

    /** 未登录必须拒绝，且鉴权先于一切外部调用（否则素材会挂到错误 owner 下） */
    @Test
    void anonymousCallerIsRejected() throws Exception {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.search("x", "safebooru", 0));
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 123, "SCENE", "x", null));
        assertThrows(RuntimeException.class, () -> service.preview("safebooru", 123));
        verifyNoInteractions(http);
        // provider 在 setUp 里被 stub 过（name()），故用 never() 指名哪些方法不得被调，不用 verifyNoInteractions
        verify(provider, never()).search(anyString(), anyInt(), anyInt());
        verify(provider, never()).findById(anyLong());
    }

    /** 未知源名直接拒（含 null/空白回落 default-provider 的正向对照）；三入口必须同归 RejectException，
     *  否则 provider 写错时 /search 是 400、/ingest 与 /preview 是 500（复审 N-2 的误分类回归） */
    @Test
    void unknownProviderRejectedAndBlankFallsBackToDefault() throws Exception {
        assertThrows(BooruSearchService.RejectException.class, () -> service.search("x", "gelbooru", 0));
        assertThrows(BooruSearchService.RejectException.class, () -> service.ingest("gelbooru", 123, "SCENE", "x", null));
        assertThrows(BooruSearchService.RejectException.class, () -> service.preview("gelbooru", 123));
        when(provider.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        assertEquals(0, service.search("x", null, 0).size());
        verify(provider).search(eq("x"), eq(20), eq(0));
    }

    /**
     * 三入口都复核 rating（spec §4.2 验收矩阵）：显式帖不能绕过搜索闸直接 ingest/preview。
     * 关键时序：闸在取字节之前，一次 HTTP 都不该发、一个字节都不该入库。
     */
    @Test
    void ingestAndPreviewRecheckRatingBeforeAnyIo() throws Exception {
        BooruPost explicit = new BooruPost("safebooru", 55, "https://safebooru.org/samples/x.jpg",
                "https://safebooru.org/images/x.png", "t", "explicit", 1, 1,
                "https://safebooru.org/index.php?page=post&s=view&id=55");
        when(provider.findById(55)).thenReturn(explicit);
        assertThrows(RuntimeException.class, () -> service.ingest("safebooru", 55, "CHARACTER", "x", null));
        assertThrows(RuntimeException.class, () -> service.preview("safebooru", 55));
        verifyNoInteractions(http);
        verifyNoInteractions(storage);
    }

    /** 后缀只取最后一段路径内的部分：域名里的点不是后缀，无后缀/带 query 都回落 .jpg（防文件名注入） */
    @Test
    void extensionOfDefaultsToJpgAndStripsQuery() {
        assertEquals(".png", BooruSearchService.extensionOf("https://safebooru.org/images/a/bb.png"));
        // 带 query 的签名链：先剔 query 再取后缀，它确实是 png
        assertEquals(".png", BooruSearchService.extensionOf("https://safebooru.org/images/a/bb.png?token=1"));
        assertEquals(".jpg", BooruSearchService.extensionOf("https://safebooru.org/images/a/b"));
        assertEquals(".jpg", BooruSearchService.extensionOf("https://host.evil.com/a"));
        assertEquals(".webp", BooruSearchService.extensionOf("https://safebooru.org/a.WEBP"));
        // 后缀白名单：超长后缀与百分号编码片段不得拼进存储文件名（否则可带目录/控制字符）
        assertEquals(".jpg", BooruSearchService.extensionOf("https://safebooru.org/a.verylongext20"));
        assertEquals(".jpg", BooruSearchService.extensionOf("https://safebooru.org/a.png%2f..%2fetc"));
    }
}
