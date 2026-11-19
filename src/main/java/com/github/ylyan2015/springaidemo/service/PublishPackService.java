package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布包：把发布元数据渲染成「投稿页逐项粘贴」的纯文本，并负责落盘与 ZIP 附带。
 *
 * <p>三个入口对应三个使用场景：
 * <ul>
 *   <li>{@link #syncAfterExport} —— 高清导出成功后：落派生稿（人工值优先）+ 写 {@code final/<标题>-发布包.txt}</li>
 *   <li>{@link #packTextFor} —— 批量 ZIP 每条记录附一个 {@code NNN_发布包.txt}</li>
 *   <li>{@link #summaryFor} —— ZIP 清单里的「标题/标签数/简介字数/分区」四列</li>
 * </ul>
 *
 * <p>本类<b>任何失败都不抛</b>：视频是主产物，发布包是附属品，附属品写失败绝不能把出片改判成失败。
 * 一切读库都走免登录入口（{@code userId} 显式传入），因为调用方可能是导出 worker 线程。
 */
@Service
public class PublishPackService {

    private static final Logger log = LoggerFactory.getLogger(PublishPackService.class);

    /** 当前只有 B 站；平台扩展时这里换成参数而不是复制一份服务 */
    static final String PLATFORM = PublishSpec.PLATFORM_BILIBILI;
    /** uploadFinal 的 category 段，最终文件名形如 {@code 钱坑漫评01-发布包.txt} */
    static final String PACK_CATEGORY = "发布包";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StorageService storageService;
    private final PublishMetaService metaService;
    private final PublishMetaDeriver deriver;

    public PublishPackService(StorageService storageService, PublishMetaService metaService,
                              PublishMetaDeriver deriver) {
        this.storageService = storageService;
        this.metaService = metaService;
        this.deriver = deriver;
    }

    /** ZIP 清单一行要用的摘要 */
    public record Summary(String title, int tagCount, int descriptionChars, String categoryName, String source) {}

    /** 内部统一视图：库内人工值优先，缺的字段用派生草稿补 */
    private record View(String title, String categoryName, Integer categoryTid, List<String> tags,
                        String description, String pinnedComment, String declaration, String collection,
                        List<String> timestamps, Boolean approx, Integer ratioPercent, String ratioBasis,
                        String source) {}

    /**
     * 导出成功后的联动：派生 → 落库（人工值只补空）→ 写发布包文件。
     *
     * @return 发布包访问 URL；任何失败返回 null 并只记 warn（不影响出片）
     */
    public String syncAfterExport(Long scriptId, Long userId, String title) {
        try {
            Map<String, Object> draft = deriver.derive(scriptId, userId, PLATFORM);
            PublishMeta meta = metaService.upsertGenerated(scriptId, userId, PLATFORM, draft);
            String text = packText(fromRow(meta, draft));
            String fileId = storageService.uploadFinal(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    title, PACK_CATEGORY, ".txt", "text/plain");
            String url = storageService.getAccessUrl(fileId);
            log.info("Publish pack written for script {}: {} ({} chars)", scriptId, fileId,
                    text.codePointCount(0, text.length()));
            return url;
        } catch (Exception e) {
            log.warn("Publish pack skipped for script {} (export itself is fine): {}", scriptId, e.getMessage());
            return null;
        }
    }

    /** 发布包纯文本（ZIP 附带用；库里没人写过时直接用派生稿，不落库） */
    public String packTextFor(Long scriptId, Long userId) {
        return packText(view(scriptId, userId));
    }

    public Summary summaryFor(Long scriptId, Long userId) {
        View v = view(scriptId, userId);
        int descChars = v.description() == null ? 0 : v.description().codePointCount(0, v.description().length());
        return new Summary(v.title(), v.tags() == null ? 0 : v.tags().size(), descChars,
                v.categoryName(), v.source());
    }

    private View view(Long scriptId, Long userId) {
        Optional<PublishMeta> stored = metaService.findExisting(scriptId, userId, PLATFORM);
        if (stored.isPresent() && notBlank(stored.get().getTitle())) {
            return fromRow(stored.get(), Map.of());
        }
        return fromDraft(deriver.derive(scriptId, userId, PLATFORM));
    }

    private static View fromRow(PublishMeta m, Map<String, Object> draft) {
        return new View(
                pick(m.getTitle(), draft.get("title")),
                pick(m.getCategoryName(), draft.get("categoryName")),
                m.getCategoryTid() != null ? m.getCategoryTid() : intOrNull(draft.get("categoryTid")),
                m.getTags() != null && !m.getTags().isBlank()
                        ? PublishMetaService.splitCsv(m.getTags()) : tagsOrNull(draft.get("tags")),
                pick(m.getDescription(), draft.get("description")),
                m.getPinnedComment(),
                pick(m.getDeclaration(), draft.get("declaration")),
                m.getCollection(),
                m.getTimestampsCsv() != null && !m.getTimestampsCsv().isBlank()
                        ? List.of(m.getTimestampsCsv().split(",")) : stringsOrNull(draft.get("timestamps")),
                boolOrNull(draft.get("approxTimestamps")),
                intOrNull(draft.get("insertRatioPercent")),
                (String) draft.get("insertRatioBasis"),
                m.getSource() == null ? PublishMetaService.SOURCE_GENERATED : m.getSource());
    }

    private static View fromDraft(Map<String, Object> d) {
        return new View(
                (String) d.get("title"),
                (String) d.get("categoryName"),
                intOrNull(d.get("categoryTid")),
                tagsOrNull(d.get("tags")),
                (String) d.get("description"),
                null,
                (String) d.get("declaration"),
                null,
                stringsOrNull(d.get("timestamps")),
                boolOrNull(d.get("approxTimestamps")),
                intOrNull(d.get("insertRatioPercent")),
                (String) d.get("insertRatioBasis"),
                PublishMetaService.SOURCE_GENERATED);
    }

    /** 顺序 = 投稿页从上到下；机器代填的痕迹（来源、时间、口径）全部放在正文之后 */
    private static String packText(View v) {
        StringBuilder sb = new StringBuilder();
        line(sb, "【标题】", v.title());
        line(sb, "【分区】", v.categoryName() + (v.categoryTid() == null ? "" : "（tid=" + v.categoryTid() + "）"));
        line(sb, "【标签】", v.tags() == null || v.tags().isEmpty() ? "（无）" : String.join(", ", v.tags()));
        line(sb, "【简介】", v.description());
        line(sb, "【置顶评论】", notBlank(v.pinnedComment()) ? v.pinnedComment() : "（未设置）");
        line(sb, "【创作声明】", v.declaration());
        line(sb, "【合集】", notBlank(v.collection()) ? v.collection() : "（未设置）");
        if (v.timestamps() != null && !v.timestamps().isEmpty()) {
            line(sb, "【时间戳】", String.join(", ", v.timestamps())
                    + (Boolean.TRUE.equals(v.approx()) ? "（近似轴，发布前按成片核对）" : ""));
        }
        if (v.ratioPercent() != null) {
            line(sb, "【引用占比】", v.ratioPercent() + "%"
                    + (notBlank(v.ratioBasis()) ? "（" + v.ratioBasis() + "）" : ""));
        }
        sb.append("\n—— 来源 ")
                .append(PublishMetaService.SOURCE_MANUAL.equals(v.source()) ? "人工编辑" : "机器派生")
                .append("，派生于 ").append(LocalDateTime.now().format(TS))
                .append("。数字与素材出处均取自脚本台账，投稿前请人工核对一遍。\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(label).append('\n').append(value == null ? "" : value).append('\n');
    }

    private static String pick(String stored, Object fallback) {
        return notBlank(stored) ? stored : (fallback == null ? null : String.valueOf(fallback));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Boolean boolOrNull(Object o) {
        return o instanceof Boolean b ? b : null;
    }

    private static List<String> tagsOrNull(Object o) {
        return o instanceof List<?> l ? PublishSpec.normalizeTags(cast(l)) : null;
    }

    private static List<String> stringsOrNull(Object o) {
        return o instanceof List<?> l ? cast(l) : null;
    }

    private static List<String> cast(List<?> l) {
        return l.stream().map(x -> x == null ? "" : String.valueOf(x)).toList();
    }
}
