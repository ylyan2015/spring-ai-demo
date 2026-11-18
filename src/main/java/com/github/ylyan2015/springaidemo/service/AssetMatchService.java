package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.MediaAssetRepository;
import com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 素材库自动推荐（spec §2）：LLM 批量判断镜头×素材文本相关性，失败降级关键词重叠。
 * 推荐不落库——确认走 updateShot.referenceAssetIds。LLM 只碰 name/tags 文本，不碰图片字节。
 */
@Service
public class AssetMatchService {

    private static final Logger log = LoggerFactory.getLogger(AssetMatchService.class);
    private static final int CATALOG_CAP = 100;
    private static final int TOP_N = 3;

    public record Candidate(Long assetId, String name, String category, String reason) {}
    public record ShotMatch(Long shotId, List<Candidate> candidates) {}
    public record MatchResult(String source, List<ShotMatch> matches) {}

    private final MediaAssetRepository assetRepository;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final StoryCharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public AssetMatchService(MediaAssetRepository assetRepository, StoryScriptRepository scriptRepository,
                             StoryShotRepository shotRepository, StoryCharacterRepository characterRepository,
                             UserRepository userRepository,
                             ModelService modelService, ObjectMapper objectMapper) {
        this.assetRepository = assetRepository;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    public MatchResult match(Long scriptId) {
        Long userId = getCurrentUserId();
        scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
        List<StoryShot> shots = shotRepository.findByScriptIdOrderByShotOrderAsc(scriptId);
        List<MediaAsset> catalogAll = assetRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(MediaAsset::isReferenceAsset)
                .toList();
        if (catalogAll.size() > CATALOG_CAP) {
            // spec §2.1 第 5 条：>100 按 id 倒序（≈createdAt 倒序）截断 + warn，防 prompt 爆炸
            log.warn("素材目录 {} 条超上限，截断至 {}（scriptId={}）", catalogAll.size(), CATALOG_CAP, scriptId);
        }
        List<MediaAsset> catalog = catalogAll.size() > CATALOG_CAP
                ? catalogAll.subList(0, CATALOG_CAP) : catalogAll;
        if (catalog.isEmpty() || shots.isEmpty()) {
            return new MatchResult("EMPTY", List.of());
        }
        try {
            return llmMatch(shots, catalog, characterNamesByScript(scriptId));
        } catch (Exception e) {
            // 审查 M-5：保留完整栈，区分 LLM 已知异常与解析/代码异常
            log.warn("LLM 素材匹配失败，降级关键词重叠: {}", e.getMessage(), e);
            return keywordMatch(shots, catalog, characterNamesByScript(scriptId));
        }
    }

    /** spec §2.1.1：镜头信号 = sceneDescription + 出场角色名（id→name，角色名与素材库一致是 §5.2 闭环前提） */
    private Map<Long, String> characterNamesByScript(Long scriptId) {
        Map<Long, String> idToName = new HashMap<>();
        for (StoryCharacter c : characterRepository.findByScriptIdOrderByIdAsc(scriptId)) {
            idToName.put(c.getId(), c.getName());
        }
        return idToName;
    }

    private String characterNamesFor(StoryShot shot, Map<Long, String> idToName) {
        return StoryScriptService.parseCharacterIds(shot.getCharacterIds()).stream()
                .map(idToName::get).filter(java.util.Objects::nonNull)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private MatchResult llmMatch(List<StoryShot> shots, List<MediaAsset> catalog,
                                 Map<Long, String> idToName) throws Exception {
        StringBuilder cat = new StringBuilder();
        for (MediaAsset a : catalog) {
            cat.append("id=").append(a.getId())
               .append(" [").append("CHARACTER".equals(a.effectiveCategory()) ? "人物" : "场景").append("] ")
               .append(a.getName()).append(" tags=").append(a.getTags() == null ? "" : a.getTags()).append('\n');
        }
        StringBuilder sh = new StringBuilder();
        for (StoryShot s : shots) {
            String names = characterNamesFor(s, idToName);
            sh.append("镜头id=").append(s.getId());
            if (!names.isEmpty()) {
                sh.append(" 出场角色=[").append(names).append("]");
            }
            sh.append(": ").append(s.getSceneDescription()).append('\n');
        }
        String system = """
                你是分镜素材匹配助手。给定素材目录和镜头列表，为每个镜头推荐最相关的素材（最多3个）。
                人物素材优先匹配镜头出场角色，场景素材匹配环境描述。没有相关素材的镜头返回空数组。
                只输出 JSON：{"matches":[{"shotId":镜头id,"assetIds":[素材id...],"reasons":["每个素材一句理由"...]}]}
                assetIds 必须来自素材目录的 id，禁止编造。""";
        String response = modelService.getChatClient().prompt()
                .system(system).user("素材目录：\n" + cat + "\n镜头列表：\n" + sh)
                .call().content();
        String cleaned = response == null ? "" : response.trim();
        // 审查 I-1：围栏安全剥离（未闭合时 lastIndexOf 命中开头围栏 → substring 越界丢整份有效 JSON，
        // 对齐 ImagePromptOptimizerService.cleanPromptOutput idiom）
        if (cleaned.startsWith("```")) {
            int f = cleaned.indexOf('\n');
            int end = cleaned.lastIndexOf("```");
            if (f > 0 && end > f + 1) {
                cleaned = cleaned.substring(f + 1, end).trim();
            } else if (f > 0) {
                cleaned = cleaned.substring(f + 1).replace("`", "").trim();
            }
        }
        JsonNode root = objectMapper.readTree(cleaned);
        Set<Long> validIds = new HashSet<>();
        Map<Long, MediaAsset> byId = new HashMap<>();
        for (MediaAsset a : catalog) { validIds.add(a.getId()); byId.put(a.getId(), a); }
        List<ShotMatch> matches = new ArrayList<>();
        int matchedShotNodes = 0;
        // 复审收口：matches 数组校验前置（防 returnedNodes NPE，保住精确的 IAE 诊断）
        JsonNode matchesArr = root.get("matches");
        if (matchesArr == null || !matchesArr.isArray()) {
            throw new IllegalArgumentException("LLM 输出缺少 matches 数组");
        }
        int returnedNodes = matchesArr.size();
        for (StoryShot s : shots) {
            List<Candidate> candidates = new ArrayList<>();
            JsonNode m = findShotNode(root, s.getId());
            if (m != null) {
                matchedShotNodes++;
                JsonNode ids = m.get("assetIds");
                JsonNode reasons = m.get("reasons");
                for (int i = 0; ids != null && i < ids.size() && candidates.size() < TOP_N; i++) {
                    long id = ids.get(i).asLong();
                    if (!validIds.contains(id)) continue;
                    MediaAsset a = byId.get(id);
                    String reason = reasons != null && reasons.has(i) ? reasons.get(i).asText() : "";
                    candidates.add(new Candidate(id, a.getName(), a.effectiveCategory(), reason));
                }
            }
            matches.add(new ShotMatch(s.getId(), candidates));
        }
        // 审查 M-3（收紧）：返回非空但 shotId 全对不上（模型用自己编号）才降级；
        // matches=[] 是 system prompt 明定的合法「无相关素材」回答，正常返回 LLM+零候选
        if (matchedShotNodes == 0 && returnedNodes > 0) {
            throw new IllegalStateException("LLM 返回的 shotId 与本脚本镜头全部不匹配");
        }
        return new MatchResult("LLM", matches);
    }

    private JsonNode findShotNode(JsonNode root, Long shotId) {
        JsonNode arr = root.get("matches");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("LLM 输出缺少 matches 数组");
        for (JsonNode n : arr) {
            if (n.has("shotId") && n.get("shotId").asLong() == shotId) return n;
        }
        return null;
    }

    /**
     * 关键词重叠降级：name 命中 +2、tag 命中 +1；tags 按 [,，_空白] 拆分（兼容扫描逗号/booru 下划线双形态，台账约束）；
     * 审查 M-4：双向 Locale.ROOT 小写归一（imagePrompt 英文大小写混排 vs 库内 tag 全小写）；镜头信号含出场角色名（I-2）。
     */
    private MatchResult keywordMatch(List<StoryShot> shots, List<MediaAsset> catalog, Map<Long, String> idToName) {
        List<ShotMatch> matches = new ArrayList<>();
        for (StoryShot s : shots) {
            String text = ((s.getSceneDescription() == null ? "" : s.getSceneDescription())
                    + " " + (s.getImagePrompt() == null ? "" : s.getImagePrompt())
                    + " " + characterNamesFor(s, idToName)).toLowerCase(Locale.ROOT);
            List<Candidate> candidates = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();
            for (MediaAsset a : catalog) {
                int score = 0;
                if (a.getName() != null && !a.getName().isBlank()
                        && text.contains(a.getName().toLowerCase(Locale.ROOT))) score += 2;
                if (a.getTags() != null) {
                    for (String t : a.getTags().split("[,，_\\s]+")) {
                        if (!t.isBlank() && text.contains(t.toLowerCase(Locale.ROOT))) score++;
                    }
                }
                if (score > 0) {
                    candidates.add(new Candidate(a.getId(), a.getName(), a.effectiveCategory(),
                            "关键词重叠 " + score + " 分（LLM 不可用降级）"));
                    scores.add(score);
                }
            }
            // 平行 score 列表排序（不反解析 reason 字符串，审查友好性）
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt((Integer i) -> -scores.get(i)));
            List<Candidate> sorted = new ArrayList<>();
            for (int i = 0; i < order.size() && i < TOP_N; i++) sorted.add(candidates.get(order.get(i)));
            matches.add(new ShotMatch(s.getId(), sorted));
        }
        return new MatchResult("FALLBACK", matches);
    }

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
