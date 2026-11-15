package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.ShotCandidate;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ShotCandidateRepository;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.storage.StorageService;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 镜头候选服务（Phase 4 切片 3）
 * 选择=人工闸门：select 归属校验后清同 type 旧 selected → 置新 → 回写主资产；
 * VIDEO 回写委托 VideoGenerationService.applyVideoCandidate（probe/末帧/READY）。
 */
@Service
public class ShotCandidateService {

    private static final Logger log = LoggerFactory.getLogger(ShotCandidateService.class);

    private final ShotCandidateRepository candidateRepository;
    private final StoryShotRepository shotRepository;
    private final StoryScriptRepository scriptRepository;
    private final UserRepository userRepository;
    private final VideoGenerationService videoGenerationService;
    private final ImageGeneratorFactory imageGeneratorFactory;
    private final StorageService storageService;

    public ShotCandidateService(ShotCandidateRepository candidateRepository,
                                StoryShotRepository shotRepository,
                                StoryScriptRepository scriptRepository,
                                UserRepository userRepository,
                                VideoGenerationService videoGenerationService,
                                ImageGeneratorFactory imageGeneratorFactory,
                                StorageService storageService) {
        this.candidateRepository = candidateRepository;
        this.shotRepository = shotRepository;
        this.scriptRepository = scriptRepository;
        this.userRepository = userRepository;
        this.videoGenerationService = videoGenerationService;
        this.imageGeneratorFactory = imageGeneratorFactory;
        this.storageService = storageService;
    }

