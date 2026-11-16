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
import static org.mockito.Mockito.*;

/**
 * 文件夹批量入库测试（Phase 5 Track B Task 6）
 * 文件名约定 ep(\d+)_(标签)_(名称).(mp4|png) 解析；非法跳过；目录未配置返回空；仅读配置目录
 */
class MediaAssetScanTest {

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void parseConventionExtractsTagsAndName() {
        String[] r = MediaAssetService.parseAssetFileName("ep04_名场面_战斗.mp4");
        assertNotNull(r);
        assertEquals("战斗", r[0]);
        assertEquals("ep04,名场面", r[1]);
    }

    @Test void parsePngConvention() {
        String[] r = MediaAssetService.parseAssetFileName("ep12_日常_校园.png");
        assertNotNull(r);
        assertEquals("校园", r[0]);
        assertEquals("ep12,日常", r[1]);
    }

    @Test void parseInvalidReturnsNull() {
        assertNull(MediaAssetService.parseAssetFileName("random.mp4"));
        assertNull(MediaAssetService.parseAssetFileName("ep04_战斗.mp4"));
        assertNull(MediaAssetService.parseAssetFileName("ep04_名场面_战斗.txt"));
        assertNull(MediaAssetService.parseAssetFileName("ep_名场面_战斗.mp4"));
    }

    @Test void scanUnconfiguredReturnsEmpty() {
        MediaAssetService svc = new MediaAssetService(mock(MediaAssetRepository.class),
                mock(StorageService.class), mock(VideoComposeService.class), mock(UserRepository.class));
        ReflectionTestUtils.setField(svc, "importDir", "");
        assertTrue(svc.scanImportDir().isEmpty());
    }

    @Test void scanIngestsConventionFilesAndSkipsInvalid() throws Exception {
        MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
        StorageService storage = mock(StorageService.class);
        UserRepository userRepo = mock(UserRepository.class);
        when(assetRepo.save(any(MediaAsset.class))).thenAnswer(inv -> {
            MediaAsset a = inv.getArgument(0); a.setId(1L); return a;
        });
        when(storage.upload(any(), anyString(), anyString())).thenReturn("f1");
        when(storage.getAccessUrl("f1")).thenReturn("/images/f1.png");
        User u = new User(); u.setId(1L); u.setUsername("scan-user");
        when(userRepo.findByUsername("scan-user")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("scan-user", null, List.of()));

        Path dir = Files.createTempDirectory("scan-");
        Files.writeString(dir.resolve("ep01_标签_名称.png"), "x");
        Files.writeString(dir.resolve("bad.txt"), "x");
        MediaAssetService svc = new MediaAssetService(assetRepo, storage,
                mock(VideoComposeService.class), userRepo);
        ReflectionTestUtils.setField(svc, "importDir", dir.toString());

        List<MediaAsset> r = svc.scanImportDir();
        assertEquals(1, r.size());
        assertEquals("ep01,标签", r.get(0).getTags());
        assertEquals("名称", r.get(0).getName());
    }
}