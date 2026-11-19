package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.PublishMetaRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 发布元数据 upsert 与规格校验测试。
 * 判据落在「单行幂等 / 超限硬失败 / 归属拒绝 / 标签落库形态」上：
 * 去掉归属校验、去掉超限抛错、改成每次新建行，都必须有用例失败。
 */
class PublishMetaServiceTest {

    private PublishMetaRepository metaRepository;
    private StoryScriptRepository scriptRepository;
    private UserRepository userRepository;
    private PublishMetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(PublishMetaRepository.class);
        scriptRepository = mock(StoryScriptRepository.class);
        userRepository = mock(UserRepository.class);
        service = new PublishMetaService(metaRepository, scriptRepository, userRepository);

        User u = new User();
        u.setId(7L);
        u.setUsername("kurumi_fx");
        when(userRepository.findByUsername("kurumi_fx")).thenReturn(Optional.of(u));
        StoryScript script = new StoryScript();
        script.setId(738L);
        script.setUserId(7L);
        when(scriptRepository.findByIdAndUserId(eq(738L), eq(7L))).thenReturn(Optional.of(script));
        when(metaRepository.save(any(PublishMeta.class))).thenAnswer(inv -> inv.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("kurumi_fx", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> form(String title, List<String> tags, String desc) {
        return Map.of("title", title, "tags", tags, "description", desc, "categoryTid", 253);
    }

    @Test
    void upsertReusesExistingRowInsteadOfInsertingSecond() {
        PublishMeta existing = new PublishMeta();
        existing.setId(99L);
        existing.setScriptId(738L);
        existing.setUserId(7L);
        existing.setPlatform("BILIBILI");
        existing.setTitle("旧标题");
        when(metaRepository.findByScriptIdAndUserIdAndPlatform(738L, 7L, "BILIBILI"))
                .thenReturn(Optional.of(existing));

        PublishMeta saved = service.save(738L, "BILIBILI", form("新标题", List.of("爆仓"), "简介"));

        assertEquals(99L, saved.getId(), "同一 (script,user,platform) 必须复用原行");
        assertEquals("新标题", saved.getTitle());
    }

    @Test
    void overLimitTitleIsRejectedWithViolationCode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(738L, "BILIBILI", form("x".repeat(81), List.of("a"), "ok")));
        assertTrue(e.getMessage().contains("TITLE_TOO_LONG"),
                "message 必须带违规码供前端定位，实际：" + e.getMessage());
        verify(metaRepository, never()).save(any());
    }

    @Test
    void overLimitDescriptionIsRejectedWithViolationCode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(738L, "BILIBILI", form("t", List.of("a"), "y".repeat(2001))));
        assertTrue(e.getMessage().contains("DESC_TOO_LONG"), e.getMessage());
        verify(metaRepository, never()).save(any());
    }

    @Test
    void otherUsersScriptCannotBeSavedAndNothingIsWritten() {
        when(scriptRepository.findByIdAndUserId(eq(739L), eq(7L))).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,
                () -> service.save(739L, "BILIBILI", form("t", List.of("a"), "d")));
        verify(metaRepository, never()).save(any());
    }

    @Test
    void anonymousRequestIsRejected() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.save(738L, "BILIBILI", form("t", List.of("a"), "d")));
        assertTrue(e.getMessage().contains("未登录"), e.getMessage());
    }

    @Test
    void tagsAreStoredNormalisedAsCsv() {
        PublishMeta saved = service.save(738L, "BILIBILI",
                form("t", List.of(" 解说 ", "", "爆仓", "解说"), "d"));
        assertEquals("解说,爆仓", saved.getTags(), "落库前必须去空去重，且以 CSV 存（避免 @ElementCollection 跨库差异）");
    }

    @Test
    void categoryNameResolvesToItsTid() {
        PublishMeta saved = service.save(738L, "BILIBILI", Map.of(
                "title", "t", "tags", List.of("a"), "description", "d", "categoryName", "动漫杂谈"));
        assertEquals(253, saved.getCategoryTid());
        assertEquals("动漫杂谈", saved.getCategoryName());
    }

    @Test
    void unsupportedPlatformFailsFast() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(738L, "DOUYIN", form("t", List.of("a"), "d")));
        assertTrue(e.getMessage().contains("DOUYIN"), e.getMessage());
    }

    @Test
    void partialUpdateKeepsUntouchedFields() {
        PublishMeta existing = new PublishMeta();
        existing.setId(5L);
        existing.setScriptId(738L);
        existing.setUserId(7L);
        existing.setPlatform("BILIBILI");
        existing.setTitle("人工标题");
        existing.setDescription("人工简介");
        existing.setTags("久留美");
        when(metaRepository.findByScriptIdAndUserIdAndPlatform(738L, 7L, "BILIBILI"))
                .thenReturn(Optional.of(existing));

        // 只 PUT 标签：标题与简介不得被清空（前端「保存」按钮只发改动字段）
        PublishMeta saved = service.save(738L, "BILIBILI", Map.of("tags", List.of("爆仓")));
        assertEquals("人工标题", saved.getTitle());
        assertEquals("人工简介", saved.getDescription());
        assertEquals("爆仓", saved.getTags());
    }

    @Test
    void partialUpdateValidatesMergedViewNotOnlySubmittedKeys() {
        // 库里已存一个非法长标题（典型场景：平台规则收紧后旧稿变非法）。
        // 只 PUT 标签也必须拒绝：校验得看「合并后的最终视图」，
        // 只看本次提交片段的话，旧脏值会被一路带进发布包。
        PublishMeta existing = new PublishMeta();
        existing.setId(6L);
        existing.setScriptId(738L);
        existing.setUserId(7L);
        existing.setPlatform("BILIBILI");
        existing.setTitle("x".repeat(81));
        existing.setDescription("旧简介");
        when(metaRepository.findByScriptIdAndUserIdAndPlatform(738L, 7L, "BILIBILI"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(738L, "BILIBILI", Map.of("tags", List.of("爆仓"))));
        assertTrue(e.getMessage().contains("TITLE_TOO_LONG"), e.getMessage());
        verify(metaRepository, never()).save(any());
    }
}
