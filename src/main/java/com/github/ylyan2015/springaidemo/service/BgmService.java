package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.Bgm;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.BgmRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 背景音乐（BGM）服务类
 * 负责音乐库扫描注册、分类列表、LLM 智能推荐与用户上传
 *
 * 内置音乐库目录结构：
 *   {bgm.library-path}/            ← 内置音乐（文件名格式 category__name.mp3）
 *   {bgm.library-path}/uploads/    ← 用户上传的音乐
 */
@Service
public class BgmService {

    private static final Logger log = LoggerFactory.getLogger(BgmService.class);

    /** 内置音乐预置清单（扫描到对应文件时自动注册，mood 供 LLM 推荐参考） */
    private static final Map<String, String[]> BUILT_IN_PRESETS = new LinkedHashMap<>() {{
        put("happy", new String[]{"sunny_morning:明快欢快", "playful_puppy:活泼俏皮", "sweet_candy:甜蜜轻快"});
        put("sad", new String[]{"melancholy_strings:忧伤温情", "lonely_night:孤寂夜色", "distant_memory:回忆感伤"});
        put("epic", new String[]{"heroic_journey:恢弘史诗", "battle_drums:热血战鼓", "rising_star:励志澎湃"});
        put("calm", new String[]{"peaceful_piano:舒缓治愈", "gentle_rain:轻柔雨声", "morning_breeze:清晨微风"});
        put("tense", new String[]{"suspense_pulse:悬疑心跳", "urgent_chase:紧张追逐", "dark_shadow:压迫阴郁"});
    }};

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp3", "wav", "ogg", "m4a", "flac");

    private final BgmRepository bgmRepository;
    private final UserRepository userRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    @Value("${bgm.library-path:./data/bgm}")
    private String libraryPath;

