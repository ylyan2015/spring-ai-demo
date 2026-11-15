package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色卡服务（Phase 4 切片 1）
 * CRUD + 定妆参考图上传 + 声线列表；归属经 script.userId 校验（服务层登录校验红线）
 */
@Service
public class StoryCharacterService {

    private static final Logger log = LoggerFactory.getLogger(StoryCharacterService.class);

    /** 内置 zh 声线（edge-tts），角色卡默认晓晓 */
    private static final List<String> VOICES = List.of(
            "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural",
            "zh-CN-XiaoyiNeural", "zh-CN-YunyangNeural", "zh-CN-liaoning-XiaobeiNeural");

    private final StoryCharacterRepository characterRepository;
    private final StoryScriptRepository scriptRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public StoryCharacterService(StoryCharacterRepository characterRepository,
                                 StoryScriptRepository scriptRepository,
                                 UserRepository userRepository,
                                 StorageService storageService) {
        this.characterRepository = characterRepository;
        this.scriptRepository = scriptRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    public List<Map<String, Object>> listByScript(Long scriptId) {
        requireScript(scriptId);
        return characterRepository.findByScriptIdOrderByIdAsc(scriptId).stream()
                .map(StoryCharacterService::characterToMap).toList();
    }

    public Map<String, Object> create(Long scriptId, String name, String featureTable,
                                      String personality, String voice) {
        StoryScript script = requireScript(scriptId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("角色名不能为空");
        }
        StoryCharacter c = new StoryCharacter();
        c.setScript(script);
        c.setName(name);
        c.setFeatureTable(featureTable);
        c.setPersonality(personality);
        c.setVoice(voice == null || voice.isBlank() ? "zh-CN-XiaoxiaoNeural" : voice);
        StoryCharacter saved = characterRepository.save(c);
        log.info("Character created: id={}, name={}, script={}", saved.getId(), saved.getName(), scriptId);
        return characterToMap(saved);
    }

    public Map<String, Object> update(Long scriptId, Long characterId, String name, String featureTable,
                                      String personality, String voice) {
        StoryCharacter c = requireCharacter(scriptId, characterId);
        if (name != null && !name.isBlank()) {
            c.setName(name);
        }
        if (featureTable != null) {
            c.setFeatureTable(featureTable);
        }
        if (personality != null) {
            c.setPersonality(personality);
        }
        if (voice != null && !voice.isBlank()) {
            c.setVoice(voice);
        }
        return characterToMap(characterRepository.save(c));
    }

    public void delete(Long scriptId, Long characterId) {
        StoryCharacter c = requireCharacter(scriptId, characterId);
        characterRepository.delete(c);
        log.info("Character deleted: id={}, script={}", characterId, scriptId);
    }

    /**
     * 定妆参考图上传（multipart，存储转存；仅接受 image/*）
     */
    public Map<String, Object> uploadReferenceImage(Long scriptId, Long characterId,
                                                    InputStream inputStream, String fileName,
                                                    String contentType) {
        StoryCharacter c = requireCharacter(scriptId, characterId);
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片文件（image/*）");
        }
        String fileId = storageService.upload(inputStream, fileName, contentType);
        c.setReferenceImageFileId(fileId);
        c.setReferenceImageUrl(storageService.getAccessUrl(fileId));
        return characterToMap(characterRepository.save(c));
    }

    public List<Map<String, String>> listVoices() {
        return VOICES.stream().map(v -> Map.of("name", v)).toList();
    }

    /**
     * 角色卡 → Map（scriptToMap/控制器/工具共用，单一装配点）
     */
    public static Map<String, Object> characterToMap(StoryCharacter c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("name", c.getName());
        map.put("featureTable", c.getFeatureTable());
        map.put("personality", c.getPersonality());
        map.put("referenceImageUrl", c.getReferenceImageUrl());
        map.put("referenceImageFileId", c.getReferenceImageFileId());
        map.put("voice", c.getVoice());
        return map;
    }

    // ==================== 私有辅助 ====================

    private StoryCharacter requireCharacter(Long scriptId, Long characterId) {
        requireScript(scriptId);
        return characterRepository.findByIdAndScriptId(characterId, scriptId)
                .orElseThrow(() -> new RuntimeException("角色不存在或无权访问: " + characterId));
    }

    private StoryScript requireScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new RuntimeException("用户未登录");
        }
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
        return user.getId();
    }
}