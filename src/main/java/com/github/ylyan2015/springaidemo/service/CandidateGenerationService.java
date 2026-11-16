package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.ShotCandidate;
import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ShotCandidateRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ShotPromptAssembler;
import com.github.ylyan2015.springaidemo.video.VisualStyle;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedVideo;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.generator.LoraResolver;
import com.github.ylyan2015.springaidemo.video.generator.LoraSpec;
import com.github.ylyan2015.springaidemo.video.generator.ReferenceImageResolver;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerationRequest;
import com.github.ylyan2015.springaidemo.video.generator.VideoGenerator;
import com.github.ylyan2015.springaidemo.video.generator.VideoGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityScore;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽卡候选生成服务（Phase 4 切片 3）
 * 顺序生成 N 候选（成本可控）；单次失败 warn 跳过，全失败抛异常；
 * 评分启用时逐张评估；provisional=最高分（无评分取首张）暂回写主资产保证流水线流动，
 * 最终决策由人工 select 完成。一键编排路径不调用本服务（强制 N=1）。
 */
@Service
public class CandidateGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CandidateGenerationService.class);

    private static final String MOTION_SUFFIX = ", subtle motion, slow camera, no large deformation";

    private final ImageGeneratorFactory imageGeneratorFactory;
    private final VideoGeneratorFactory videoGeneratorFactory;
    private final StoryScriptRepository scriptRepository;
    private final StoryShotRepository shotRepository;
    private final UserRepository userRepository;
    private final ShotCandidateRepository candidateRepository;
    private final ShotPromptAssembler promptAssembler;
    private final ReferenceImageResolver referenceResolver;
    private final ImageQualityService qualityService;
    private final VideoGenerationService videoGenerationService;
    private final LoraResolver loraResolver;

    @Value("${video.candidates.image-count:3}")
    private int imageCount;

    @Value("${video.candidates.video-count:2}")
    private int videoCount;

    @Value("${video.wan.resolution:720P}")
    private String resolution;

    public CandidateGenerationService(ImageGeneratorFactory imageGeneratorFactory,
                                      VideoGeneratorFactory videoGeneratorFactory,
                                      StoryScriptRepository scriptRepository,
                                      StoryShotRepository shotRepository,
                                      UserRepository userRepository,
                                      ShotCandidateRepository candidateRepository,
                                      ShotPromptAssembler promptAssembler,
                                      ReferenceImageResolver referenceResolver,
                                      ImageQualityService qualityService,
                                      VideoGenerationService videoGenerationService,
                                      LoraResolver loraResolver) {
        this.imageGeneratorFactory = imageGeneratorFactory;
        this.videoGeneratorFactory = videoGeneratorFactory;
        this.scriptRepository = scriptRepository;
        this.shotRepository = shotRepository;
        this.userRepository = userRepository;
        this.candidateRepository = candidateRepository;
        this.promptAssembler = promptAssembler;
        this.referenceResolver = referenceResolver;
        this.qualityService = qualityService;
        this.videoGenerationService = videoGenerationService;
        this.loraResolver = loraResolver;
    }

    /** 图像抽卡：N 张顺序生成 + 评分 + 最高分 provisional 回写 */
    public List<ShotCandidate> generateImageCandidates(Long scriptId, Long shotId) {
        StoryScript script = requireOwnedScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        VisualStyle style = VisualStyle.fromName(script.getStyle());
        List<StoryCharacter> characters = referenceResolver.charactersForShot(shot);
        String prompt = promptAssembler.assemble(shot, style, characters);
        List<String> refs = referenceResolver.resolve(characters);
        LoraSpec lora = loraResolver.resolve(characters);
        ImageGenerator gen = imageGeneratorFactory.getActiveGenerator();

        List<ShotCandidate> candidates = new ArrayList<>();
        ShotCandidate best = null;
        int n = Math.max(1, imageCount);
        for (int i = 0; i < n; i++) {
            try {
                GeneratedImage img = lora != null && gen.supportsLora()
                        ? gen.generateWithLora(prompt, style.getNegativePrompt(), 1280, 720,
                                lora.loraName(), lora.controlNetRefUrl())
                        : refs != null && !refs.isEmpty() && gen.supportsReferences()
                        ? gen.generateWithReferences(prompt, style.getNegativePrompt(), 1280, 720, refs)
                        : gen.generate(prompt, style.getNegativePrompt(), 1280, 720);
                ShotCandidate c = new ShotCandidate();
                c.setShotId(shotId);
                c.setType("IMAGE");
                c.setUrl(img.getImageUrl());
                c.setFileId(img.getFileId());
                if (qualityService.isEnabled()) {
                    try {
                        ImageQualityScore score = qualityService.evaluate(img.getImageUrl(), prompt);
                        if (score != null) {
                            c.setQualityScore(score.getOverallScore());
                        }
                    } catch (Exception e) {
                        log.warn("Candidate {} quality evaluation failed: {}", i + 1, e.getMessage());
                    }
                }
                candidateRepository.save(c);
                candidates.add(c);
                if (best == null || higherScore(c, best)) {
                    best = c;
                }
            } catch (Exception e) {
                log.warn("Image candidate {}/{} failed, skipping: {}", i + 1, n, e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            throw new RuntimeException("图像候选全部生成失败");
        }
        // provisional 回写（前端标注"待择优"，最终由人工 select 决定）
        shot.setImageUrl(best.getUrl());
        shot.setImageFileId(best.getFileId());
        shot.setImageStatus("READY");
        shot.setQualityScore(best.getQualityScore());
        // 切片 2 M3 不变量：主图替换同步失效旧视频/尾帧（切片 3 审查 M2）
        shot.invalidateVideo();
        shotRepository.save(shot);
        log.info("Image candidates generated: shotId={}, count={}, provisional={}", shotId, candidates.size(), best.getUrl());
        return candidates;
    }

    /** 视频抽卡：N 段顺序生成 + 首张 provisional 回写（视频无质量评分） */
    public List<ShotCandidate> generateVideoCandidates(Long scriptId, Long shotId) {
        requireOwnedScript(scriptId);
        StoryShot shot = shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        VideoGenerator gen = videoGeneratorFactory.getActiveGenerator();
        if (gen == null || "none".equals(gen.getType())) {
            throw new RuntimeException("video.generator=none：未配置图生视频");
        }
        String firstFrame = videoGenerationService.resolveMediaUrl(shot.getImageUrl(), shot.getImageFileId());
        String base = shot.getImagePrompt() != null && !shot.getImagePrompt().isBlank()
                ? shot.getImagePrompt() : shot.getSceneDescription();
        String prompt = (base == null ? "" : base) + MOTION_SUFFIX;
        int duration = Math.max(2, Math.min(15,
                shot.getDurationSeconds() == null ? 5 : shot.getDurationSeconds()));

        List<ShotCandidate> candidates = new ArrayList<>();
        int n = Math.max(1, videoCount);
        for (int i = 0; i < n; i++) {
            try {
                GeneratedVideo video = gen.generate(
                        new VideoGenerationRequest(prompt, firstFrame, null, duration, resolution));
                ShotCandidate c = new ShotCandidate();
                c.setShotId(shotId);
                c.setType("VIDEO");
                c.setUrl(video.getVideoUrl());
                c.setFileId(video.getFileId());
                c.setDurationSeconds(video.getDurationSeconds());
                candidateRepository.save(c);
                candidates.add(c);
            } catch (Exception e) {
                log.warn("Video candidate {}/{} failed, skipping: {}", i + 1, n, e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            throw new RuntimeException("视频候选全部生成失败");
        }
        ShotCandidate best = candidates.get(0);
        videoGenerationService.applyVideoCandidate(shot, best.getUrl(), best.getFileId(),
                best.getDurationSeconds() == null ? duration : best.getDurationSeconds());
        log.info("Video candidates generated: shotId={}, count={}, provisional={}", shotId, candidates.size(), best.getUrl());
        return candidates;
    }

    private boolean higherScore(ShotCandidate a, ShotCandidate b) {
        if (a.getQualityScore() == null) {
            return false;
        }
        return b.getQualityScore() == null || a.getQualityScore() > b.getQualityScore();
    }

    // ==================== 私有辅助 ====================

    private StoryScript requireOwnedScript(Long scriptId) {
        Long userId = getCurrentUserId();
        return scriptRepository.findByIdAndUserId(scriptId, userId)
                .orElseThrow(() -> new RuntimeException("脚本不存在或无权访问: " + scriptId));
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