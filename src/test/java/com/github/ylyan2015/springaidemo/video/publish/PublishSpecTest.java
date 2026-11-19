package com.github.ylyan2015.springaidemo.video.publish;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B 站发布规格表测试。
 * 判据落在「平台硬数值 + 违规码 + 标签归一」上：规格值被改动、校验分支被删、
 * 归一逻辑被去掉，都必须有用例失败（规格值来自平台实测与官方规范，复核日期 2026-11-19）。
 */
class PublishSpecTest {

    @Test
    void bilibiliHardLimitsMatchPlatformRules() {
        PublishSpec s = PublishSpec.bilibili();
        assertEquals(80, s.titleMax());
        assertEquals(10, s.tagMax());
        assertEquals(2000, s.descMax());
        assertEquals(253, s.category("动漫杂谈").tid());
        assertEquals(182, s.category("影视杂谈").tid());
        assertTrue(s.declarationOptions().contains("含AI生成内容"),
                "AI 声明选项缺失，声明下拉会没有合规项");
    }

    @Test
    void cleanInputYieldsNoViolation() {
        PublishSpec s = PublishSpec.bilibili();
        assertTrue(s.validate("x".repeat(80), List.of("爆仓"), "y".repeat(2000), 253).isEmpty());
    }

    @Test
    void overLimitTitleIsReportedNotTruncated() {
        PublishSpec.Violation v = PublishSpec.bilibili().validate("x".repeat(81), List.of("a"), "", 253).get(0);
        assertEquals("title", v.field());
        assertEquals("TITLE_TOO_LONG", v.code());
    }

    @Test
    void descriptionLimitFollowsSelectedCategory() {
        // 动漫杂谈（动画区）2000 字档；2001 字必须报 DESC_TOO_LONG
        assertEquals("DESC_TOO_LONG",
            PublishSpec.bilibili().validate("t", List.of("a"), "y".repeat(2001), 253).get(0).code());
        // 窄档分区（250 字）用测试专用 tid=7001，不冒充任何真实分区号；
        // 目的是卡住「简介上限取所选分区档，而非全局档」这条分支
        PublishSpec narrow = PublishSpec.bilibili()
                .withCategory(new PublishSpec.Category("测试窄档分区", 7001, 250));
        assertEquals("DESC_TOO_LONG",
            narrow.validate("t", List.of("a"), "y".repeat(251), 7001).get(0).code());
        assertTrue(narrow.validate("t", List.of("a"), "y".repeat(250), 7001).isEmpty());
        // 同一窄档分区下，全局 2000 字档的简介不得直接放行
        assertEquals(250, narrow.category(7001).descMax());
    }

    @Test
    void tooManyTagsIsReported() {
        List<String> eleven = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "tag" + i).toList();
        assertEquals("TAG_TOO_MANY",
            PublishSpec.bilibili().validate("t", eleven, "", 253).get(0).code());
    }

    @Test
    void blankAndDuplicateTagsAreReportedForUiBeforeNormalisation() {
        PublishSpec s = PublishSpec.bilibili();
        List<String> codes = s.validate("t", List.of("解说", "  ", "爆仓", "解说"), "", 253)
                .stream().map(PublishSpec.Violation::code).toList();
        assertTrue(codes.contains("TAG_BLANK"), "空标签必须提示，否则前端 chip 会静默变少");
        assertTrue(codes.contains("TAG_DUPLICATE"), "重复标签必须提示（现稿 fx久留美/FX战士久留美 类问题）");
    }

    @Test
    void tagNormalisationDropsBlankAndDuplicateKeepingOrder() {
        assertEquals(List.of("解说", "爆仓"),
            PublishSpec.normalizeTags(List.of(" 解说 ", "", "爆仓", "解说", "  ", "\t爆仓 ")));
        assertEquals(List.of(), PublishSpec.normalizeTags(null));
    }

    @Test
    void unknownCategoryNameFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> PublishSpec.bilibili().category("不存在的分区"));
    }
}
