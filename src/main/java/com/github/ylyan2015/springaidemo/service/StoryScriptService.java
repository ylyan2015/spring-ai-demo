package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.CharacterDesign;
import com.github.ylyan2015.springaidemo.video.Shot;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import com.github.ylyan2015.springaidemo.video.VideoScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 故事脚本服务类
 * 负责脚本的生成、保存、编辑、镜头管理等业务逻辑
 */
@Service
public class StoryScriptService {

    private static final Logger log = LoggerFactory.getLogger(StoryScriptService.class);

    /** 素材清单三道 token 闸（spec §5.2）：条数 / tags 字数 / 行字数 */
    private static final int CATALOG_ASSETS = 30;
    private static final int CATALOG_TAGS = 40;
    private static final int CATALOG_LINE = 60;

    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final VideoScriptService videoScriptService;
    private final StoryCharacterRepository characterRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public StoryScriptService(StoryScriptRepository scriptRepository,
                              StoryShotRepository shotRepository,
                              UserRepository userRepository,
                              VideoScriptService videoScriptService,
                              StoryCharacterRepository characterRepository,
                              MediaAssetRepository mediaAssetRepository) {
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.videoScriptService = videoScriptService;
        this.characterRepository = characterRepository;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    // ==================== 脚本生成与保存 ====================

    /**
     * 根据创意想法生成分镜脚本并保存到数据库
     *
     * @param idea 创意想法文本
     * @return 保存后的脚本实体（含镜头列表）
     */
    // 不加 @Transactional：LLM 调用 15-30s，长事务占死连接池（release 审查 M9）；
    // scriptRepository.save 自带事务，cascade 持久化镜头在同一 save 事务内完成
    public StoryScript generateAndSaveScript(String idea) {
        return generateAndSaveScript(idea, null);
    }

    /**
     * 根据创意想法生成分镜脚本并保存（模块 H：可选模板风格引导）
     *
     * @param idea             创意想法文本
     * @param templateGuidance 模板风格引导（注入 System Prompt），null 时不注入
     */
    public StoryScript generateAndSaveScript(String idea, String templateGuidance) {
        Long userId = getCurrentUserId();

        // 1. 调用 LLM 生成分镜脚本
        log.info("Generating storyboard for user {}: idea='{}', templateGuidance={}",
                userId, idea, templateGuidance != null);
        String assetCatalog = buildAssetCatalog(userId);
        Storyboard storyboard = videoScriptService.generateScript(idea, templateGuidance, assetCatalog);

        // 2. 转换为实体并保存
        StoryScript script = new StoryScript();
        script.setUserId(userId);
        script.setTitle(storyboard.getTitle());
        script.setTheme(storyboard.getTheme());
        script.setIdea(idea);
        script.setStatus("DRAFT");

        if (storyboard.getShots() != null) {
            for (int i = 0; i < storyboard.getShots().size(); i++) {
                Shot shotDto = storyboard.getShots().get(i);
                StoryShot shot = new StoryShot();
                shot.setShotOrder(i + 1);
                shot.setSceneDescription(shotDto.getSceneDescription());
                shot.setCameraMovement(shotDto.getCameraMovement());
                shot.setDurationSeconds(shotDto.getDurationSeconds());
                shot.setNarration(shotDto.getNarration());
                shot.setImagePrompt(shotDto.getImagePrompt());
                shot.setShotSize(normalizeShotSize(shotDto.getShotSize()));
                shot.setDialogue(shotDto.getDialogue());
                shot.setEndCard(shotDto.getEndCard());
                shot.setImageStatus("PENDING");
                script.addShot(shot);
            }
        }

        StoryScript saved = scriptRepository.save(script);
        attachCharacters(saved, storyboard);
        saved = scriptRepository.save(saved);
        log.info("Story script saved: id={}, title='{}', {} shots",
                saved.getId(), saved.getTitle(), saved.getShots().size());

        return saved;
    }

    /**
     * 从 Storyboard DTO 保存脚本（用于手动创建/导入）
     *
     * @param userId 归属用户ID，传 null 时自动使用当前登录用户
     */
    @Transactional
    public StoryScript saveFromStoryboard(Long userId, Storyboard storyboard, String idea) {
        if (userId == null) {
            userId = getCurrentUserId();
        }
        StoryScript script = new StoryScript();
        script.setUserId(userId);
        script.setTitle(storyboard.getTitle());
        script.setTheme(storyboard.getTheme());
        script.setIdea(idea);
        script.setStatus("DRAFT");

        if (storyboard.getShots() != null) {
            for (int i = 0; i < storyboard.getShots().size(); i++) {
                Shot shotDto = storyboard.getShots().get(i);
                StoryShot shot = new StoryShot();
                shot.setShotOrder(i + 1);
                shot.setSceneDescription(shotDto.getSceneDescription());
                shot.setCameraMovement(shotDto.getCameraMovement());
                shot.setDurationSeconds(shotDto.getDurationSeconds());
                shot.setNarration(shotDto.getNarration());
                shot.setImagePrompt(shotDto.getImagePrompt());
                shot.setShotSize(normalizeShotSize(shotDto.getShotSize()));
                shot.setDialogue(shotDto.getDialogue());
                shot.setEndCard(shotDto.getEndCard());
                shot.setImageStatus("PENDING");
                script.addShot(shot);
            }
        }

        StoryScript saved = scriptRepository.save(script);
        attachCharacters(saved, storyboard);
        return scriptRepository.save(saved);
    }

    // ==================== 脚本查询 ====================

    /**
     * 获取当前用户的所有脚本列表（导演工作台：默认排除评论脚本）
     */
    public List<StoryScript> listScripts() {
        return listScripts(null);
    }

    /**
     * Phase 5 Track B：format="commentary" 仅评论；null/其他 排除评论（导演页零改动即分离）
     */
    public List<StoryScript> listScripts(String format) {
        Long userId = getCurrentUserId();
        List<StoryScript> all = scriptRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if ("commentary".equals(format)) {
            return all.stream().filter(s -> "commentary".equals(s.getFormat())).collect(Collectors.toList());
        }
        return all.stream().filter(s -> !"commentary".equals(s.getFormat())).collect(Collectors.toList());
    }

    /**
     * Phase 5 Track B：导入评论类机读 JSON（kurumi-commentary-domestic.json schema）
     */
    @Transactional
    public StoryScript importCommentary(Map<String, Object> json) {
        StoryScript script = new StoryScript();
        script.setUserId(getCurrentUserId());
        script.setFormat("commentary");
        script.setTitle(str(json.get("title"), "未命名漫评"));
        script.setTheme(str(json.get("theme"), ""));
        // idea 是发布派生【本片事实】的唯一台账源，导入时不接就等于丢掉事实
        script.setIdea(str(json.get("idea"), ""));
        script.setStyle(str(json.get("style"), null));
        script.setStatus("DRAFT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) json.get("shots");
        if (shots == null || shots.isEmpty()) {
            throw new IllegalArgumentException("shots 不能为空");
        }
        int order = 1;
        for (Map<String, Object> s : shots) {
            StoryShot shot = new StoryShot();
            shot.setShotOrder(order++);
            shot.setDurationSeconds(intVal(s.get("durationSeconds"), 5));
            shot.setNarration(str(s.get("narration"), null));
            shot.setSceneDescription(str(s.get("sceneDescription"), null));
            shot.setImagePrompt(str(s.get("imagePrompt"), null));
            shot.setShotSize(normalizeShotSize(str(s.get("shotSize"), null)));
            shot.setCameraMovement(str(s.get("cameraMovement"), "固定"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ins = (Map<String, Object>) s.get("insert");
            if (ins != null) {
                shot.setInsertAttribution(str(ins.get("attribution"), "评论引用"));
            }
            shot.setImageStatus("PENDING");
            script.addShot(shot);
        }
        return scriptRepository.save(script);
    }

    /**
     * Phase 5 Track B：脚本形态更新（导演↔评论）
     */
    @Transactional
    public StoryScript updateFormat(Long scriptId, String format) {
        // 审查 Minor#2：format 契约 null|commentary，白名单校验（对齐 updateStyle/updateContinuity idiom，防大小写静默错分与超长列违约）
        String f = format == null || format.isBlank() ? null : format.trim();
        if (f != null && !"commentary".equals(f)) {
            throw new IllegalArgumentException("不支持的脚本形态: " + format);
        }
        StoryScript script = getScript(scriptId);
        script.setFormat(f);
        script.setUpdatedAt(LocalDateTime.now());
        return scriptRepository.save(script);
    }

    private static String str(Object v, String def) {
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    private static int intVal(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    /**
     * 获取脚本详情（含所有镜头）
     */
    public StoryScript getScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
    }

    /**
     * 将 StoryScript 转换为 Storyboard DTO（用于后续图片生成/视频合成）
     */
    public Storyboard toStoryboard(StoryScript script) {
        Storyboard storyboard = new Storyboard();
        storyboard.setTitle(script.getTitle());
        storyboard.setTheme(script.getTheme());

        // Phase 4 切片 1：角色卡反向装配 + id→name 映射
        List<StoryCharacter> characters = characterRepository.findByScriptIdOrderByIdAsc(script.getId());
        Map<Long, String> idToName = new LinkedHashMap<>();
        List<CharacterDesign> designs = new ArrayList<>();
        for (StoryCharacter c : characters) {
            idToName.put(c.getId(), c.getName());
            CharacterDesign cd = new CharacterDesign();
            cd.setName(c.getName());
            cd.setFeatureTable(c.getFeatureTable());
            cd.setPersonality(c.getPersonality());
            cd.setSuggestedVoice(c.getVoice());
            designs.add(cd);
        }
        storyboard.setCharacters(designs);

        List<Shot> shots = script.getShots().stream().map(entity -> {
            Shot dto = new Shot();
            dto.setOrder(entity.getShotOrder());
            dto.setSceneDescription(entity.getSceneDescription());
            dto.setCameraMovement(entity.getCameraMovement());
            dto.setDurationSeconds(entity.getDurationSeconds());
            dto.setNarration(entity.getNarration());
            dto.setImagePrompt(entity.getImagePrompt());
            dto.setShotSize(entity.getShotSize());
            dto.setCharacterNames(namesOf(entity.getCharacterIds(), idToName));
            dto.setDialogue(entity.getDialogue());
            dto.setDialogueCharacterName(entity.getDialogueCharacterId() == null
                    ? null : idToName.get(entity.getDialogueCharacterId()));
            dto.setEndCard(entity.getEndCard());
            return dto;
        }).collect(Collectors.toList());

        storyboard.setShots(shots);
        return storyboard;
    }

    /**
     * Phase 4 切片 1：脚本级画风锁更新（VisualStyle 枚举名校验）
     */
    @Transactional
    public StoryScript updateStyle(Long scriptId, String style) {
        if (style != null && !style.isBlank()) {
            try {
                com.github.ylyan2015.springaidemo.video.VisualStyle.valueOf(style.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的画风: " + style);
            }
        }
        StoryScript script = getScript(scriptId);
        script.setStyle(style == null || style.isBlank() ? null : style.trim().toUpperCase());
        return scriptRepository.save(script);
    }

    /**
     * Phase 4 切片 2：首尾帧连续性模式更新
     */
    @Transactional
    public StoryScript updateContinuity(Long scriptId, String mode) {
        String m = mode == null || mode.isBlank() ? "NONE" : mode.trim().toUpperCase();
        if (!List.of("NONE", "PREV_LAST_FRAME", "GENERATED_LAST_FRAME").contains(m)) {
            throw new IllegalArgumentException("不支持的连续性模式: " + mode);
        }
        StoryScript script = getScript(scriptId);
        script.setContinuityMode(m);
        return scriptRepository.save(script);
    }

    /**
     * P0-3 竖屏优先：脚本级画幅更新（白名单 16:9/9:16/1:1，null/blank 清空回默认横屏，对齐 updateStyle idiom）
     */
    @Transactional
    public StoryScript updateAspect(Long scriptId, String aspectRatio) {
        String a = aspectRatio == null || aspectRatio.isBlank() ? null : aspectRatio.trim();
        if (a != null && !List.of("16:9", "9:16", "1:1").contains(a)) {
            throw new IllegalArgumentException("不支持的画幅: " + aspectRatio);
        }
        StoryScript script = getScript(scriptId);
        script.setAspectRatio(a);
        return scriptRepository.save(script);
    }

    // ==================== 脚本更新 ====================

    /**
     * 更新脚本基本信息（标题、主题、状态）
     */
    @Transactional
    public StoryScript updateScript(Long scriptId, String title, String theme, String status) {
        StoryScript script = getScript(scriptId);

        if (title != null && !title.isBlank()) {
            script.setTitle(title);
        }
        if (theme != null && !theme.isBlank()) {
            script.setTheme(theme);
        }
        if (status != null && !status.isBlank()) {
            script.setStatus(status);
        }
        script.setUpdatedAt(LocalDateTime.now());

        return scriptRepository.save(script);
    }

    /**
     * 删除脚本
     */
    @Transactional
    public void deleteScript(Long scriptId) {
        Long userId = getCurrentUserId();
        StoryScript script = scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        characterRepository.deleteByScriptId(scriptId);
        scriptRepository.delete(script);
        log.info("Story script deleted: id={}, userId={}", scriptId, userId);
    }

    // ==================== 镜头管理 ====================

    /**
     * Phase 5 Track B：镜头绑定素材（归属双校验：script 属当前用户 + asset 属当前用户）
     * asVideo → videoUrl/videoFileId/videoStatus=READY 且 durationSeconds=override||asset.duration；
     * 否则 imageUrl/imageFileId/imageStatus=READY（不写 video 字段）
     */
    @Transactional
    public StoryShot bindAsset(Long scriptId, Long shotId, Long assetId, boolean asVideo, Integer durationOverride) {
        StoryScript script = getScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        Long userId = getCurrentUserId();
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + assetId));
        if (!asset.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权绑定该素材: " + assetId);
        }
        // 审查 Major#2：kind 护栏 fail-fast（IMAGE 绑为视频会 durationSeconds=null 致 scriptToMap NPE 变砖；VIDEO 绑为图污染 i2v 管线）
        if (asVideo && !"VIDEO".equals(asset.getKind())) {
            throw new IllegalArgumentException("仅视频素材可绑定为视频片段: " + assetId);
        }
        if (!asVideo && !"IMAGE".equals(asset.getKind())) {
            throw new IllegalArgumentException("仅图片素材可绑定为主视觉图: " + assetId);
        }
        if (asVideo) {
            shot.setVideoUrl(asset.getUrl());
            shot.setVideoFileId(asset.getFileId());
            shot.setVideoStatus("READY");
            shot.setDurationSeconds(durationOverride != null ? durationOverride : asset.getDurationSeconds());
        } else {
            // 审查 Major#1：图变更失效旧视频/尾帧（对齐 updateShot idiom，防导出静默混入与新图不符的旧片段）
            if (!java.util.Objects.equals(asset.getUrl(), shot.getImageUrl())
                    || !java.util.Objects.equals(asset.getFileId(), shot.getImageFileId())) {
                shot.invalidateVideo();
            }
            shot.setImageUrl(asset.getUrl());
            shot.setImageFileId(asset.getFileId());
            shot.setImageStatus("READY");
        }
        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);
        return shotRepository.save(shot);
    }

    /**
     * 更新单个镜头的内容
     */
    @Transactional
    public StoryShot updateShot(Long scriptId, Long shotId, Map<String, Object> updates) {
        StoryScript script = getScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));

        if (updates.containsKey("sceneDescription")) {
            shot.setSceneDescription((String) updates.get("sceneDescription"));
        }
        if (updates.containsKey("cameraMovement")) {
            shot.setCameraMovement((String) updates.get("cameraMovement"));
        }
        if (updates.containsKey("durationSeconds")) {
            shot.setDurationSeconds(((Number) updates.get("durationSeconds")).intValue());
        }
        if (updates.containsKey("narration")) {
            shot.setNarration((String) updates.get("narration"));
        }
        if (updates.containsKey("imagePrompt")) {
            shot.setImagePrompt((String) updates.get("imagePrompt"));
            // 提示词修改后，图片需要重新生成；旧视频/尾帧同步失效
            shot.setImageStatus("PENDING");
            shot.setImageUrl(null);
            shot.setImageFileId(null);
            shot.invalidateVideo();
        }
        if (updates.containsKey("imageUrl")) {
            String newUrl = (String) updates.get("imageUrl");
            if (!java.util.Objects.equals(newUrl, shot.getImageUrl())) {
                shot.invalidateVideo();
            }
            shot.setImageUrl(newUrl);
        }
        if (updates.containsKey("imageFileId")) {
            String newFileId = (String) updates.get("imageFileId");
            if (!java.util.Objects.equals(newFileId, shot.getImageFileId())) {
                shot.invalidateVideo();
            }
            shot.setImageFileId(newFileId);
        }
        if (updates.containsKey("imageStatus")) {
            shot.setImageStatus((String) updates.get("imageStatus"));
        }
        if (updates.containsKey("characterIds")) {
            Object v = updates.get("characterIds");
            List<Long> owned = characterRepository.findByScriptIdOrderByIdAsc(scriptId).stream()
                    .map(StoryCharacter::getId).collect(Collectors.toList());
            if (v instanceof List<?> list) {
                List<String> filtered = list.stream().map(String::valueOf)
                        .filter(s -> {
                            try {
                                return owned.contains(Long.parseLong(s.trim()));
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        }).collect(Collectors.toList());
                shot.setCharacterIds(filtered.isEmpty() ? null : String.join(",", filtered));
            } else if (v instanceof String s && !s.isBlank()) {
                List<String> filtered = parseCharacterIds(s).stream()
                        .filter(owned::contains).map(String::valueOf).collect(Collectors.toList());
                shot.setCharacterIds(filtered.isEmpty() ? null : String.join(",", filtered));
            } else {
                shot.setCharacterIds(null);
            }
        }
        if (updates.containsKey("referenceAssetIds")) {
            // 素材库切片 S1：参考素材绑定三校验（归属+IMAGE+category∈{CHARACTER,SCENE}，INSERT 禁入生成链路）；
            // 绑定/更换不失效旧图旧视频（参考图只影响下次生成，与 imageUrl 变更失效语义区分）；id 解析复用 parseCharacterIds
            Object v = updates.get("referenceAssetIds");
            List<String> raw = new ArrayList<>();
            if (v instanceof List<?> list) {
                list.forEach(x -> raw.add(String.valueOf(x)));
            } else if (v instanceof String s && !s.isBlank()) {
                raw.add(s);
            }
            Long uid = script.getUserId();   // getScript 已按当前用户归属查出 script，免重复查库（审查 Minor#3）
            List<String> valid = parseCharacterIds(String.join(",", raw)).stream()
                    .map(id -> mediaAssetRepository.findById(id).orElse(null))
                    .filter(a -> a != null && uid.equals(a.getUserId()) && a.isReferenceAsset())
                    .map(a -> String.valueOf(a.getId()))
                    .distinct()
                    .collect(Collectors.toList());
            if (valid.size() < raw.size()) {
                log.warn("参考素材绑定过滤：入参 {} 项，有效 {} 项（shot {}）", raw.size(), valid.size(), shotId);
            }
            shot.setReferenceAssetIds(valid.isEmpty() ? null : String.join(",", valid));
        }
        if (updates.containsKey("shotSize")) {
            shot.setShotSize(normalizeShotSize((String) updates.get("shotSize")));
        }
        if (updates.containsKey("dialogue")) {
            shot.setDialogue((String) updates.get("dialogue"));
        }
        if (updates.containsKey("insertAttribution")) {
            shot.setInsertAttribution((String) updates.get("insertAttribution"));
        }
        if (updates.containsKey("dialogueCharacterId")) {
            Object v = updates.get("dialogueCharacterId");
            Long cid = null;
            if (v instanceof Number n) {
                cid = n.longValue();
            } else if (v instanceof String s && !s.isBlank()) {
                try {
                    cid = Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            // 归属过滤：非本脚本角色置 null（与 addShot/batchUpdateShots 共用，审查修复）
            shot.setDialogueCharacterId(cid);
            filterOwnedDialogueCharacterId(scriptId, shot);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        return shotRepository.save(shot);
    }

    /**
     * 在指定位置插入新镜头
     */
    @Transactional
    public StoryShot addShot(Long scriptId, Integer position, StoryShot newShot) {
        StoryScript script = getScript(scriptId);

        // 确定插入位置
        int insertOrder = (position != null && position >= 1) ? position : script.getShots().size() + 1;

        // 将插入位置及之后的镜头顺序后移
        List<StoryShot> existingShots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        for (StoryShot existing : existingShots) {
            if (existing.getShotOrder() >= insertOrder) {
                existing.setShotOrder(existing.getShotOrder() + 1);
            }
        }
        if (!existingShots.isEmpty()) {
            shotRepository.saveAll(existingShots);
        }

        // 设置新镜头的顺序并添加
        filterOwnedDialogueCharacterId(scriptId, newShot);
        newShot.setShotOrder(insertOrder);
        script.addShot(newShot);
        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shot added to script {}: order={}, scene='{}'",
                scriptId, insertOrder, newShot.getSceneDescription());

        return newShot;
    }

    /**
     * 删除镜头
     */
    @Transactional
    public void deleteShot(Long scriptId, Long shotId) {
        StoryScript script = getScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));

        int deletedOrder = shot.getShotOrder();
        script.removeShot(shot);

        // 将删除位置之后的镜头顺序前移
        List<StoryShot> remainingShots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        for (StoryShot s : remainingShots) {
            if (s.getShotOrder() > deletedOrder) {
                s.setShotOrder(s.getShotOrder() - 1);
            }
        }
        if (!remainingShots.isEmpty()) {
            shotRepository.saveAll(remainingShots);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shot deleted from script {}: shotId={}, order={}", scriptId, shotId, deletedOrder);
    }

    /**
     * 重新排序镜头（拖拽排序后调用）
     *
     * @param scriptId 脚本ID
     * @param shotIds  按新顺序排列的镜头ID列表
     */
    @Transactional
    public void reorderShots(Long scriptId, List<Long> shotIds) {
        StoryScript script = getScript(scriptId);

        for (int i = 0; i < shotIds.size(); i++) {
            Long shotId = shotIds.get(i);
            StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                    .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
            shot.setShotOrder(i + 1);
            shotRepository.save(shot);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        log.info("Shots reordered for script {}: {} shots", scriptId, shotIds.size());
    }

    /**
     * 批量更新镜头（整体替换脚本的镜头列表）
     */
    @Transactional
    public List<StoryShot> batchUpdateShots(Long scriptId, List<StoryShot> updatedShots) {
        StoryScript script = getScript(scriptId);

        // 清空现有镜头
        List<StoryShot> existing = new ArrayList<>(script.getShots());
        existing.forEach(script::removeShot);
        shotRepository.deleteAll(existing);

        // 添加新镜头
        for (int i = 0; i < updatedShots.size(); i++) {
            StoryShot shot = updatedShots.get(i);
            shot.setShotOrder(i + 1);
            shot.setId(null); // 确保是新记录
            filterOwnedDialogueCharacterId(scriptId, shot);
            script.addShot(shot);
        }

        script.setUpdatedAt(LocalDateTime.now());
        scriptRepository.save(script);

        return shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
    }

    // ==================== 工具方法 ====================

    /** 对白角色归属过滤：非本脚本角色置 null（updateShot/addShot/batchUpdateShots 三入口一致，审查修复） */
    private void filterOwnedDialogueCharacterId(Long scriptId, StoryShot shot) {
        Long cid = shot.getDialogueCharacterId();
        if (cid == null) {
            return;
        }
        boolean owned = characterRepository.findByScriptIdOrderByIdAsc(scriptId).stream()
                .anyMatch(c -> c.getId().equals(cid));
        if (!owned) {
            shot.setDialogueCharacterId(null);
        }
    }

    /**
     * 将 StoryScript 实体转换为完整 Map（含镜头详情，供控制器统一复用）
     */
    public Map<String, Object> scriptToMap(StoryScript script) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", script.getId());
        map.put("title", script.getTitle());
        map.put("theme", script.getTheme());
        map.put("idea", script.getIdea());
        map.put("status", script.getStatus());
        map.put("style", script.getStyle());
        map.put("format", script.getFormat());
        map.put("continuityMode", script.getContinuityMode() != null ? script.getContinuityMode() : "NONE");
        map.put("aspectRatio", script.getAspectRatio() != null ? script.getAspectRatio() : "16:9");
        map.put("characters", characterRepository.findByScriptIdOrderByIdAsc(script.getId()).stream()
                .map(StoryCharacterService::characterToMap).collect(Collectors.toList()));
        map.put("createdAt", script.getCreatedAt());
        map.put("updatedAt", script.getUpdatedAt());

        List<Map<String, Object>> shotMaps = script.getShots().stream()
                .sorted(Comparator.comparingInt(StoryShot::getShotOrder))
                .map(this::shotToMap)
                .collect(Collectors.toList());
        map.put("shots", shotMaps);
        map.put("shotCount", shotMaps.size());

        int totalDuration = shotMaps.stream()
                .mapToInt(s -> ((Number) s.getOrDefault("durationSeconds", 0)).intValue())
                .sum();
        map.put("totalDuration", totalDuration);

        return map;
    }

    /**
     * 将 StoryShot 实体转换为 Map
     */
    public Map<String, Object> shotToMap(StoryShot shot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", shot.getId());
        map.put("scriptId", shot.getScriptId());
        map.put("shotOrder", shot.getShotOrder());
        map.put("sceneDescription", shot.getSceneDescription());
        map.put("cameraMovement", shot.getCameraMovement());
        map.put("durationSeconds", shot.getDurationSeconds());
        map.put("narration", shot.getNarration());
        map.put("imagePrompt", shot.getImagePrompt());
        map.put("imageUrl", shot.getImageUrl());
        map.put("imageFileId", shot.getImageFileId());
        map.put("imageStatus", shot.getImageStatus());
        map.put("qualityScore", shot.getQualityScore());
        map.put("characterIds", parseCharacterIds(shot.getCharacterIds()));
        // 素材库切片 S2（读侧组装点，与 mapToStoryShot 写侧配对；Map 链路新增字段须枚举全部组装点）
        map.put("referenceAssetIds", parseCharacterIds(shot.getReferenceAssetIds()));
        map.put("shotSize", shot.getShotSize() != null ? shot.getShotSize() : "MEDIUM");
        map.put("videoUrl", shot.getVideoUrl());
        map.put("videoFileId", shot.getVideoFileId());
        map.put("videoStatus", shot.getVideoStatus() != null ? shot.getVideoStatus() : "NONE");
        map.put("lastFrameUrl", shot.getLastFrameUrl());
        map.put("dialogue", shot.getDialogue());
        map.put("dialogueCharacterId", shot.getDialogueCharacterId());
        map.put("insertAttribution", shot.getInsertAttribution());
        map.put("endCard", shot.getEndCard());
        return map;
    }

    // ==================== Phase 4 切片 1：角色关联辅助 ====================

    /**
     * 持久化角色卡并回写镜头 characterIds（name→id；未匹配 warn 不阻断）
     */
    private void attachCharacters(StoryScript saved, Storyboard storyboard) {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (storyboard.getCharacters() != null) {
            for (CharacterDesign cd : storyboard.getCharacters()) {
                if (cd == null || cd.getName() == null || cd.getName().isBlank()) {
                    continue;
                }
                StoryCharacter c = new StoryCharacter();
                c.setScript(saved);
                c.setName(cd.getName());
                c.setFeatureTable(cd.getFeatureTable());
                c.setPersonality(cd.getPersonality());
                c.setVoice(cd.getSuggestedVoice() == null || cd.getSuggestedVoice().isBlank()
                        ? "zh-CN-XiaoxiaoNeural" : cd.getSuggestedVoice());
                StoryCharacter persisted = characterRepository.save(c);
                ids.put(persisted.getName(), persisted.getId());
            }
        }
        List<Shot> dtos = storyboard.getShots();
        List<StoryShot> entities = saved.getShots();
        if (dtos != null) {
            for (int i = 0; i < entities.size() && i < dtos.size(); i++) {
                entities.get(i).setCharacterIds(linkCharacters(ids, dtos.get(i).getCharacterNames()));
                // Phase 4 切片 4：对白角色 name→id（未匹配 warn 置 null）
                String dialogueName = dtos.get(i).getDialogueCharacterName();
                if (dialogueName != null && !dialogueName.isBlank()) {
                    Long dialogueId = ids.get(dialogueName);
                    if (dialogueId == null) {
                        log.warn("Dialogue character name not matched, ignored: {}", dialogueName);
                    }
                    entities.get(i).setDialogueCharacterId(dialogueId);
                }
            }
        }
    }

    private String linkCharacters(Map<String, Long> ids, List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> matched = new ArrayList<>();
        for (String name : names) {
            Long id = ids.get(name);
            if (id != null) {
                matched.add(String.valueOf(id));
            } else {
                log.warn("Storyboard character name not matched, ignored: {}", name);
            }
        }
        return matched.isEmpty() ? null : String.join(",", matched);
    }

    private List<String> namesOf(String characterIds, Map<Long, String> idToName) {
        List<Long> ids = parseCharacterIds(characterIds);
        if (ids.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Long id : ids) {
            String name = idToName.get(id);
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : names;
    }

    /** 逗号分隔角色 id 解析（非法片段跳过） */
    public static List<Long> parseCharacterIds(String characterIds) {
        if (characterIds == null || characterIds.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : characterIds.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // 非法片段跳过
            }
        }
        return ids;
    }

    private static String normalizeShotSize(String shotSize) {
        if (shotSize == null || shotSize.isBlank()) {
            return "MEDIUM";
        }
        String upper = shotSize.trim().toUpperCase();
        return switch (upper) {
            case "CLOSE", "MEDIUM", "LONG" -> upper;
            default -> "MEDIUM";
        };
    }

    /**
     * 素材清单注入（spec §5.2）：≤30 条（id 倒序≈createdAt 倒序）/tags 截 40/行≤60；空库/异常返 null 零注入
     *
     * <p>三道闸都是为了让「库很大」不变成「prompt 很贵」：一次生成只送 30 行、每行 ≤60 字、tags ≤40 字，
     * 而 null（而非空串）是下游的判据——空串会让 prompt 里出现一个没有内容的「可用素材库」标题。
     * 先筛后截：INSERT 类（含存量的 category=null）不占名额，否则用户的可用素材数会随上传记录的新旧波动。
     */
    private String buildAssetCatalog(Long userId) {
        try {
            List<MediaAsset> assets = mediaAssetRepository.findByUserIdOrderByIdDesc(userId).stream()
                    .filter(MediaAsset::isReferenceAsset)
                    .limit(CATALOG_ASSETS)
                    .toList();
            if (assets.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (MediaAsset a : assets) {
                // 截断次序：先 tags（40），拼行后再按 60 砍整行。长名场景下行闸会再裁掉 tags 尾巴，
                // 这是两闸叠加的确定结果（行宽是硬上限），不是丢数据（复审 round 2 项 10）
                String line = ("CHARACTER".equals(a.effectiveCategory()) ? "[人物] " : "[场景] ")
                        + oneLine(a.getName()) + "：" + truncate(oneLine(a.getTags()), CATALOG_TAGS);
                sb.append(truncate(line, CATALOG_LINE)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // repo 为 null（存量测试构造）与查询异常同走降级：清单是可选增强，不得拖垮生成；栈要留（对齐 AssetMatchService 审查 M-5 口径）
            log.warn("素材清单加载失败，降级无清单模式", e);
            return null;
        }
    }

    /** 清单单元格净化：名/tags 里的换行与控制符会伪造多一行（30 行上限按 lines().count() 算），折叠成单空格 */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Zs}\\s]+", " ").trim();
    }

    /** 按 code point 边界截断：substring 按 UTF-16 code unit 下刀，落在代理对中间会把半个字符喂进 prompt */
    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Character.isLowSurrogate(value.charAt(max)) ? max - 1 : max);
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }
}
