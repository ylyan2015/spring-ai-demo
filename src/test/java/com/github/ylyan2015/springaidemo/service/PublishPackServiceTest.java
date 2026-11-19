package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 发布包服务测试。两条底线各有一判据：
 * ① 人工改过的字段绝不会被机器刷掉（用户手打标题被覆盖是最恼人的 bug）；
 * ② 发布包写失败绝不影响出片（视频已经合成完了，不能因为附属文本而报导出失败）。
 */
class PublishPackServiceTest {

    private StorageService storage;
    private PublishMetaService metaService;
    private PublishMetaDeriver deriver;
    private PublishPackService service;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        metaService = mock(PublishMetaService.class);
        deriver = mock(PublishMetaDeriver.class);
        service = new PublishPackService(storage, metaService, deriver);
        when(deriver.derive(738L, 7L, "BILIBILI")).thenReturn(draft());
        when(storage.uploadFinal(any(InputStream.class), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("final/钱坑漫评-发布包.txt");
        when(storage.getAccessUrl("final/钱坑漫评-发布包.txt"))
                .thenReturn("/images/final/钱坑漫评-发布包.txt");
    }

    private Map<String, Object> draft() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("title", "机器派生标题");
        d.put("description", "【本片事实】\n· 峰值亏 1400 万日元\n【素材出处】\n- 官网 fxkurumi-info.com");
        d.put("tags", List.of("福贺久留美", "动漫杂谈"));
        d.put("categoryName", "动漫杂谈");
        d.put("categoryTid", 253);
        d.put("declaration", "含AI生成内容");
        d.put("timestamps", List.of("00:00", "00:03"));
        d.put("approxTimestamps", false);
        d.put("insertRatioPercent", 49);
        // 与真实草稿同形：派生器总是把百分比与口径一起下发，缺一个键就是假桩
        d.put("insertRatioBasis", "整镜口径：镜头只要叠了出处水印就整镜计入，与按插入秒数统计的外部口径不同");
        return d;
    }

    private PublishMeta row(String title, String description, String source) {
        PublishMeta m = new PublishMeta();
        m.setScriptId(738L);
        m.setUserId(7L);
        m.setPlatform("BILIBILI");
        m.setTitle(title);
        m.setDescription(description);
        m.setTags("解说,爆仓");
        m.setCategoryName("动漫杂谈");
        m.setCategoryTid(253);
        m.setDeclaration("含AI生成内容");
        m.setPinnedComment("置顶：素材出处见简介");
        m.setCollection("钱坑漫评");
        m.setSource(source);
        return m;
    }

    @Test
    void manualTitleSurvivesRegeneration() {
        PublishMeta manual = row("人工标题", "人工简介", PublishMetaService.SOURCE_MANUAL);
        when(metaService.upsertGenerated(eq(738L), eq(7L), eq("BILIBILI"), any())).thenReturn(manual);

        service.syncAfterExport(738L, 7L, "钱坑漫评");

        // 落库交给服务层的「只补空」规则，但发布包文本必须写人工值，不能把派生值贴回投稿页
        String text = capturedPackText();
        assertTrue(text.contains("人工标题"), text);
        assertFalse(text.contains("机器派生标题"), "人工值行不得被派生值覆盖：" + text);
        verify(metaService).upsertGenerated(eq(738L), eq(7L), eq("BILIBILI"), any());
    }

    @Test
    void packIsWrittenToFinalDirWithFriendlyTitleName() {
        when(metaService.upsertGenerated(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(row(null, null, PublishMetaService.SOURCE_GENERATED));
        String url = service.syncAfterExport(738L, 7L, "钱坑漫评 01/14");

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> category = ArgumentCaptor.forClass(String.class);
        verify(storage).uploadFinal(any(InputStream.class), title.capture(), category.capture(),
                eq(".txt"), eq("text/plain"));
        // 本层只负责把标题原样递过去，非法字符与路径穿越清洗是存储层职责（LocalStorageServiceFinalTest 已守）
        assertEquals("钱坑漫评 01/14", title.getValue());
        assertEquals("发布包", category.getValue(), "文件名靠 category 段拼成〈标题-发布包.txt〉");
        assertEquals("/images/final/钱坑漫评-发布包.txt", url);
    }

    @Test
    void storageFailureIsSwallowedAndNeverBreaksExport() {
        when(metaService.upsertGenerated(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(row("t", "d", PublishMetaService.SOURCE_GENERATED));
        when(storage.uploadFinal(any(InputStream.class), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("disk full"));

        assertNull(service.syncAfterExport(738L, 7L, "钱坑漫评"), "发布包失败只该少一个附件，不该把出片拖下水");
    }

    @Test
    void packTextFollowsPublishPageOrderAndLabelsRatioBasis() {
        when(metaService.upsertGenerated(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(row(null, null, PublishMetaService.SOURCE_GENERATED));
        service.syncAfterExport(738L, 7L, "钱坑漫评");
        String text = capturedPackText();

        List<String> order = List.of("【标题】", "【分区】", "【标签】", "【简介】", "【置顶评论】");
        int prev = -1;
        for (String h : order) {
            int at = text.indexOf(h);
            assertTrue(at >= 0, "缺字段: " + h + " —— 全文：" + text);
            assertTrue(at > prev, "顺序应为投稿页从上到下: " + h + " —— 全文：" + text);
            prev = at;
        }
        assertTrue(text.contains("1400 万日元"), text);
        // 计划风险 4：整镜口径与外部工具的插入秒数口径差 3 倍，不标口径就是自相矛盾的版权说明
        assertTrue(text.contains("整镜口径"), "引用占比必须标明口径：" + text);
        assertTrue(text.contains("49"), text);
    }

    @Test
    void zipPackFallsBackToDraftWhenNothingStored() {
        when(metaService.findExisting(738L, 7L, "BILIBILI")).thenReturn(Optional.empty());
        String text = service.packTextFor(738L, 7L);
        assertTrue(text.contains("机器派生标题"), text);
        verify(deriver).derive(738L, 7L, "BILIBILI");
    }

    @Test
    void zipPackPrefersStoredRowAndDoesNotDeriveAgain() {
        when(metaService.findExisting(738L, 7L, "BILIBILI"))
                .thenReturn(Optional.of(row("人工标题", "人工简介", PublishMetaService.SOURCE_MANUAL)));
        String text = service.packTextFor(738L, 7L);
        assertTrue(text.contains("人工标题"), text);
        assertTrue(text.contains("置顶：素材出处见简介"), text);
        verifyNoInteractions(deriver);
    }

    private String capturedPackText() {
        ArgumentCaptor<InputStream> cap = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).uploadFinal(cap.capture(), anyString(), anyString(), anyString(), anyString());
        try {
            return new String(cap.getValue().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
