package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 素材库切片 S1 Task 2：char_/scene_ 参考素材文件名约定 + category 规范化白名单（Task 1 Minor#1 并入）。
 * 审查修复轮 1 补：I-1 扫描幂等 / I-2 端到端实参映射 / M-1 大小写 / M-2 tags 逗号化 / M-4 匿名 fail-fast。
 */
class MediaAssetReferenceScanTest {

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    // ==================== 静态 seam：文件名约定 ====================

    @Test
    void parsesCharConvention() {
        String[] r = MediaAssetService.parseRefFileName("char_林晚_正面全身.png");
        assertArrayEquals(new String[]{"CHARACTER", "林晚", "正面全身"}, r);
    }

    @Test
    void parsesSceneConventionJpg() {
        String[] r = MediaAssetService.parseRefFileName("scene_雨巷_黄昏.jpg");
        assertArrayEquals(new String[]{"SCENE", "雨巷", "黄昏"}, r);
    }

    @Test
    void rejectsWrongPrefixAndExtension() {
        assertNull(MediaAssetService.parseRefFileName("ep01_标签_名称.png"));
        assertNull(MediaAssetService.parseRefFileName("char_林晚_正面.mp4"));
        assertNull(MediaAssetService.parseRefFileName("char_林晚.png"));
        assertNull(MediaAssetService.parseRefFileName(null));
    }

    @Test
    void webpAndJpegAccepted() {
        assertNotNull(MediaAssetService.parseRefFileName("scene_雪山_垭口.webp"));
        assertNotNull(MediaAssetService.parseRefFileName("char_甲_乙.jpeg"));
    }

    @Test
    void uppercasePrefixAndExtensionAccepted() {
        // M-1：Windows 截图常见 .PNG/Char_ 前缀，大小写不敏感防静默丢文件
        assertNotNull(MediaAssetService.parseRefFileName("CHAR_甲_乙.PNG"));
        assertNotNull(MediaAssetService.parseRefFileName("Scene_雨巷_黄昏.JPG"));
        assertArrayEquals(new String[]{"CHARACTER", "甲", "乙"}, MediaAssetService.parseRefFileName("Char_甲_乙.png"));
    }

    @Test
    void multiSegmentTagsBecomeCommaSeparated() {
        // M-2：多段命名转库内逗号约定（Task 4 关键词降级按 [,，] 拆分）
        assertArrayEquals(new String[]{"SCENE", "雪山", "北,坡"},
                MediaAssetService.parseRefFileName("scene_雪山_北_坡.webp"));
    }

    // ==================== 静态 seam：category 规范化 ====================

    @Test
    void normalizeCategoryTrimsUppercasesAndKeepsNullAsInsertSemantic() {
        assertNull(MediaAssetService.normalizeCategory(null));
        assertNull(MediaAssetService.normalizeCategory("  "));
        assertEquals("CHARACTER", MediaAssetService.normalizeCategory(" character "));
        assertEquals("SCENE", MediaAssetService.normalizeCategory("Scene"));
        assertEquals("INSERT", MediaAssetService.normalizeCategory("insert"));
    }

    @Test
    void normalizeCategoryRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> MediaAssetService.normalizeCategory("Foo"));
    }

    // ==================== scanReferenceDir 行为 ====================

    @Test
    void scanReturnsEmptyWhenDirNotConfigured() {
        // 未配置目录（referenceImportDir 默认空）→ 空列表，不校验登录不抛异常
        MediaAssetService svc = new MediaAssetService(mock(MediaAssetRepository.class),
                mock(StorageService.class), mock(VideoComposeService.class), mock(UserRepository.class));
        assertTrue(svc.scanReferenceDir().isEmpty());
    }

    @Test
    void scanIngestsRefFilesWithCategoryNameTagsAndIsIdempotent() throws Exception {
        Path dir = Files.createTempDirectory("refscan-");
        Files.writeString(dir.resolve("char_林晚_正面全身.png"), "x");
        Files.writeString(dir.resolve("ep01_标签_名称.png"), "x");   // 非约定命名，须跳过
        MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("fid-1");
        when(storage.getAccessUrl("fid-1")).thenReturn("/images/fid-1.png");
        when(assetRepo.save(any(MediaAsset.class))).thenAnswer(inv -> {
            MediaAsset a = inv.getArgument(0);
            a.setId(100L);
            return a;
        });
        UserRepository userRepo = mock(UserRepository.class);
        User u = new User(); u.setId(1L); u.setUsername("scan-user");
        when(userRepo.findByUsername("scan-user")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("scan-user", null, List.of()));
        MediaAssetService svc = new MediaAssetService(assetRepo, storage, mock(VideoComposeService.class), userRepo);
        ReflectionTestUtils.setField(svc, "referenceImportDir", dir.toString());
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of());

        List<MediaAsset> r = svc.scanReferenceDir();
        // I-2：锁死 parsed[category,name,tags] → ingestPath(p,name,tags,category) 实参映射不错位
        assertEquals(1, r.size());
        assertEquals("CHARACTER", r.get(0).getCategory());
        assertEquals("林晚", r.get(0).getName());
        assertEquals("正面全身", r.get(0).getTags());
        assertEquals("IMAGE", r.get(0).getKind());

        // I-1：第二次扫描同目录 → 已入库键命中，零新增
        when(assetRepo.findByUserIdOrderByIdDesc(1L)).thenReturn(r);
        assertTrue(svc.scanReferenceDir().isEmpty());
    }

    @Test
    void scanRequiresLoginWhenDirConfigured() throws Exception {
        Path dir = Files.createTempDirectory("refscan-auth-");
        Files.writeString(dir.resolve("char_甲_乙.png"), "x");
        SecurityContextHolder.clearContext();   // 匿名
        MediaAssetService svc = new MediaAssetService(mock(MediaAssetRepository.class),
                mock(StorageService.class), mock(VideoComposeService.class), mock(UserRepository.class));
        ReflectionTestUtils.setField(svc, "referenceImportDir", dir.toString());
        // M-4：目录有效且匿名 → fail-fast 冒泡（S-1 收紧：断言确实是登录校验而非任意异常）
        RuntimeException ex = assertThrows(RuntimeException.class, svc::scanReferenceDir);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("未登录"), ex.getMessage());
    }

    @Test
    void uploadRejectsInvalidCategoryAtEntry() throws Exception {
        // Minor#1 收口：upload 入口非法 category fail-fast，不落脏数据
        MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
        StorageService storage = mock(StorageService.class);
        UserRepository userRepo = mock(UserRepository.class);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        MediaAssetService svc = new MediaAssetService(assetRepo, storage, mock(VideoComposeService.class), userRepo);
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "a.png", "image/png", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
                () -> svc.upload(file, "n", "t", null, null, "Foo"));
        verifyNoInteractions(assetRepo);
    }
}