    public BgmService(BgmRepository bgmRepository,
                      UserRepository userRepository,
                      ModelService modelService,
                      ObjectMapper objectMapper) {
        this.bgmRepository = bgmRepository;
        this.userRepository = userRepository;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时扫描内置音乐库目录并同步数据库记录
     */
    @jakarta.annotation.PostConstruct
    public void scanLibrary() {
        try {
            Path dir = Path.of(libraryPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("BGM library dir created: {} (将免版权音乐文件放入该目录即可自动注册, " +
                        "文件名格式: category__name.mp3, 如 happy__sunny_morning.mp3)", dir.toAbsolutePath());
                return;
            }

            // 扫描根目录的音频文件（不含 uploads 子目录）
            List<Path> audioFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                audioFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(this::hasAllowedExtension)
                        .toList();
            }

            Set<String> foundPaths = new HashSet<>();
            for (Path file : audioFiles) {
                String relativePath = file.getFileName().toString();
                foundPaths.add(relativePath);

                String[] parsed = parseFileName(relativePath);
                Bgm existing = bgmRepository.findByFilePathAndIsBuiltInTrue(relativePath).orElse(null);
                if (existing != null) {
                    continue; // 已注册，避免重复探测时长
                }

                Bgm bgm = new Bgm(parsed[1], relativePath, parsed[0], lookupPresetMood(parsed[0], parsed[1]), true);
                bgm.setDurationSeconds(probeDuration(file));
                bgmRepository.save(bgm);
                log.info("Built-in BGM registered: [{}] {} -> {}", parsed[0], parsed[1], relativePath);
            }

            // 清理文件已不存在的内置记录
            bgmRepository.findByIsBuiltInTrue().stream()
                    .filter(b -> !foundPaths.contains(b.getFilePath()))
                    .forEach(b -> {
                        bgmRepository.delete(b);
                        log.info("Built-in BGM removed (file missing): {}", b.getFilePath());
                    });

            log.info("BGM library scan done: {} built-in track(s) in {}", foundPaths.size(), dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("BGM library scan failed: {}", e.getMessage(), e);
        }
    }

    // ==================== BGM 列表 ====================

    /**
     * 查询可用 BGM 列表（内置 + 当前用户上传），可按分类过滤
     */
    public List<Map<String, Object>> listBgm(String category, Long userId) {
        List<Bgm> all = bgmRepository.findByIsBuiltInTrueOrUserIdOrderByCreatedAtDesc(userId);
        return all.stream()
                .filter(b -> category == null || category.isBlank() || b.getCategory().equalsIgnoreCase(category))
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ==================== LLM 智能推荐 ====================

    /**
     * 根据视频主题调用 LLM 分析情感风格，推荐合适的 BGM 分类与曲目
     */
    public Map<String, Object> recommendBgm(String theme, Long userId) {
        if (theme == null || theme.isBlank()) {
            throw new RuntimeException("主题不能为空");
        }

        String category;
        String reason;
        try {
            String answer = modelService.getChatClient()
                    .prompt()
                    .system(RECOMMEND_SYSTEM_PROMPT)
                    .user("视频主题：" + theme)
                    .call()
                    .content();

            JsonNode json = objectMapper.readTree(extractJson(answer));
            category = json.path("category").asText("calm");
            reason = json.path("reason").asText("");
        } catch (Exception e) {
            log.warn("BGM recommendation LLM failed, fallback to keyword matching: {}", e.getMessage());
            category = guessCategoryByKeyword(theme);
            reason = "按主题关键词匹配";
        }

        // 只接受预置分类，防止 LLM 输出意外分类
        if (!BUILT_IN_PRESETS.containsKey(category)) {
            log.warn("Unknown LLM category '{}', fallback to keyword matching", category);
            category = guessCategoryByKeyword(theme);
        }

        List<Map<String, Object>> candidates = listBgm(category, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", category);
        result.put("reason", reason);
        result.put("available", !candidates.isEmpty());
        if (candidates.isEmpty()) {
            result.put("bgm", null);
            result.put("message", "音乐库暂无 '" + category + "' 分类的曲目，可将对应文件放入 BGM 库目录或上传自定义 BGM");
        } else {
            result.put("bgm", candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
        }
        return result;
    }

    // ==================== 用户上传 ====================

    /**
     * 上传自定义 BGM，保存到音乐库 uploads 目录并注册记录
     */
    public Map<String, Object> uploadBgm(MultipartFile file, String name, String category, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }
        String original = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        String ext = extensionOf(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("仅支持音频文件: " + ALLOWED_EXTENSIONS);
        }

        try {
            Path uploadDir = Path.of(libraryPath).resolve("uploads").resolve("user-" + userId);
            Files.createDirectories(uploadDir);
            String relativePath = "uploads/user-" + userId + "/" + System.currentTimeMillis()
                    + "_" + sanitizeFileName(original);
            Path target = Path.of(libraryPath).resolve(relativePath);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            Bgm bgm = new Bgm(
                    (name == null || name.isBlank()) ? original.substring(0, original.lastIndexOf('.')) : name,
                    relativePath,
                    (category == null || category.isBlank()) ? "other" : category.toLowerCase(),
                    null, false);
            bgm.setUserId(userId);
            bgm.setDurationSeconds(probeDuration(target));

            log.info("User BGM uploaded: userId={}, file={}, category={}", userId, relativePath, bgm.getCategory());
            return toMap(bgmRepository.save(bgm));
        } catch (IOException e) {
            log.error("BGM upload failed: {}", e.getMessage(), e);
            throw new RuntimeException("BGM 上传失败: " + e.getMessage(), e);
        }
    }

    // ==================== 供视频合成使用 ====================

    /**
     * 获取 BGM 的本地文件路径（校验归属：内置或当前用户上传）
     */
    public Path getBgmFilePath(Long bgmId, Long userId) {
        Bgm bgm = bgmRepository.findById(bgmId)
                .orElseThrow(() -> new RuntimeException("BGM 不存在: " + bgmId));
        if (!Boolean.TRUE.equals(bgm.getIsBuiltIn()) && !bgm.getUserId().equals(userId)) {
            throw new RuntimeException("无权使用该 BGM");
        }
        Path file = Path.of(libraryPath).resolve(bgm.getFilePath());
        if (!Files.exists(file)) {
            throw new RuntimeException("BGM 文件不存在（可能已被移除）: " + bgm.getFilePath());
        }
        return file;
    }

    /**
     * 查询 BGM 名称（导出记录冗余存储用）
     */
    public String getBgmName(Long bgmId) {
        return bgmRepository.findById(bgmId).map(Bgm::getName).orElse(null);
    }

    // ==================== 工具方法 ====================

    private static final String RECOMMEND_SYSTEM_PROMPT = """
            你是短视频配乐顾问。根据视频主题判断最匹配的背景音乐分类。

            可选分类及特征：
            - happy: 明快、欢快、活力、喜庆、搞笑、可爱
            - calm: 舒缓、治愈、温馨、轻松、生活记录、日常
            - sad: 忧伤、感动、离别、深情、遗憾、温情
            - epic: 恢弘、震撼、冒险、励志、热血、宏大叙事
            - tense: 紧张、悬疑、惊悚、追逐、压迫、冲突

            只输出一个 JSON 对象，不要任何其他文字，格式：
            {"category": "分类名", "reason": "20字以内的推荐理由"}
            """;

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

    /** LLM 不可用时的关键词兜底匹配 */
    private String guessCategoryByKeyword(String theme) {
        String t = theme.toLowerCase();
        if (containsAny(t, "欢", "喜", "搞", "萌", "可爱", "活力", "happy", "fun")) return "happy";
        if (containsAny(t, "燃", "战", "史诗", "冒险", "励志", "热血", "英雄", "epic", "hero")) return "epic";
        if (containsAny(t, "悬", "惊", "恐", "追", "紧张", "谜", "tense", "dark")) return "tense";
        if (containsAny(t, "泪", "伤", "离", "思念", "感动", "遗憾", "sad", "miss")) return "sad";
        return "calm";
    }

    private boolean containsAny(String text, String... keywords) {
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    /** 文件名解析：category__name.ext → [category, 展示名]；无前缀时归入 other */
    private String[] parseFileName(String fileName) {
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        int sep = base.indexOf("__");
        if (sep > 0) {
            String category = base.substring(0, sep).toLowerCase();
            if (BUILT_IN_PRESETS.containsKey(category)) {
                return new String[]{category, base.substring(sep + 2).replace('_', ' ')};
            }
        }
        return new String[]{"other", base.replace('_', ' ')};
    }

    /** 从预置清单查找情绪标签（如 "peaceful_piano" → "舒缓治愈"） */
    private String lookupPresetMood(String category, String nameKey) {
        String[] presets = BUILT_IN_PRESETS.get(category);
        if (presets == null) return null;
        String key = nameKey.replace(' ', '_');
        for (String preset : presets) {
            String[] parts = preset.split(":");
            if (parts[0].equals(key)) return parts[1];
        }
        return null;
    }

    private boolean hasAllowedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot > 0 && ALLOWED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    /** 用 ffprobe 探测音频时长（秒），失败返回 null */
    private Integer probeDuration(Path audioFile) {
        try {
            Process p = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    audioFile.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            p.waitFor();
            return output.isEmpty() ? null : (int) Math.round(Double.parseDouble(output));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toMap(Bgm bgm) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", bgm.getId());
        map.put("name", bgm.getName());
        map.put("category", bgm.getCategory());
        map.put("mood", bgm.getMood());
        map.put("durationSeconds", bgm.getDurationSeconds());
        map.put("isBuiltIn", bgm.getIsBuiltIn());
        map.put("createdAt", bgm.getCreatedAt() == null ? null : bgm.getCreatedAt().toString());
        return map;
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

    /** 供 Controller 调用的当前用户获取 */
    public Long requireCurrentUserId() {
        return getCurrentUserId();
    }
}
