package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.BooruSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * booru 网搜三端点（spec §4.3）：搜索 / 预览代理 / 入库。
 * 响应形状镜像 MediaAssetController 的 {success, data|message}；
 * 分流比存量更严一档（有意偏离，见台账）：策略拒绝 RejectException → 400 + 原文可回前端，
 * 其余异常（上游/存储/DB）→ 500 + 固定文案，避免把 Hibernate 的 SQL 与列名泄给客户端；两类都留服务端日志。
 */
@RestController
@RequestMapping("/api/video/assets/booru")
public class BooruAssetController {

    private static final Logger log = LoggerFactory.getLogger(BooruAssetController.class);
    private static final String GENERIC_MESSAGE = "素材取用失败，请稍后重试或更换关键词";

    private final BooruSearchService booruSearchService;

    public BooruAssetController(BooruSearchService booruSearchService) {
        this.booruSearchService = booruSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q,
                                                      @RequestParam(required = false) String provider,
                                                      @RequestParam(defaultValue = "0") int page) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", booruSearchService.search(q, provider, page));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return failure(e, "search", "q=" + q + " provider=" + provider + " page=" + page);
        }
    }

    /** 缩略图代理：浏览器直连两源常失败，改走服务端过闸取字节。被拒时返空体（前端按加载失败处理） */
    @GetMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestParam String provider, @RequestParam long postId) {
        try {
            byte[] body = booruSearchService.preview(provider, postId);
            return ResponseEntity.ok().contentType(sniffImageType(body)).body(body);
        } catch (BooruSearchService.RejectException e) {
            log.warn("Booru preview rejected: provider={}, postId={}, reason={}", provider, postId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            log.error("Booru preview failed: provider={}, postId={}", provider, postId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", booruSearchService.ingest(
                    text(body.get("provider")),
                    parsePostId(body.get("postId")),
                    text(body.get("category")),
                    text(body.get("name")),
                    text(body.get("tags"))));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return failure(e, "ingest", "body=" + body.keySet());
        }
    }

    /**
     * 入参一律按字符串收口：JSON 里放数字/布尔时（name:789）直接强转会抛 ClassCastException，
     * 被下面的 failure() 归成 500 + 固定文案——客户端写错却报服务端故障（Task 12 S-15）。
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** postId 缺失/非法都是客户端可修正的 400：String.valueOf(null) 会得到字面 "null" 再撞 NumberFormatException */
    private static long parsePostId(Object raw) {
        if (raw == null) {
            throw new BooruSearchService.RejectException("缺少必填参数 postId");
        }
        String value = String.valueOf(raw).trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 回显必须截断：入参完全由请求体控制，整串拼进 400 响应就是一个响应体放大器
            throw new BooruSearchService.RejectException("postId 必须是数字: " + limit(value, 32));
        }
    }

    private static final MediaType IMAGE_WEBP = MediaType.parseMediaType("image/webp");

    /**
     * 魔数嗅探缩略图类型：源站返的 Content-Type 不可信，而硬编码 image/jpeg 会把 png/webp 谎报成 JPEG
     * （Task 11 记账项）。认不出的字节给八位字节流：宁可让前端走加载失败分支，也不继续谎报类型。
     */
    static MediaType sniffImageType(byte[] body) {
        if (body != null && body.length >= 12) {
            int b0 = body[0] & 0xFF, b1 = body[1] & 0xFF, b2 = body[2] & 0xFF, b3 = body[3] & 0xFF;
            if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') {
                return MediaType.IMAGE_PNG;
            }
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return MediaType.IMAGE_JPEG;
            }
            if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                    && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P') {
                return IMAGE_WEBP;
            }
            // GIF87a/GIF89a 共用前缀 GIF8：两源都有大量动图帖，落到下面的兜底分支会把能显示的图变成不显示
            if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') {
                return MediaType.IMAGE_GIF;
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 闸拒与内部故障分流：前者 message 面向用户可透传，后者只回固定文案（原文进日志，不进响应） */
    private ResponseEntity<Map<String, Object>> failure(RuntimeException e, String op, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        if (e instanceof BooruSearchService.RejectException) {
            log.warn("Booru {} rejected: {} ({})", op, detail, e.getMessage());
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
        // 到这里只剩上游/存储/DB 故障：宁可少说，不可把细节外泄（入参错误已在 text()/parsePostId() 收成 RejectException）
        log.error("Booru {} failed: {}", op, detail, e);
        result.put("message", GENERIC_MESSAGE);
        return ResponseEntity.internalServerError().body(result);
    }
}
