package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 素材库切片 S1 Task 3：StoryShot.referenceAssetIds 绑定 + updateShot 三条校验
 * （归属当前用户 + kind=IMAGE + category∈{CHARACTER,SCENE}；INSERT/VIDEO/他人素材拒）。
 * 红线：绑定/更换素材不失效旧图旧视频（参考图只影响下次生成，与 imageUrl 变更失效语义区分）。
 */
class StoryScriptServiceAssetBindingTest {

    StoryScriptRepository scriptRepo = mock(StoryScriptRepository.class);
    StoryShotRepository shotRepo = mock(StoryShotRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    MediaAssetRepository assetRepo = mock(MediaAssetRepository.class);
    StoryScriptService service;
    StoryShot shot;

    @BeforeEach
    void setUp() {
        service = new StoryScriptService(scriptRepo, shotRepo, userRepo,
                mock(com.github.ylyan2015.springaidemo.video.VideoScriptService.class),
                mock(StoryCharacterRepository.class), assetRepo);
        User u = new User(); u.setId(1L); u.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        StoryScript s = new StoryScript(); s.setId(7L); s.setUserId(1L);
        when(scriptRepo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(s));
        shot = new StoryShot(); shot.setId(3L); shot.setShotOrder(1);
        shot.setImageUrl("/images/old.png"); shot.setImageStatus("READY");
        when(shotRepo.findByIdAndScriptId(3L, 7L)).thenReturn(Optional.of(shot));
        when(shotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MediaAsset asset(long id, long userId, String kind, String category) {
        MediaAsset a = new MediaAsset();
        a.setId(id); a.setUserId(userId); a.setKind(kind); a.setCategory(category);
        when(assetRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void bindsValidReferenceAssets() {
        asset(11L, 1L, "IMAGE", "CHARACTER");
        asset(12L, 1L, "IMAGE", "SCENE");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11", "12")));
        assertEquals("11,12", shot.getReferenceAssetIds());
    }

    @Test
    void rejectsOtherUsersAsset() {
        asset(11L, 2L, "IMAGE", "CHARACTER");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11")));
        assertNull(shot.getReferenceAssetIds());
    }

    @Test
    void rejectsInsertAndVideo() {
        asset(11L, 1L, "IMAGE", null);      // 存量 INSERT（effectiveCategory=INSERT）
        asset(12L, 1L, "VIDEO", "SCENE");   // 视频素材
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11", "12")));
        assertNull(shot.getReferenceAssetIds());
    }

    @Test
    void bindingDoesNotInvalidateImageOrVideo() {
        asset(11L, 1L, "IMAGE", "CHARACTER");
        shot.setVideoUrl("/images/v.mp4"); shot.setVideoStatus("READY");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of("11")));
        assertEquals("/images/old.png", shot.getImageUrl());
        assertEquals("READY", shot.getImageStatus());
        assertEquals("/images/v.mp4", shot.getVideoUrl());
        assertEquals("READY", shot.getVideoStatus());
    }

    @Test
    void emptyListClearsBinding() {
        shot.setReferenceAssetIds("11");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", List.of()));
        assertNull(shot.getReferenceAssetIds());
    }

    @Test
    void parsesCommaStringAndSkipsGarbage() {
        // 审查 Minor#4：String 形态入参 + 合法/脏片段混合（parseCharacterIds 复用面）
        asset(11L, 1L, "IMAGE", "SCENE");
        service.updateShot(7L, 3L, Map.of("referenceAssetIds", " 11 , abc "));
        assertEquals("11", shot.getReferenceAssetIds());
    }
}
