package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.ShotCandidate;
import com.github.ylyan2015.springaidemo.service.CandidateGenerationService;
import com.github.ylyan2015.springaidemo.service.ShotCandidateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选抽卡与局部重绘控制器（Phase 4 切片 3，镜像 images idiom）
 * 归属校验在服务层；选择=人工闸门；inpaint 坐标越界/不支持 → 400。
 */
@RestController
public class ShotCandidateController {

    private static final Logger log = LoggerFactory.getLogger(ShotCandidateController.class);

    private final ShotCandidateService shotCandidateService;
    private final CandidateGenerationService candidateGenerationService;

    public ShotCandidateController(ShotCandidateService shotCandidateService,
                                   CandidateGenerationService candidateGenerationService) {
        this.shotCandidateService = shotCandidateService;
        this.candidateGenerationService = candidateGenerationService;
    }

    /** 候选列表 */
    @GetMapping("/api/video/candidates")
    public ResponseEntity<Map<String, Object>> list(@RequestParam Long scriptId, @RequestParam Long shotId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> data = shotCandidateService.listByShot(scriptId, shotId).stream()
                    .map(ShotCandidateService::candidateToMap).toList();
            result.put("success", true);
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 抽卡生成（同步返回候选列表；IMAGE|VIDEO 分派） */
    @PostMapping("/api/video/candidates/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long scriptId = ((Number) request.get("scriptId")).longValue();
            Long shotId = ((Number) request.get("shotId")).longValue();
            String type = String.valueOf(request.getOrDefault("type", "IMAGE")).toUpperCase();
            List<ShotCandidate> candidates = "VIDEO".equals(type)
                    ? candidateGenerationService.generateVideoCandidates(scriptId, shotId)
                    : candidateGenerationService.generateImageCandidates(scriptId, shotId);
            result.put("success", true);
            result.put("data", candidates.stream().map(ShotCandidateService::candidateToMap).toList());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.error("Candidate generation failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 人工择优选定（回写主资产） */
    @PostMapping("/api/video/candidates/{id}/select")
    public ResponseEntity<Map<String, Object>> select(@PathVariable Long id, @RequestParam Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ShotCandidate selected = shotCandidateService.select(scriptId, id);
            result.put("success", true);
            result.put("data", ShotCandidateService.candidateToMap(selected));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 框选局部重绘（产物入候选，不直接覆盖主资产） */
    @PostMapping("/api/video/images/inpaint")
    public ResponseEntity<Map<String, Object>> inpaint(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long shotId = ((Number) request.get("shotId")).longValue();
            double[] rect = new double[]{
                    ((Number) request.get("x")).doubleValue(),
                    ((Number) request.get("y")).doubleValue(),
                    ((Number) request.get("w")).doubleValue(),
                    ((Number) request.get("h")).doubleValue()};
            String instruction = (String) request.get("instruction");
            ShotCandidate candidate = shotCandidateService.inpaint(shotId, rect, instruction);
            result.put("success", true);
            result.put("data", ShotCandidateService.candidateToMap(candidate));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (RuntimeException e) {
            log.error("Inpaint failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}