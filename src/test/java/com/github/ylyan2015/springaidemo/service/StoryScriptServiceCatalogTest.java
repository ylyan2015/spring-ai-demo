package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 13（spec §5.2）：素材清单进 prompt 的三道 token 闸与两种降级。
 * 清单完全由本服务拼装，所以判据全部落在「传给 generateScript 的第三个实参」上，
 * 而不是 LLM 收到什么——那是 VideoScriptServicePromptTest 的职责。
 */
class StoryScriptServiceCatalogTest {

    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    VideoScriptService videoScriptService = mock(VideoScriptService.class);
    StoryScriptService service;

    private UserRepository userRepo = mock(UserRepository.class);

    @BeforeEach
    void setUp() {
        StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
        when(charRepo.save(any())).thenAnswer(inv -> {
            StoryCharacter c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });
        service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class), userRepo,
                videoScriptService, charRepo, assetRepo);
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        Storyboard sb = new Storyboard();
        sb.setTitle("t");
        sb.setTheme("th");
        when(videoScriptService.generateScript(anyString(), isNull(), any())).thenReturn(sb);
        when(scriptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MediaAsset asset(long id, String name, String category, String tags) {
        MediaAsset a = new MediaAsset();
        a.setId(id);
        a.setUserId(1L);
        a.setKind("IMAGE");
        a.setName(name);
        a.setCategory(category);
        a.setTags(tags);
        return a;
    }

    private String capturedCatalog() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(videoScriptService).generateScript(eq("创意"), isNull(), cap.capture());
        return cap.getValue();
    }

    @Test
    void catalogCappedAt30AndTruncated() {
        List<MediaAsset> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(asset(i, "角色" + i, "CHARACTER", "标签非常非常非常非常非常非常非常非常非常非常非常非常长"));
        }
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(many);
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertEquals(30, catalog.lines().count());
        // 行宽闸门不由本用例守（本载荷行长 36 字符，删了截断也不红）——longNameLineTruncatedTo60 才是它的守兵
        // 入选身份（审查 Minor-2）：不钉住的话，把 limit(30) 换成 skip/倒序取尾依然全绿
        assertTrue(catalog.startsWith("[人物] 角色0："), catalog);
        assertFalse(catalog.contains("角色30"), catalog);
        assertFalse(catalog.contains("角色39"), catalog);
    }

    @Test
    void insertAssetsExcludedAndEmptyLibraryInjectsNull() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(1, "截图", null, "ep01")));
        service.generateAndSaveScript("创意");
        assertNull(capturedCatalog());
    }

    @Test
    void repoFailureDegradesToNullWithoutBlocking() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenThrow(new RuntimeException("DB 挂了"));
        assertNotNull(service.generateAndSaveScript("创意"));
        assertNull(capturedCatalog());
    }

    @Test
    void characterAndSceneFormatted() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(1, "林晚", "CHARACTER", "黑长直,红大衣"), asset(2, "雨巷", "SCENE", "黄昏,霓虹")));
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertTrue(catalog.contains("[人物] 林晚：黑长直,红大衣"));
        assertTrue(catalog.contains("[场景] 雨巷：黄昏,霓虹"));
    }

    /** 非图片素材（视频/音频）即便被标成 CHARACTER 也不得进清单：prompt 里承诺「已备参考图」却给不出图 */
    @Test
    void nonImageKindExcluded() {
        MediaAsset video = asset(1, "片段", "CHARACTER", "op01");
        video.setKind("VIDEO");
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(video));
        service.generateAndSaveScript("创意");
        assertNull(capturedCatalog());
    }

    /**
     * 闸门的顺序是行为契约：先筛后截才拿得到「最近 30 条可用素材」。
     * 反过来（先 limit(30) 再筛）会让最前面 5 条 INSERT 把可用名额吃掉，清单只剩 25 条——
     * 用户看到的素材数随上传记录的新旧波动，而这条差异在只有齐一色素材的库里测不出来。
     */
    @Test
    void filterHappensBeforeCapSoInsertRowsDoNotEatTheQuota() {
        List<MediaAsset> mixed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mixed.add(asset(100 - i, "截图" + i, null, "ep01"));
        }
        for (int i = 0; i < 40; i++) {
            mixed.add(asset(i, "角色" + i, "CHARACTER", "黑长直"));
        }
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(mixed);
        service.generateAndSaveScript("创意");
        assertEquals(30, capturedCatalog().lines().count());
    }

    /** tags 为 null（本地上传从不填 tags）时不得拼出 "null"，否则 prompt 里出现假标签词 */
    @Test
    void nullTagsRenderAsEmptyNotLiteralNull() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(1, "林晚", "CHARACTER", null)));
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertTrue(catalog.contains("[人物] 林晚："));
        assertTrue(!catalog.contains("null"));
    }

    /**
     * 行宽 60 的专属守兵：cappedAt30 的载荷行长只有 36 字符，删掉截断也不会红，
     * 这里用 80 字名钉住「行被砍到正好 60」，长 prompt 的第一道闸才算真有人守。
     */
    @Test
    void longNameLineCutToExactly60Chars() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L))
                .thenReturn(List.of(asset(1, "角".repeat(80), "CHARACTER", "黑长直")));
        service.generateAndSaveScript("创意");
        String line = capturedCatalog().lines().findFirst().orElse("");
        assertEquals(60, line.length(), line);
    }

    /**
     * 行截 60 不得拆代理对（审查 Important-2）：substring 按 UTF-16 code unit 下刀，正好落在 emoji 中间时，
     * 半个字符会进 prompt（模型收到乱码/占位符），而行宽也不再是「60 字符」。
     * 守兵是「末位不得是代理」而不是 codePoints 计数——孤立代理在 codePoints() 里也是一个点，数不出来（复审 Important-A）。
     */
    @Test
    void surrogatePairNeverSplitByLineTruncation() {
        String movieEmoji = new String(Character.toChars(0x1F3AC));
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(1, "a".repeat(54) + movieEmoji, "CHARACTER", "黑长直")));
        service.generateAndSaveScript("创意");
        String line = capturedCatalog().lines().findFirst().orElse("");
        assertEquals(59, line.length(), line);
        assertFalse(Character.isSurrogate(line.charAt(line.length() - 1)), "尾部不得残留半截代理对");
    }

    /** tags 截 40 同病：名短、行未过 60，那 40 是唯一在起作用的那刀，同样不得拆代理对 */
    @Test
    void surrogatePairNeverSplitByTagsTruncation() {
        String movieEmoji = new String(Character.toChars(0x1F3AC));
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(1, "林晚", "CHARACTER", "b".repeat(39) + movieEmoji)));
        service.generateAndSaveScript("创意");
        String line = capturedCatalog().lines().findFirst().orElse("");
        assertTrue(line.endsWith("b".repeat(39)), line);
        assertFalse(Character.isSurrogate(line.charAt(line.length() - 1)), "尾部不得残留半截代理对");
    }

    /** 名/tags 里的换行不得伪造多一行（30 行上限与「一素材一行」都按 lines().count() 算，审查 Minor-4） */
    @Test
    void embeddedNewlineDoesNotFakeAnExtraLine() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(
                asset(1, "林\n晚", "CHARACTER", "黑长\r\n直")));
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertEquals(1, catalog.lines().count(), catalog);
        assertTrue(catalog.contains("[人物] 林 晚：黑长 直"), catalog);
    }

    /** 名也走 oneLine：null 名不得拼出字面 "null"（本轮新行为，不钉就会默默回退，复审 Minor-B） */
    @Test
    void nullNameDoesNotRenderLiteralNull() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(asset(1, null, "CHARACTER", "黑长直")));
        service.generateAndSaveScript("创意");
        String catalog = capturedCatalog();
        assertTrue(catalog.contains("[人物] ：黑长直"), catalog);
        assertFalse(catalog.contains("null"), catalog);
    }

    /** repo 为 null（存量测试构造出的 NPE 降级）必须是显式契约，不能只靠别的用例顺带绿（审查 Minor-5） */
    @Test
    void nullRepositoryDegradesToNoCatalogWithoutFailing() {
        service = new StoryScriptService(scriptRepo, mock(StoryShotRepository.class), userRepo,
                videoScriptService, mock(StoryCharacterRepository.class), null);
        assertNotNull(service.generateAndSaveScript("创意"));
        assertNull(capturedCatalog());
    }

    /** tags 40 字闸门：截后行还剩 12 字余量，所以这 40 是唯一在起作用的限制（60 字行闸截不到它） */
    @Test
    void tagsTruncatedTo40Chars() {
        when(assetRepo.findByUserIdOrderByIdDesc(1L))
                .thenReturn(List.of(asset(1, "林晚", "CHARACTER", "a".repeat(100))));
        service.generateAndSaveScript("创意");
        String line = capturedCatalog().lines().findFirst().orElse("");
        assertTrue(line.endsWith("a".repeat(40)), line);
        assertFalse(line.contains("a".repeat(41)), line);
        // 钉住「截到 40 而非 52」：只 endsWith 的话，tags 闸删掉、行闸还在时本断言仍真（审查 Minor-1）
        assertEquals(48, line.length(), line);
    }
}
