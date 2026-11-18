package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
    private final MediaAssetRepository mediaAssetRepository;
    private final StoryScriptRepository scriptRepository;

    @Value("${storage.type:local}")
    private String storageType;

    public ReferenceImageResolver(StoryCharacterRepository characterRepository,
                                  StorageService storageService,
                                  MediaAssetRepository mediaAssetRepository,
                                  StoryScriptRepository scriptRepository) {
        this.characterRepository = characterRepository;
        this.storageService = storageService;
        this.mediaAssetRepository = mediaAssetRepository;
        this.scriptRepository = scriptRepository;
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

    /**
     * 素材库参考图解析（spec §3.5）：按 shot.referenceAssetIds 加载 MediaAsset，
     * 归属+kind+category 三校验后走与角色参考图完全相同的回退链；CHARACTER 优先 SCENE。
     * 三校验是消费侧最后防线：批量 PUT 写侧不做校验，脏 id / 他人素材在这里被拦下。
     */
    public List<String> resolveAssets(StoryShot shot) {
        List<Long> ids = StoryScriptService.parseCharacterIds(shot.getReferenceAssetIds()).stream()
                .distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return List.of();
        }
        Long scriptId = shot.getScriptId();
        if (scriptId == null) {
            // 与存量 charactersForShot 的 null scriptId 语义对齐（不得进 findById，那会抛 IAE）
            log.warn("Shot {} has no script, skip {} reference assets", shot.getId(), ids.size());
            return List.of();
        }
        Long ownerId = scriptRepository.findById(scriptId)
                .map(StoryScript::getUserId).orElse(null);
        if (ownerId == null) {
            log.warn("Cannot resolve owner of script {}, skip {} reference assets of shot {}", scriptId, ids.size(), shot.getId());
            return List.of();
        }
        List<MediaAsset> assets = new ArrayList<>();
        for (Long id : ids) {
            MediaAsset a = mediaAssetRepository.findById(id).orElse(null);
            if (a == null || !ownerId.equals(a.getUserId()) || !a.isReferenceAsset()) {
                continue;
            }
            assets.add(a);
        }
        assets.sort(Comparator.comparing((MediaAsset a) -> "CHARACTER".equals(a.effectiveCategory()) ? 0 : 1));
        List<String> refs = new ArrayList<>();
        for (MediaAsset a : assets) {
            String url = a.getUrl();
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                refs.add(url);
                continue;
            }
            String fileId = a.getFileId();
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
                log.warn("Reference asset {} unavailable, skip: {}", a.getName(), e.getMessage());
            }
        }
        return refs;
    }

    /** 最终 refs = 角色定妆图（在前）+ 素材库参考图（spec §3.5） */
    public List<String> resolveAll(StoryShot shot, List<StoryCharacter> characters) {
        List<String> refs = new ArrayList<>(resolve(characters));
        refs.addAll(resolveAssets(shot));
        return refs;
    }
}