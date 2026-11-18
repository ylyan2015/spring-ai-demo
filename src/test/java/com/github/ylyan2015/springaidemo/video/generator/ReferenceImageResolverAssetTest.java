package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.*;
import com.github.ylyan2015.springaidemo.repository.*;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceImageResolverAssetTest {

    StoryCharacterRepository charRepo = mock(StoryCharacterRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StorageService storage = mock(StorageService.class);
    ReferenceImageResolver resolver;
    StoryShot shot;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceImageResolver(charRepo, storage, assetRepo, scriptRepo);
        ReflectionTestUtils.setField(resolver, "storageType", "local");
        shot = new StoryShot(); shot.setId(3L);
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        shot.setScript(s);   // scriptId 为派生只读属性，只能经关系赋值
        when(scriptRepo.findById(7L)).thenReturn(Optional.of(s));
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of());
    }

    private MediaAsset asset(long id, long userId, String kind, String category, String url, String fileId) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(userId); a.setKind(kind); a.setCategory(category);
        a.setUrl(url); a.setFileId(fileId);
        when(assetRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void localAssetResolvesToBase64DataUri() throws Exception {
        shot.setReferenceAssetIds("11");
        asset(11L, 1L, "IMAGE", "CHARACTER", "/images/a.png", "f11");
        when(storage.download("f11")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        List<String> refs = resolver.resolveAssets(shot);
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("data:image/png;base64,"));
    }

    @Test
    void httpUrlUsedDirectlyAndNonLocalUsesAccessUrl() {
        shot.setReferenceAssetIds("11,12");
        asset(11L, 1L, "IMAGE", "SCENE", "https://cdn.example.com/a.png", "f11");
        asset(12L, 1L, "IMAGE", "SCENE", "/images/b.png", "f12");
        assertEquals("https://cdn.example.com/a.png", resolver.resolveAssets(shot).get(0));
        ReflectionTestUtils.setField(resolver, "storageType", "oss");
        when(storage.getAccessUrl("f12")).thenReturn("https://oss.example.com/f12");
        assertEquals("https://oss.example.com/f12", resolver.resolveAssets(shot).get(1));
    }

    @Test
    void ownershipKindCategoryGuards() {
        shot.setReferenceAssetIds("11,12,13,14,15");
        // 违规项一律给 https url：一旦被漏拦就必然命中回退链第一段而现身（不靠下载桩空转）
        asset(11L, 2L, "IMAGE", "CHARACTER", "https://x.example/leak1.png", "f11");  // 他人素材
        asset(12L, 1L, "VIDEO", "SCENE", "https://x.example/leak2.png", "f12");      // VIDEO
        asset(13L, 1L, "IMAGE", null, "https://x.example/leak3.png", "f13");         // 存量 INSERT
        asset(14L, 1L, "IMAGE", "CHARACTER", null, null);                             // 合法但无可下载文件（回退链第一段后被 fileId 空拦下）
        asset(15L, 1L, "IMAGE", "SCENE", "https://x.example/ok.png", "f15");        // 正向对照：合法项必放
        assertEquals(List.of("https://x.example/ok.png"), resolver.resolveAssets(shot));
    }

    @Test
    void downloadFailureSkipsWithWarn() throws Exception {
        shot.setReferenceAssetIds("11");
        asset(11L, 1L, "IMAGE", "SCENE", "/images/a.png", "f11");
        when(storage.download("f11")).thenThrow(new RuntimeException("file gone"));
        assertTrue(resolver.resolveAssets(shot).isEmpty());
    }

    @Test
    void resolveAllMergesCharactersFirstAndCharacterAssetsBeforeScene() throws Exception {
        shot.setCharacterIds("5");
        shot.setReferenceAssetIds("12,11");   // 12=SCENE 在前，11=CHARACTER 应排前
        StoryCharacter c = new StoryCharacter(); c.setId(5L); c.setName("林晚");
        c.setReferenceImageFileId("fc");
        when(charRepo.findByScriptIdOrderByIdAsc(7L)).thenReturn(List.of(c));
        asset(11L, 1L, "IMAGE", "CHARACTER", null, "f11");
        asset(12L, 1L, "IMAGE", "SCENE", null, "f12");
        when(storage.download("fc")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(storage.download("f11")).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        when(storage.download("f12")).thenReturn(new ByteArrayInputStream(new byte[]{3}));
        List<String> refs = resolver.resolveAll(shot, resolver.charactersForShot(shot));
        // 字节桩互异→base64 互异，顺序错即红（size+前缀断言对排列不敏感）
        assertEquals(List.of("data:image/png;base64,AQ==",   // 角色定妆图 fc 在前
                "data:image/png;base64,Ag==",                // CHARACTER 素材 11 先于 SCENE
                "data:image/png;base64,Aw=="), refs);        // SCENE 素材 12
    }

    @Test
    void noBindingReturnsEmptyWithoutRepoCalls() {
        assertTrue(resolver.resolveAssets(shot).isEmpty());
        // 方法级 never 精确锁定本路径的两处查库（未绑定时 ids 为空即早退）
        verify(assetRepo, never()).findById(any());
        verify(scriptRepo, never()).findById(any());
    }

    @Test
    void orphanShotWithoutScriptDegradesWithoutThrowing() {
        StoryShot orphan = new StoryShot();
        orphan.setId(9L);
        orphan.setReferenceAssetIds("11");
        // scriptId 派生自 script 关系，为 null 时不得进 findById（真仓库会抛 IAE）；mock 不抛，故靠 never 锁住
        assertTrue(resolver.resolveAssets(orphan).isEmpty());
        verify(scriptRepo, never()).findById(any());
        verify(assetRepo, never()).findById(any());
    }

    @Test
    void duplicateIdsResolveToOneImage() {
        shot.setReferenceAssetIds("11,11");
        asset(11L, 1L, "IMAGE", "CHARACTER", "https://x.example/a.png", "f11");
        // 批量 PUT 写侧不 distinct，消费侧去重否则白抛一个参考位（Task 8 二槽上限）
        assertEquals(List.of("https://x.example/a.png"), resolver.resolveAssets(shot));
        verify(assetRepo, times(1)).findById(11L);
    }
}
