package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.SfxTrack;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.SfxTrackRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 音效库服务（Phase 4 切片 4：分层混音，复刻 BGM 预置模式）
 * 启动扫描 data/sfx（category__name.mp3）；LLM 单次批量推荐每镜音效分类，
 * 失败/未配置时关键词回退（雨→rain/风→wind/走→footsteps/紧张→heartbeat，无匹配 null）。
 */
@Service
public class SfxService {

    private static final Logger log = LoggerFactory.getLogger(SfxService.class);

    private static final Set<String> CATEGORIES = Set.of("wind", "rain", "footsteps", "heartbeat");

    private static final String RECOMMEND_SYSTEM_PROMPT = """
            你是短视频音效设计师。根据每个镜头的画面描述，为镜头挑选最匹配的环境音效分类。

            可选分类：
            - wind: 风声、户外、空旷、微风
            - rain: 雨天、细雨、潮湿
            - footsteps: 行走、脚步、走廊、街道漫步
            - heartbeat: 紧张、悬疑、心跳、压迫

            输入为 JSON 数组，每项 {"shotId": 数字, "scene": 画面描述}。
            只输出一个 JSON 对象，键为 shotId 字符串，值为分类名；无合适音效的镜头不要输出。
            示例：{"3": "rain", "5": "heartbeat"}
            """;

    private final SfxTrackRepository sfxTrackRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${sfx.library-path:./data/sfx}")
    private String libraryPath;

    public SfxService(SfxTrackRepository sfxTrackRepository, ModelService modelService) {
        this.sfxTrackRepository = sfxTrackRepository;
        this.modelService = modelService;
    }

    /** 启动扫描音效库目录并同步数据库记录（idiom 同 BgmService） */
    @PostConstruct
    public void scanLibrary() {
        try {
            Path dir = Path.of(libraryPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("SFX library dir created: {} (运行 tools/generate-preset-sfx.ps1 生成预置音效, "
                        + "文件名格式: category__name.mp3)", dir.toAbsolutePath());
                return;
            }
            List<Path> audioFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                audioFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp3"))
                        .toList();
            }
            Set<String> foundPaths = new HashSet<>();
            for (Path file : audioFiles) {
                String relativePath = file.getFileName().toString();
                foundPaths.add(relativePath);
                if (sfxTrackRepository.findByFilePath(relativePath).isPresent()) {
                    continue;
                }
                String base = relativePath.substring(0, relativePath.lastIndexOf('.'));
                int sep = base.indexOf("__");
                String category = sep > 0 ? base.substring(0, sep).toLowerCase() : "other";
                String name = (sep > 0 ? base.substring(sep + 2) : base).replace('_', ' ');
                sfxTrackRepository.save(new SfxTrack(name, relativePath, category));
                log.info("Built-in SFX registered: [{}] {} -> {}", category, name, relativePath);
            }
            sfxTrackRepository.findAll().stream()
                    .filter(t -> !foundPaths.contains(t.getFilePath()))
                    .forEach(t -> {
                        sfxTrackRepository.delete(t);
                        log.info("Built-in SFX removed (file missing): {}", t.getFilePath());
                    });
            log.info("SFX library scan done: {} track(s) in {}", foundPaths.size(), dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("SFX library scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 每镜音效分类推荐：LLM 单次批量，失败/未配置逐镜关键词回退；无匹配不入 Map。
     */
    public Map<Long, String> recommendForScript(List<StoryShot> shots) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (shots == null || shots.isEmpty()) {
            return result;
        }
        if (modelService != null) {
            try {
                List<Map<String, Object>> items = new ArrayList<>();
                for (StoryShot s : shots) {
                    items.add(Map.of("shotId", s.getId(),
                            "scene", s.getSceneDescription() == null ? "" : s.getSceneDescription()));
                }
                String answer = modelService.getChatClient()
                        .prompt()
                        .system(RECOMMEND_SYSTEM_PROMPT)
                        .user(objectMapper.writeValueAsString(items))
                        .call()
                        .content();
                JsonNode json = objectMapper.readTree(extractJson(answer));
                if (json.isObject()) {
                    json.fields().forEachRemaining(e -> {
                        String category = e.getValue().asText("");
                        if (CATEGORIES.contains(category)) {
                            try {
                                result.put(Long.parseLong(e.getKey()), category);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    });
                    return result;
                }
            } catch (Exception e) {
                log.warn("SFX recommendation LLM failed, fallback to keywords: {}", e.getMessage());
            }
        }
        for (StoryShot s : shots) {
            String category = guessByKeyword(s.getSceneDescription());
            if (category != null) {
                result.put(s.getId(), category);
            }
        }
        return result;
    }

    /** 关键词兜底：雨→rain/风→wind/走→footsteps/紧张→heartbeat，无匹配 null */
    static String guessByKeyword(String scene) {
        if (scene == null || scene.isBlank()) {
            return null;
        }
        String t = scene.toLowerCase();
        if (t.contains("雨")) return "rain";
        if (t.contains("风")) return "wind";
        if (t.contains("走") || t.contains("脚步")) return "footsteps";
        if (t.contains("紧张") || t.contains("心跳") || t.contains("悬疑")) return "heartbeat";
        return null;
    }

    /** 分类对应音效文件路径（库内无该分类返回 null） */
    public Path getSfxFilePath(String category) {
        if (category == null) {
            return null;
        }
        return sfxTrackRepository.findByCategoryOrderByIdAsc(category).stream()
                .findFirst()
                .map(t -> Path.of(libraryPath).resolve(t.getFilePath()))
                .filter(Files::exists)
                .orElse(null);
    }

    /** LLM 输出可能被 Markdown 代码块包裹，提取其中的 JSON 对象 */
    private String extractJson(String text) {
        if (text == null) return "";
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}