package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * booru 网搜入库（spec §4）：三道闸 = host 白名单+https+禁重定向 / ingest 只认 provider+postId 重查 / 下载护栏。
 * 外链永不进生成链路：字节经 storageService.upload 转存，sourceNote 版权可追溯。
 * rating 复核覆盖搜索/预览/下载三入口（spec §4.2 验收矩阵），源返回的 rating 不可当作可信输入。
 */
@Service
public class BooruSearchService {

    private static final Logger log = LoggerFactory.getLogger(BooruSearchService.class);
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE = 4; // 0 基，≤5 页
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    /** 闸 3 的读取预算：既当 HttpRequest.timeout（只管到响应头），也当整段 body 的墙钟预算 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    /**
     * 两源 rating 编码不同，且 spec 写的不是站点实际返回的那一个（Task 12 浏览器验证取回真实响应）：
     * safebooru.org dapi = general（长式）、donmai 系 = g（短式）、Gelbooru 风格 = safe。
     * 只认 {safe, g} 时主源表现为「搜索成功、空列表」而不是报错，故三种安全写法都收；
     * questionable/explicit/q/e 一律拒（判据只能由真实响应形态定，不得按猜测扩张）。
     * 扩张时注意：**donmai 系的 s = sensitive（性暗示、无裸露），不是 safe**，绝对不能进这个集合；
     * 若将来把 yande.re/konachan 等 Gelbooru 分叉纳入 allowed-hosts，那边的 s 才是 safe 长式，需先核源站码表。
     */
    private static final Set<String> SAFE_RATINGS = Set.of("safe", "g", "general");
    /** MediaAsset 列宽（entity L21-L41），远端可控串写库前必须截断 */
    private static final int MAX_NAME = 200;
    private static final int MAX_TAGS = 500;
    private static final int MAX_SOURCE_NOTE = 500;

    private final Map<String, BooruProvider> providers = new LinkedHashMap<>();
    private final StorageService storageService;
    private final MediaAssetRepository assetRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;
    private final HttpClient http; // 禁重定向（BooruConfig 复用 BooruProvider.newHttpClient()）
    private final Set<String> allowedHosts;
    private final String defaultProvider;

    public BooruSearchService(List<BooruProvider> providerList, StorageService storageService,
                              MediaAssetRepository assetRepository, UserRepository userRepository,
                              ModelService modelService, HttpClient http,
                              @Value("${video.assets.booru.allowed-hosts:safebooru.org,safebooru.donmai.us,cdn.donmai.us}") String allowedHosts,
                              @Value("${video.assets.booru.default-provider:safebooru}") String defaultProvider) {
        providerList.forEach(p -> providers.put(p.name(), p));
        this.storageService = storageService;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
        this.http = http;
        // 逗号分隔配置逐项 trim + 小写：yml 里写成 "a, b" 时若不 trim，备源整源落空（不报错，静默丢结果）；
        // 小写后与 validateUrl 的 host 小写比较同口径（上游返大写域名不得整源落空）
        Set<String> hosts = new HashSet<>();
        Arrays.stream(allowedHosts.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).forEach(hosts::add);
        this.allowedHosts = hosts;
        this.defaultProvider = defaultProvider;
    }

    /**
     * 策略拒绝（白名单/分级/类型/体积/参数不合法/未登录）：语义上是客户端可修正的 4xx，
     * message 可安回前端；其余 RuntimeException 属上游/内部故障，由 controller 归 500 + 固定文案，
     * 不把 Hibernate/Jackson 异常原文（含 SQL 与列名）泄给前端（Task 11 审查 Important#1/Q3）。
     */
    public static class RejectException extends RuntimeException {
        public RejectException(String message) {
            super(message);
        }
    }

