package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceImageResolverTest {

    private ReferenceImageResolver resolver(StorageService storage, String type) {
        ReferenceImageResolver r = new ReferenceImageResolver(mock(StoryCharacterRepository.class), storage);
        ReflectionTestUtils.setField(r, "storageType", type);
        return r;
    }

    private StoryCharacter character(String url, String fileId) {
        StoryCharacter c = new StoryCharacter();
        c.setName("林晚");
        c.setReferenceImageUrl(url);
        c.setReferenceImageFileId(fileId);
        return c;
    }

    @Test
    void httpUrlUsedDirectly() {
        StorageService storage = mock(StorageService.class);
        List<String> refs = resolver(storage, "local")
                .resolve(List.of(character("https://x/a.png", null)));
        assertEquals(List.of("https://x/a.png"), refs);
        verifyNoInteractions(storage);
    }

    @Test
    void nonLocalUsesAccessUrl() {
        StorageService storage = mock(StorageService.class);
        when(storage.getAccessUrl("fid")).thenReturn("https://oss/presigned");
        assertEquals(List.of("https://oss/presigned"),
                resolver(storage, "oss").resolve(List.of(character("/images/a.png", "fid"))));
    }

    @Test
    void localFallsBackToBase64() {
        StorageService storage = mock(StorageService.class);
        when(storage.download("fid")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        List<String> refs = resolver(storage, "local").resolve(List.of(character("/images/a.png", "fid")));
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("data:image/png;base64,"));
    }

    @Test
    void localDownloadFailureSkipsCharacter() {
        StorageService storage = mock(StorageService.class);
        when(storage.download("fid")).thenThrow(new RuntimeException("boom"));
        assertTrue(resolver(storage, "local").resolve(List.of(character("/images/a.png", "fid"))).isEmpty());
    }
}