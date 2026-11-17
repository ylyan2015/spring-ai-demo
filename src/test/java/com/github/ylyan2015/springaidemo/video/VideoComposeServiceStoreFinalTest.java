package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 成片归档接线：compose 携带 finalTitle 时，storeVideo 走 uploadFinal（友好命名），
 * 无标题时保持 legacy upload（日期目录 + video_ 前缀），存量零回归。
 */
class VideoComposeServiceStoreFinalTest {

    @TempDir
    Path tempDir;

    @Test
    void storeVideo_withFinalTitle_usesUploadFinal() throws Exception {
        StorageService storage = mock(StorageService.class);
        when(storage.uploadFinal(any(), eq("那座山"), eq("视频"), eq(".mp4"), eq("video/mp4")))
                .thenReturn("final/那座山-视频.mp4");
        when(storage.getAccessUrl(anyString())).thenReturn("/images/final/那座山-视频.mp4");
        VideoComposeService svc = new VideoComposeService(storage);

        Path video = tempDir.resolve("v.mp4");
        Files.writeString(video, "data");
        VideoComposeService.ComposeOptions opts = new VideoComposeService.ComposeOptions().finalTitle("那座山");

        var result = svc.storeVideoForTest(video, opts);

        assertEquals("/images/final/那座山-视频.mp4", result.url());
        verify(storage).uploadFinal(any(InputStream.class), eq("那座山"), eq("视频"), eq(".mp4"), eq("video/mp4"));
        verify(storage, never()).upload(any(), anyString(), anyString());
    }

    @Test
    void storeVideo_withoutFinalTitle_keepsLegacyUpload() throws Exception {
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("2026/11/17/uuid_video_x.mp4");
        when(storage.getAccessUrl(anyString())).thenReturn("/images/2026/11/17/uuid_video_x.mp4");
        VideoComposeService svc = new VideoComposeService(storage);

        Path video = tempDir.resolve("v.mp4");
        Files.writeString(video, "data");

        var result = svc.storeVideoForTest(video, new VideoComposeService.ComposeOptions());

        verify(storage).upload(any(InputStream.class), argThat(n -> ((String) n).startsWith("video_")), eq("video/mp4"));
        verify(storage, never()).uploadFinal(any(), any(), any(), any(), any());
    }

    @Test
    void storeVideo_blankTitle_keepsLegacyUpload() throws Exception {
        StorageService storage = mock(StorageService.class);
        when(storage.upload(any(), anyString(), anyString())).thenReturn("x");
        when(storage.getAccessUrl(anyString())).thenReturn("/images/x");
        VideoComposeService svc = new VideoComposeService(storage);

        Path video = tempDir.resolve("v.mp4");
        Files.writeString(video, "data");

        svc.storeVideoForTest(video, new VideoComposeService.ComposeOptions().finalTitle("   "));

        verify(storage).upload(any(), anyString(), anyString());
        verify(storage, never()).uploadFinal(any(), any(), any(), any(), any());
    }
}
