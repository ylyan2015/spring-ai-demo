package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.VideoComposeService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
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
 * MediaAsset 素材库服务测试（Phase 5 Track B Task 1）
 * 上传探测/入库裁剪/图片跳过探测/归属删除校验；登录经服务层 getCurrentUserId idiom
 */
class MediaAssetServiceTest {

    private MediaAssetRepository assetRepository;
    private StorageService storage;
    private VideoComposeService compose;
    private UserRepository userRepository;
    private MediaAssetService service;

    @BeforeEach void setUp() {
        assetRepository = mock(MediaAssetRepository.class);
        storage = mock(StorageService.class);
        compose = mock(VideoComposeService.class);
        userRepository = mock(UserRepository.class);
        service = spy(new MediaAssetService(assetRepository, storage, compose, userRepository));
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        when(assetRepository.save(any(MediaAsset.class))).thenAnswer(inv -> {
            MediaAsset a = inv.getArgument(0);
            if (a.getId() == null) a.setId(100L);
            return a;
        });
        login("u1", 1L);
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    private void login(String username, Long id) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    @Test void videoUploadProbesAndStoresDuration() {
        when(compose.probeDurationSeconds(any(Path.class))).thenReturn(7);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("f1");
        when(storage.getAccessUrl("f1")).thenReturn("/images/f1.mp4");
        MockMultipartFile file = new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1, 2});

        MediaAsset a = service.upload(file, "片段A", "ep01", null, null);

        assertEquals("VIDEO", a.getKind());
        assertEquals(7, a.getDurationSeconds());
        assertEquals("f1", a.getFileId());
        assertEquals("/images/f1.mp4", a.getUrl());
        assertEquals(1L, a.getUserId());
        verify(compose).probeDurationSeconds(any(Path.class));
    }

    @Test void trimParamsProduceTrimmedAsset() throws Exception {
        when(compose.probeDurationSeconds(any(Path.class))).thenReturn(7, 3);
        final byte[][] uploaded = new byte[1][];
        when(storage.upload(any(), anyString(), anyString())).thenAnswer(inv -> {
            uploaded[0] = ((java.io.InputStream) inv.getArgument(0)).readAllBytes();
            return "f2";
        });
        when(storage.getAccessUrl("f2")).thenReturn("/images/f2.mp4");
        // trimVideo seam：真实 ffmpeg 不在单测跑，stub 生成目标文件供后续上传
        doAnswer(inv -> { Files.write(inv.getArgument(3), new byte[]{9}); return null; })
                .when(service).trimVideo(any(Path.class), anyDouble(), anyDouble(), any(Path.class));
        MockMultipartFile file = new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1});

        MediaAsset a = service.upload(file, "裁剪", null, 10.0, 13.0);

        verify(service).trimVideo(any(Path.class), eq(10.0), eq(13.0), any(Path.class));
        assertEquals(3, a.getDurationSeconds());
        // 锁定上传的是裁剪产物（byte 9）而非原片（byte 1）
        assertArrayEquals(new byte[]{9}, uploaded[0]);
    }

    @Test void invalidTrimRangeRejected() {
        when(compose.probeDurationSeconds(any(Path.class))).thenReturn(7);
        MockMultipartFile file = new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1});
        // 逆序区间与只传其一均须 fail-fast，不得静默入库全片
        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "x", null, 13.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "x", null, 10.0, null));
        verify(storage, never()).upload(any(), anyString(), anyString());
    }

    @Test void imageUploadSkipsProbe() {
        when(storage.upload(any(), anyString(), anyString())).thenReturn("f3");
        when(storage.getAccessUrl("f3")).thenReturn("/images/f3.png");
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});

        MediaAsset a = service.upload(file, "截图", null, null, null);

        assertEquals("IMAGE", a.getKind());
        assertNull(a.getDurationSeconds());
        verifyNoInteractions(compose);
    }

    @Test void foreignUserCannotDelete() {
        MediaAsset a = new MediaAsset();
        a.setId(100L);
        a.setUserId(1L);
        when(assetRepository.findById(100L)).thenReturn(Optional.of(a));
        login("u2", 2L);

        assertThrows(IllegalArgumentException.class, () -> service.delete(100L));
        verify(assetRepository, never()).delete(any(MediaAsset.class));
    }
}