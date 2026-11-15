package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 参考图 URL 解析（Phase 4 切片 1）
 * 回退链：http 绝对 URL 直用 → 非 local 存储 presigned/公网 URL → local base64 data URI → 跳过该角色并 warn（纯文本+特征表降级）。
 * 仅接受存储相对路径（SSRF 红线：LLM 可控 URL 不直接外发）。
 */
@Component
public class ReferenceImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageResolver.class);

    private final StoryCharacterRepository characterRepository;
    private final StorageService storageService;

    @Value("${storage.type:local}")
    private String storageType;

    public ReferenceImageResolver(StoryCharacterRepository characterRepository,
                                  StorageService storageService) {
        this.characterRepository = characterRepository;
        this.storageService = storageService;
    }

    /**
     * 按 shot.characterIds 收集出场角色卡（循环外一次加载的过滤版）
     */
    public List<StoryCharacter> charactersForShot(StoryShot shot) {
        List<Long> ids = StoryScriptService.parseCharacterIds(shot.getCharacterIds());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<StoryCharacter> all = characterRepository.findByScriptIdOrderByIdAsc(shot.getScriptId());
        List<StoryCharacter> matched = new ArrayList<>();
        for (StoryCharacter c : all) {
            if (ids.contains(c.getId())) {
                matched.add(c);
            }
        }
        return matched;
    }

    /**
     * 解析角色参考图为生成器可消费的 URL 列表（无参考图的角色跳过）
     */
    public List<String> resolve(List<StoryCharacter> characters) {
        List<String> refs = new ArrayList<>();
        if (characters == null) {
            return refs;
        }
        for (StoryCharacter c : characters) {
            String url = c.getReferenceImageUrl();
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                refs.add(url);
                continue;
            }
            String fileId = c.getReferenceImageFileId();
            if (fileId == null || fileId.isBlank()) {
                continue;
            }
            if (!"local".equals(storageType)) {
                refs.add(storageService.getAccessUrl(fileId));
                continue;
            }
            try (InputStream in = storageService.download(fileId)) {
                refs.add("data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes()));
            } catch (Exception e) {
                log.warn("Reference image unavailable for character {}, degrade to text+featureTable: {}",
                        c.getName(), e.getMessage());
            }
        }
        return refs;
    }
}