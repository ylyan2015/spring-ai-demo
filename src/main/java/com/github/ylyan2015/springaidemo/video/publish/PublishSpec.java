package com.github.ylyan2015.springaidemo.video.publish;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 平台发布规格表（标题/标签/简介的硬上限、分区候选、创作声明选项）。
 *
 * <p>数值全部来自平台侧可核实来源，复核日期 2026-11-19：
 * <ul>
 *   <li>标题 ≤80 字符、标签 ≤10 个、简介 ≤2000 字：B 站创作中心投稿页计数器实测</li>
 *   <li>分区 tid：B 站官方分区公告与公开分区表（动漫杂谈 2022-09-27 由动画区新增）</li>
 *   <li>简介字数随分区变（部分分区仅 250 字）：见 {@link Category#descMax()}</li>
 * </ul>
 * 未核实的选项（如创作声明下拉的其余条目）一律不写死，避免把猜测当规范。
 *
 * <p>本类是纯数据 + 纯函数，不依赖 Spring 与任何服务，便于派生器与端点共用。
 */
public record PublishSpec(String platform,
                          int titleMax,
                          int tagMax,
                          int descMax,
                          List<Category> categories,
                          List<String> declarationOptions,
                          String coverNote) {

    /** 分区候选：name 展示名，tid 平台分区号（灰度期可用投稿页地址参数 {@code ?tid=} 指定），
     *  descMax 该分区的简介上限（&le;0 表示沿用全局 {@link #descMax()}） */
    public record Category(String name, int tid, int descMax) {}

    /** 校验结果：field 供前端定位输入框，code 为稳定枚举串（不可本地化） */
    public record Violation(String field, String code, String message) {}

    /** 已登记的平台名（与 {@code PlatformPreset} 枚举名保持一致，避免两套平台串） */
    public static final String PLATFORM_BILIBILI = "BILIBILI";

    public static final String TITLE_TOO_LONG = "TITLE_TOO_LONG";
    public static final String TAG_TOO_MANY = "TAG_TOO_MANY";
    public static final String TAG_BLANK = "TAG_BLANK";
    public static final String TAG_DUPLICATE = "TAG_DUPLICATE";
    public static final String DESC_TOO_LONG = "DESC_TOO_LONG";

    /** B 站（bilibili 创作中心 · 投稿页）规格 */
    public static PublishSpec bilibili() {
        return new PublishSpec(
                "BILIBILI",
                80,
                10,
                2000,
                List.of(
                        // 评论/解说类动漫内容官方口径投「动漫杂谈」；影视评论投「影视杂谈」
                        new Category("动漫杂谈", 253, 2000),
                        new Category("影视杂谈", 182, 2000)),
                // 仅列已核实项；投稿页下拉的其余声明选项需在页面核对后再补，不凭印象写死
                List.of("含AI生成内容"),
                "封面须与主体内容一致或为视频截图，禁动图；单图不低于 640×188，社区推荐 16:10（1146×717）");
    }

    /** 按分区号取分区（未登记返回 null，交由调用方降级到全局档） */
    public Category categoryOrNull(int tid) {
        for (Category c : categories) {
            if (c.tid() == tid) {
                return c;
            }
        }
        return null;
    }

    /** 按分区号取分区，未知名/未知号快速失败（端点与派生器都靠它拦住拼错的分区） */
    public Category category(int tid) {
        Category c = categoryOrNull(tid);
        if (c == null) {
            throw new IllegalArgumentException("未登记的分区号 " + tid + "（" + platform + "）");
        }
        return c;
    }

    /** 按分区名取分区 */
    public Category category(String name) {
        for (Category c : categories) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("未登记的分区名 " + name + "（" + platform + "）");
    }

    /** 追加/替换一个分区候选（不可变），供测试与未来窄档分区使用 */
    public PublishSpec withCategory(Category c) {
        List<Category> merged = new ArrayList<>(categories.stream()
                .filter(x -> x.tid() != c.tid())
                .toList());
        merged.add(c);
        return new PublishSpec(platform, titleMax, tagMax, descMax,
                List.copyOf(merged), declarationOptions, coverNote);
    }

    /**
     * 校验发布字段。简介上限取所选分区档（未登记分区号退回全局档）；
     * 标题与简介按码点计数（emoji/生僻字不双倍占用）。
     */
    public List<Violation> validate(String title, List<String> tags, String description, int tid) {
        List<Violation> out = new ArrayList<>();
        int titleLen = codePoints(title);
        if (titleLen > titleMax) {
            out.add(new Violation("title", TITLE_TOO_LONG,
                    "标题 " + titleLen + " 字符，超 " + platform + " 上限 " + titleMax));
        }
        List<String> norm = normalizeTags(tags);
        if (tags != null && norm.size() < tags.size()) {
            // 归一后变短：要么有空标签要么有重复，两者分别提示（前端据此标灰 chip）
            if (hasBlank(tags)) {
                out.add(new Violation("tags", TAG_BLANK, "存在空白标签，落库前会被丢弃"));
            }
            if (hasDuplicate(tags)) {
                out.add(new Violation("tags", TAG_DUPLICATE, "存在重复标签（忽略首尾空白后同义）"));
            }
        }
        if (norm.size() > tagMax) {
            out.add(new Violation("tags", TAG_TOO_MANY,
                    "标签 " + norm.size() + " 个，超上限 " + tagMax));
        }
        int limit = descLimit(tid);
        int descLen = codePoints(description);
        if (descLen > limit) {
            out.add(new Violation("description", DESC_TOO_LONG,
                    "简介 " + descLen + " 字符，超该分区上限 " + limit));
        }
        return out;
    }

    /** 所选分区的简介上限；分区未登记时沿用全局档（不因未收录分区而误拒合法稿件） */
    public int descLimit(int tid) {
        Category c = categoryOrNull(tid);
        return c != null && c.descMax() > 0 ? c.descMax() : descMax;
    }

    /** 标签清洗：去首尾空白（含制表符）→ 丢空 → 去重保序。null 安全。 */
    public static List<String> normalizeTags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(raw.stream()
                .map(t -> t == null ? "" : t.trim())
                .filter(t -> !t.isEmpty())
                .toList()));
    }

    private static boolean hasBlank(List<String> tags) {
        return tags.stream().anyMatch(t -> t == null || t.trim().isEmpty());
    }

    private static boolean hasDuplicate(List<String> tags) {
        return normalizeTags(tags).size() < tags.stream()
                .filter(t -> t != null && !t.trim().isEmpty())
                .count();
    }

    private static int codePoints(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
