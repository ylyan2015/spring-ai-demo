package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.exception.PublishRejectException;
import com.github.ylyan2015.springaidemo.repository.PublishMetaRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 发布元数据服务：按平台规格校验后 upsert（人工编辑入口）。
 *
 * <p>鉴权口径与 {@link VideoExportService} 一致：HTTP 层 permitAll，服务层取当前登录用户并校验脚本归属，
 * 不在 Controller 上加 {@code @PreAuthorize}。
 *
 * <p>部分更新语义与 {@code StoryScriptService.updateShot} 对齐：请求体里出现的键才覆盖，
 * 未出现的键保留原值——前端「保存」按钮只发改动字段，整行覆盖会误清人工简介。
 */
@Service
public class PublishMetaService {

    private static final Logger log = LoggerFactory.getLogger(PublishMetaService.class);

    /** 人工编辑来源标识：派生与导出联动都不得覆盖该来源行的非空字段 */
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_GENERATED = "GENERATED";

    private final PublishMetaRepository metaRepository;
    private final StoryScriptRepository scriptRepository;
    private final UserRepository userRepository;

    public PublishMetaService(PublishMetaRepository metaRepository,
                              StoryScriptRepository scriptRepository,
                              UserRepository userRepository) {
        this.metaRepository = metaRepository;
        this.scriptRepository = scriptRepository;
        this.userRepository = userRepository;
    }

    /**
     * 平台规格表：当前只登记 B 站。未支持平台快速失败，
     * 而不是悄悄套用 B 站上限去校验别的平台（那会把「抖音标题 55 字」这类规则写成假的绿灯）。
     */
    public static PublishSpec specFor(String platform) {
        String p = platform == null ? "" : platform.trim();
        if (PublishSpec.PLATFORM_BILIBILI.equalsIgnoreCase(p)) {
            return PublishSpec.bilibili();
        }
        throw new PublishRejectException("暂不支持的发布平台: " + p
                + "（当前仅 " + PublishSpec.PLATFORM_BILIBILI + "）");
    }

    /** 人工保存：校验通过才落库，超限抛 {@link PublishRejectException}（带结构化 violations，端点据此回 400） */
    @Transactional
    public PublishMeta save(Long scriptId, String platform, Map<String, Object> form) {
        Long userId = getCurrentUserId();
        requireOwnedScript(scriptId, userId);
        PublishSpec spec = specFor(platform);
        PublishMeta meta = findOrCreate(scriptId, userId, platform);

        Integer tid = meta.getCategoryTid();
        String categoryName = meta.getCategoryName();
        if (form.get("categoryTid") instanceof Number n) {
            tid = n.intValue();
            categoryName = spec.category(tid).name();   // 未登记分区号快速失败，不写脏 tid
        } else if (form.get("categoryName") != null) {
            PublishSpec.Category c = spec.category(String.valueOf(form.get("categoryName")));
            categoryName = c.name();
            tid = c.tid();
        }

        // 未传的键沿用库内原值：校验必须针对「合并后的最终视图」，
        // 否则部分更新时旧脏值（如平台规则收紧后的长标题）会绕过校验直接落库
        String title = form.containsKey("title") ? text(form.get("title")) : meta.getTitle();
        String description = form.containsKey("description") ? text(form.get("description")) : meta.getDescription();
        List<String> tags = form.containsKey("tags")
                ? PublishSpec.normalizeTags(asStringList(form.get("tags")))
                : splitCsv(meta.getTags());

        List<PublishSpec.Violation> violations = spec.validate(title, tags, description, tid == null ? -1 : tid);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.code() + "(" + v.message() + ")")
                    .collect(Collectors.joining("; "));
            throw new PublishRejectException("发布字段不符合 " + spec.platform() + " 规格: " + detail, violations);
        }

        // 只有请求体里出现的键才覆盖（部分更新）
        if (form.containsKey("title")) meta.setTitle(title);
        if (form.containsKey("description")) meta.setDescription(description);
        if (form.containsKey("tags")) meta.setTags(String.join(",", tags));
        if (form.get("categoryTid") instanceof Number || form.get("categoryName") != null) {
            meta.setCategoryTid(tid);
            meta.setCategoryName(categoryName);
        }
        if (form.containsKey("declaration")) meta.setDeclaration(text(form.get("declaration")));
        if (form.containsKey("collection")) meta.setCollection(text(form.get("collection")));
        if (form.containsKey("pinnedComment")) meta.setPinnedComment(text(form.get("pinnedComment")));
        if (form.containsKey("timestampsCsv")) meta.setTimestampsCsv(text(form.get("timestampsCsv")));
        meta.setSource(SOURCE_MANUAL);
        meta.setUpdatedAt(LocalDateTime.now());
        log.info("Publish meta saved: script {} platform {} titleLen={} tags={} descLen={}",
                scriptId, platform, meta.getTitle() == null ? 0 : meta.getTitle().length(),
                tags.size(), description == null ? 0 : description.length());
        return metaRepository.save(meta);
    }

    /** 查当前用户在某脚本某平台已存的元数据 */
    public Optional<PublishMeta> find(Long scriptId, String platform) {
        Long userId = getCurrentUserId();
        return metaRepository.findByScriptIdAndUserIdAndPlatform(scriptId, userId, platform);
    }

    /** 供导出联动使用：按主键三元组定位，不做登录校验（worker 线程已在提交前捕获 userId） */
    @Transactional
    public PublishMeta findOrCreate(Long scriptId, Long userId, String platform) {
        return metaRepository.findByScriptIdAndUserIdAndPlatform(scriptId, userId, platform)
                .orElseGet(() -> {
                    PublishMeta m = new PublishMeta();
                    m.setScriptId(scriptId);
                    m.setUserId(userId);
                    m.setPlatform(platform);
                    m.setSource(SOURCE_MANUAL);
                    return m;
                });
    }

    public Map<String, Object> toMap(PublishMeta meta) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", meta.getId());
        map.put("scriptId", meta.getScriptId());
        map.put("platform", meta.getPlatform());
        map.put("title", meta.getTitle());
        map.put("description", meta.getDescription());
        map.put("tags", splitCsv(meta.getTags()));
        map.put("categoryName", meta.getCategoryName());
        map.put("categoryTid", meta.getCategoryTid());
        map.put("declaration", meta.getDeclaration());
        map.put("collection", meta.getCollection());
        map.put("pinnedComment", meta.getPinnedComment());
        map.put("timestampsCsv", meta.getTimestampsCsv());
        map.put("source", meta.getSource());
        map.put("updatedAt", meta.getUpdatedAt() == null ? null : meta.getUpdatedAt().toString());
        return map;
    }

    /** 标签 CSV → 列表（null/空串 → 空列表） */
    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return PublishSpec.normalizeTags(List.of(csv.split(",")));
    }

    private static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(x -> out.add(x == null ? "" : String.valueOf(x)));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private StoryScript requireOwnedScript(Long scriptId, Long userId) {
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new PublishRejectException("脚本不存在或无权访问: " + scriptId));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new PublishRejectException("用户未登录");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new PublishRejectException("用户不存在"))
                .getId();
    }
}