    public List<ShotCandidate> listByShot(Long scriptId, Long shotId) {
        requireOwnedScript(scriptId);
        shotRepository.findByIdAndScriptId(shotId, scriptId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        return candidateRepository.findByShotIdOrderByIdAsc(shotId);
    }

    /** 人工择优选定：清同 type 旧 selected → 置新 → 回写 shot 主资产 */
    @Transactional
    public ShotCandidate select(Long scriptId, Long candidateId) {
        requireOwnedScript(scriptId);
        ShotCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new RuntimeException("候选不存在: " + candidateId));
        StoryShot shot = shotRepository.findByIdAndScriptId(candidate.getShotId(), scriptId)
                .orElseThrow(() -> new RuntimeException("候选不属于该脚本: " + candidateId));
        for (ShotCandidate other : candidateRepository.findByShotIdOrderByIdAsc(shot.getId())) {
            if (other.getType().equals(candidate.getType()) && other.isSelected()) {
                other.setSelected(false);
                candidateRepository.save(other);
            }
        }
        candidate.setSelected(true);
        candidateRepository.save(candidate);
        if ("VIDEO".equals(candidate.getType())) {
            // 长 I/O（下载/probe/抽帧）移出事务：提交后执行，避免占用连接池（切片 3 审查 m5）
            Runnable apply = () -> videoGenerationService.applyVideoCandidate(shot, candidate.getUrl(),
                    candidate.getFileId(), candidate.getDurationSeconds() == null ? 0 : candidate.getDurationSeconds());
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        apply.run();
                    }
                });
            } else {
                apply.run();
            }
        } else {
            boolean changed = !java.util.Objects.equals(shot.getImageUrl(), candidate.getUrl())
                    || !java.util.Objects.equals(shot.getImageFileId(), candidate.getFileId());
            shot.setImageUrl(candidate.getUrl());
            shot.setImageFileId(candidate.getFileId());
            shot.setImageStatus("READY");
            if (candidate.getQualityScore() != null) {
                shot.setQualityScore(candidate.getQualityScore());
            }
            if (changed) {
                // 切片 2 M3 不变量：主图替换同步失效旧视频/尾帧
                shot.invalidateVideo();
            }
            shotRepository.save(shot);
        }
        log.info("Candidate selected: id={}, shotId={}, type={}", candidateId, shot.getId(), candidate.getType());
        return candidate;
    }

    /** 候选 Map 装配（控制器/Agent 复用） */
    public static Map<String, Object> candidateToMap(ShotCandidate c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("shotId", c.getShotId());
        map.put("type", c.getType());
        map.put("url", c.getUrl());
        map.put("fileId", c.getFileId());
        map.put("qualityScore", c.getQualityScore());
        map.put("durationSeconds", c.getDurationSeconds());
        map.put("selected", c.isSelected());
        map.put("createdAt", c.getCreatedAt());
        return map;
    }

    /**
     * 局部重绘（切片 3 单一落点，控制器/Agent 均委托）：
     * 归属经 shot→script.userId 推导；坐标 0-1 越界/不支持 edit → IllegalArgumentException(400)；
     * 重绘产物入候选（selected=false）不直接覆盖主资产。
     */
    public ShotCandidate inpaint(Long shotId, double[] rect, String instruction) {
        StoryShot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new RuntimeException("镜头不存在: " + shotId));
        // 代理安全：用 scriptId 查仓库，不触 LAZY 关联（编排线程无 OSIV，切片 3 审查 M3）
        StoryScript script = shot.getScriptId() == null ? null
                : scriptRepository.findById(shot.getScriptId()).orElse(null);
        Long userId = getCurrentUserId();
        if (script == null || !userId.equals(script.getUserId())) {
            throw new RuntimeException("镜头不存在或无权访问: " + shotId);
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("重绘指令不能为空");
        }
        validateRect(rect);
        ImageGenerator active = imageGeneratorFactory.getActiveGenerator();
        if (active == null || !active.supportsEdit()) {
            throw new IllegalArgumentException("当前图像生成器不支持局部重绘，请切换为 wan27");
        }
        try {
            // SSRF 防护（切片 3 审查 C1）：无存储 fileId 的外部 URL 图片一律拒绝，不发起服务端抓取
            String url = shot.getImageUrl();
            boolean external = url != null && (url.startsWith("http://") || url.startsWith("https://"));
            if (external && (shot.getImageFileId() == null || shot.getImageFileId().isBlank())) {
                throw new IllegalArgumentException("重绘仅支持本应用存储的图片，请先重新生成图片");
            }
            byte[] bytes = downloadSourceImage(shot);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                throw new IllegalArgumentException("无法读取原图尺寸，重绘终止");
            }
            // 一律用存储字节转 base64 data URI，不把用户可控 URL 传给供应商
            String editSource = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            GeneratedImage result = active.editWithMask(instruction, editSource, rect, img.getWidth(), img.getHeight());
            ShotCandidate c = new ShotCandidate();
            c.setShotId(shotId);
            c.setType("IMAGE");
            c.setUrl(result.getImageUrl());
            c.setFileId(result.getFileId());
            candidateRepository.save(c);
            log.info("Inpaint candidate created: shotId={}, candidateId={}", shotId, c.getId());
            return c;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("局部重绘失败: " + e.getMessage(), e);
        }
    }

    /** 坐标校验：0≤x,y；w,h>0；x+w≤1；y+h≤1（含浮点容差） */
    private void validateRect(double[] rect) {
        if (rect == null || rect.length != 4) {
            throw new IllegalArgumentException("重绘坐标需为 [x,y,w,h] 四元组");
        }
        double x = rect[0], y = rect[1], w = rect[2], h = rect[3];
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.0 + 1e-9 || y + h > 1.0 + 1e-9) {
            throw new IllegalArgumentException("重绘坐标越界：需 0≤x,y、w,h>0 且 x+w≤1、y+h≤1");
        }
    }

    /** 原图字节：仅走本应用存储（SSRF 防护，拒绝外部 URL） */
    private byte[] downloadSourceImage(StoryShot shot) throws Exception {
        if (shot.getImageFileId() != null && !shot.getImageFileId().isBlank()) {
            try (InputStream in = storageService.download(shot.getImageFileId())) {
                return in.readAllBytes();
            }
        }
        throw new IllegalArgumentException("镜头无可用存储图片，无法重绘（请先重新生成图片）");
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