    /** 闸 1：https + host 精确匹配白名单（子域伪装拒） */
    static void validateUrl(String url, Set<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new RejectException("非法 URL: " + url);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || host == null || !allowedHosts.contains(host)) {
            throw new RejectException("URL 不在白名单或非 https: " + url);
        }
    }

    public List<BooruPost> search(String q, String provider, int page) {
        getCurrentUserId();
        BooruProvider p = resolveProvider(provider);
        String tags = toBooruTags(q);
        try {
            List<BooruPost> posts = p.search(tags, PAGE_SIZE, Math.max(0, Math.min(MAX_PAGE, page)));
            List<BooruPost> safe = new ArrayList<>();
            for (BooruPost post : posts) {
                if (!isSafeRating(post.rating())) {
                    continue; // 分级复核：非 safe/g 一律丢弃
                }
                try {
                    validateUrl(post.fileUrl(), allowedHosts);
                    validateUrl(post.previewUrl(), allowedHosts);
                } catch (RuntimeException e) {
                    log.warn("booru 结果 host 非白名单丢弃: {}", e.getMessage());
                    continue;
                }
                safe.add(post);
            }
            return safe;
        } catch (Exception e) {
            throw new RuntimeException("booru 搜索失败: " + e.getMessage(), e);
        }
    }

    /** 预览代理：服务端重查拿 previewUrl 过闸取字节（解决浏览器直连网络问题） */
    public byte[] preview(String provider, long postId) {
        getCurrentUserId();
        BooruPost post = requery(provider, postId);
        requireSafeRating(post);
        validateUrl(post.previewUrl(), allowedHosts);
        return guardedDownload(post.previewUrl());
    }

    public MediaAsset ingest(String provider, long postId, String category, String name, String tags) {
        Long userId = getCurrentUserId();
        if (!("CHARACTER".equals(category) || "SCENE".equals(category))) {
            throw new RejectException("参考素材 category 仅支持 CHARACTER/SCENE: " + category);
        }
        BooruPost post = requery(provider, postId); // 闸 2：不信任客户端 URL
        requireSafeRating(post);                    // 三入口都复核：显式帖不可绕过搜索闸直接入库
        validateUrl(post.fileUrl(), allowedHosts);
        byte[] bytes = guardedDownload(post.fileUrl());
        String ext = extensionOf(post.fileUrl());
        String contentType = switch (ext) {
            case ".png" -> "image/png";
            // 写侧格式清单必须跟读侧嗅探（BooruAssetController.sniffImageType）同一份：两源动图帖很多，
            // 不列 .gif 就把动图的 contentType 写成 JPEG（oss/minio/fastdfs 会落进对象元数据；local 存储不报错，所以很静）
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            default -> "image/jpeg";
        };
        String fileId = storageService.upload(new ByteArrayInputStream(bytes),
                "booru_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ext, contentType);
        try {
            MediaAsset a = new MediaAsset();
            a.setUserId(userId);
            // name 回落不取 post.tags() 整串：name 既是 UI 标题也是 AssetMatch 的 prompt 行，整串 tag 既难看又吃 token
            a.setName(limit(name == null || name.isBlank() ? "booru-" + post.postId() : name, MAX_NAME));
            a.setKind("IMAGE");
            a.setCategory(category);
            a.setFileId(fileId);
            a.setUrl(storageService.getAccessUrl(fileId));   // 红线：只允许自家存储访问路径，外链永不进实体
            a.setTags(limit(tags == null || tags.isBlank() ? post.tags() : tags, MAX_TAGS));
            // 段序：URL+rating 前置，长 tag 被截也不丢出处与分级（spec 验收矩阵「sourceNote 完整」）
            a.setSourceNote(limit(post.sourcePageUrl() + " | rating=" + post.rating()
                    + " | tags=" + post.tags(), MAX_SOURCE_NOTE));
            MediaAsset saved = assetRepository.save(a);
            log.info("Booru asset ingested: id={}, provider={}, postId={}, user={}", saved.getId(), provider, postId, userId);
            return saved;
        } catch (RuntimeException e) {
            // 写库失败必须补偿已落盘字节，否则 uploads 目录留孤儿文件（MediaAssetService 同款 deleteQuietly idiom）
            try {
                storageService.delete(fileId);
            } catch (Exception ignored) {
                log.warn("Booru ingest 失败后的孤儿文件清理失败: fileId={}", fileId);
            }
            throw e;
        }
    }

    // ==================== 内部方法 ====================

    private BooruProvider resolveProvider(String name) {
        BooruProvider p = providers.get(name == null || name.isBlank() ? defaultProvider : name);
        if (p == null) {
            throw new RejectException("未知 booru 源: " + name);
        }
        return p;
    }

    private BooruPost requery(String provider, long postId) {
        // resolveProvider 必须在包装范围外：它是客户端可修正的策略拒绝（provider 写错），
        // 一起包进 RuntimeException 会被 controller 误归 500，与 search 侧的 400 不一致（Task 11 复审 N-2）
        BooruProvider p = resolveProvider(provider);
        try {
            return p.findById(postId);
        } catch (Exception e) {
            throw new RuntimeException("booru 帖子重查失败: " + e.getMessage(), e);
        }
    }

    private static boolean isSafeRating(String rating) {
        return rating != null && SAFE_RATINGS.contains(rating.trim().toLowerCase(Locale.ROOT));
    }

    /** 预览/下载入口的分级复核：与搜索同一份判据，源返回的 rating 不当作可信输入 */
    private static void requireSafeRating(BooruPost post) {
        if (!isSafeRating(post.rating())) {
            throw new RejectException("帖子分级非 safe/g，拒绝取用: rating=" + post.rating() + " id=" + post.postId());
        }
    }

    /** 扩展名只取最后一段路径内的后缀，且限定 1-5 位字母数字；其余形态（无后缀/域名内点号/超长/含%）回落 .jpg */
    static String extensionOf(String url) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String ext = dot > slash ? path.substring(dot).toLowerCase(Locale.ROOT) : ".jpg";
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".jpg";
    }

    /**
     * 入库文本收口：折叠连续空白与控制符（远端 tag 串会进 AssetMatch prompt）+ 按列宽截断。
     * 只用 \\s 折叠不够：Java 的 \\s 只有 ASCII 空白，而 U+00A0（LLM 常吐的不换行空格）、U+3000（中文全角空格）与 ESC/NUL
     * 这些控制符都会原样入 DB，再污染下一镜的 AssetMatch prompt（审查 S-4）。
     * 不做长度约束时，donmai 常见 300-800 字符的 tag_string 会撞破 sourceNote/tags 的 500 列宽，
     * 而 save 失败会被 controller 归成 400 并把含 SQL 与列名的 Hibernate 原文回给前端。
     */
    static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String collapsed = value.replaceAll("[\\p{Cntrl}\\p{Zs}\\s]+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max);
    }

    /** 中文 → LLM 转 booru 英文 tag；失败降级原文下划线化直查（spec §4.1） */
    private String toBooruTags(String q) {
        String normalized = BooruProvider.normalizeTags(q);
        if (q != null && q.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
            try {
                String translated = modelService.getChatClient().prompt()
                        .system("把中文动漫角色/场景名转成 booru 英文 tag：小写、空格转下划线、多 tag 用 + 连接。只输出 tag 本身。")
                        .user(q).call().content();
                if (translated != null && !translated.isBlank()) {
                    String tags = sanitizeTags(translated);
                    if (!tags.isEmpty()) {
                        return tags;
                    }
                    log.warn("中文转 booru tag 返回无可用的 tag 串，降级原文直查");
                }
            } catch (Exception e) {
                log.warn("中文转 booru tag 失败，降级原文直查: {}", e.getMessage());
            }
        }
        return normalized;
    }

    /**
     * LLM 输出归一：剥 ``` 围栏及其语言标记、引号与逗号，空白与 + 统一成分隔符后以 + 重拼（booru 多 tag 的标准分隔，
     * encodeTagQuery 也按 + 分段）；直接用原文会让逗号被 donmai 解释成 OR、围栏字符变成未知 tag，整次检索静默落空。
     * 注意：+ 必须当分隔符而不是非法字符删除，否则最听话的那类输出「1girl+hatsune_miku」会被合并成假 tag。
     * 分隔符类与 limit() 同口径（含 \p{Zs}）：否则 U+00A0/U+3000 会先被下面的字符白名单删掉，
     * 两个 tag 粘连成一个不存在的 tag（与 Task 11 复审 N-1 同一失效机制）。
     */
    static String sanitizeTags(String raw) {
        String cleaned = raw.replaceAll("```[a-zA-Z]*", "")
                .replaceAll("[\"'`,;]", " ")
                .trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (String part : cleaned.split("[\\p{Cntrl}\\p{Zs}\\s+]+")) {
            String tag = part.replaceAll("[^a-z0-9_*()\\-]", "");
            if (tag.isEmpty() || "_".equals(tag)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(tag);
        }
        return sb.toString();
    }

    /**
     * 闸 3：10s 连接 + 30s 整段读取预算 + ≤20MB + Content-Type image/*；禁重定向（非 200 一律拒，3xx 不得逃逸白名单）。
     * 预算在建请求之前起算：HttpRequest.timeout 只保到「响应头到达」，body 阶段靠传下去的 deadline 兜——
     * trickle 源（每次几十字节）否则可以无限占住请求线程（Task 12 审查 W-1）。
     * 残留：中途彻底不吐字节的硬卡死仍靠 TCP/连接层自己断（真要钉死它需要看门狗线程 close 这条流）。
     */
    private byte[] guardedDownload(String url) {
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(READ_TIMEOUT)
                    .header("User-Agent", "spring-ai-demo-asset-library").GET().build();
            // 必须流式取体：JDK 17 的 HttpRequest.Builder 没有任何体积上限 API（limit/maxBodySize 均不存在），
            // 而 ofByteArray() 会把整个响应体先读进堆才轮到长度比较——这道闸唯一会主动触发的场景
            // （白名单主机返超大文件）恰好是它最伤的输入（Task 11 审查 Important#2）
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    // NEVER 重定向策略下 3xx 会回到应用层，这一行是「禁重定向」唯一的应用层执行点
                    throw new RejectException("下载响应异常: HTTP " + resp.statusCode());
                }
                String ct = resp.headers().firstValue("Content-Type").orElse("");
                if (!ct.startsWith("image/")) {
                    throw new RejectException("非图片 Content-Type: " + ct);
                }
                rejectIfDeclaredSizeTooBig(resp);
                return readCapped(in, MAX_BYTES, deadline);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("booru 图片下载失败: " + e.getMessage(), e);
        }
    }

    /** 诚实的 Content-Length 超限先拒（省掉整段读取）；不老实的 chunked 由 readCapped 的边读边计数兜 */
    private static void rejectIfDeclaredSizeTooBig(HttpResponse<?> resp) {
        long declared;
        try {
            declared = Long.parseLong(resp.headers().firstValue("Content-Length").orElse("-1").trim());
        } catch (NumberFormatException e) {
            // 畸形的声明值当「未声明」处理：解析失败不得成为绕过体积闸的路径
            declared = -1L;
        }
        if (declared > MAX_BYTES) {
            throw new RejectException("声明体积超限: " + declared + " > " + MAX_BYTES + " bytes");
        }
    }

    /** 边读边计数，超过 max 立即断——超限响应不会全量入堆；空体同样拒。
     *  仅测试入口：生产路径必须走带 deadline 的三参版，不然新写的下载链会悄悄丢掉读取预算（复审 W-1/N-10） */
    static byte[] readCapped(InputStream in, long max) throws IOException {
        return readCapped(in, max, System.nanoTime() + READ_TIMEOUT.toNanos());
    }

    /** 同一个上限判据再加一道墙钟：预算内没读完就断（trickle 源的 body 不受 HttpRequest.timeout 保护） */
    static byte[] readCapped(InputStream in, long max, long deadlineNanos) throws IOException {
        if (in == null) {
            throw new RejectException("下载响应无响应体");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (System.nanoTime() > deadlineNanos) {
                // 这不是客户端可修正错误，但依旧归 RejectException（重试指引靠消息文案而不是新开 504 分支）
                throw new RejectException("下载读取超时: >" + READ_TIMEOUT.toSeconds() + "s，请稍后重试该帖");
            }
            total += n;
            if (total > max) {
                throw new RejectException("图片大小超限: >" + max + " bytes");
            }
            bos.write(buf, 0, n);
        }
        byte[] body = bos.toByteArray();
        if (body.length == 0) {
            throw new RejectException("图片大小超限或为空: 0");
        }
        return body;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RejectException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RejectException("用户不存在"));
        return user.getId();
    }
}
