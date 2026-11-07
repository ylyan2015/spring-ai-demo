package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.dto.ImageGenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoScriptService videoScriptService;
    private final VideoComposeService videoComposeService;

    public VideoController(VideoScriptService videoScriptService,
                           VideoComposeService videoComposeService) {
        this.videoScriptService = videoScriptService;
        this.videoComposeService = videoComposeService;
    }

    /**
     * Generate storyboard script + image for the first shot
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateVideoScript(
            @RequestBody Map<String, String> request) {

        String idea = request.get("idea");
        Map<String, Object> result = new LinkedHashMap<>();

        if (idea == null || idea.isBlank()) {
            result.put("success", false);
            result.put("message", "创意想法不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            Storyboard storyboard = videoScriptService.generateScript(idea);

            ImageGenerateResponse firstImage = null;
            String imageError = null;
            if (storyboard.getShots() != null && !storyboard.getShots().isEmpty()) {
                try {
                    firstImage = videoScriptService.generateImageForShot(storyboard.getShots().get(0));
                } catch (Exception e) {
                    imageError = e.getMessage();
                    log.warn("First shot image generation failed: {}", e.getMessage());
                }
            }

            result.put("success", true);
            result.put("storyboard", storyboard);
            if (firstImage != null) {
                result.put("firstImage", imageToMap(firstImage));
            } else if (imageError != null) {
                result.put("imageError", imageError);
            }
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Script generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "脚本生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Generate image for a specific shot by index
     */
    @PostMapping("/generate-image")
    public ResponseEntity<Map<String, Object>> generateShotImage(
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new HashMap<>();
        try {
            String imagePrompt = (String) request.get("imagePrompt");
            if (imagePrompt == null || imagePrompt.isBlank()) {
                result.put("success", false);
                result.put("message", "图像提示词不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            Shot shot = new Shot();
            shot.setImagePrompt(imagePrompt);
            ImageGenerateResponse response = videoScriptService.generateImageForShot(shot);

            result.put("success", true);
            result.put("data", imageToMap(response));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "图像生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Generate images for all shots in a storyboard
     */
    @PostMapping("/generate-all-images")
    public ResponseEntity<Map<String, Object>> generateAllImages(
            @RequestBody Storyboard storyboard) {

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (storyboard.getShots() == null || storyboard.getShots().isEmpty()) {
                result.put("success", false);
                result.put("message", "分镜列表为空");
                return ResponseEntity.badRequest().body(result);
            }

            List<Map<String, Object>> images = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (int i = 0; i < storyboard.getShots().size(); i++) {
                Shot shot = storyboard.getShots().get(i);
                try {
                    log.info("Generating image for shot {}/{}", i + 1, storyboard.getShots().size());
                    ImageGenerateResponse resp = videoScriptService.generateImageForShot(shot);
                    images.add(imageToMap(resp));
                } catch (Exception e) {
                    log.warn("Shot {} image generation failed: {}", i, e.getMessage());
                    errors.add("Shot " + (i + 1) + ": " + e.getMessage());
                    images.add(null);
                }
            }

            result.put("success", true);
            result.put("images", images);
            result.put("errors", errors);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Batch image generation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "批量图片生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Compose a video from shot data (images + durations + narrations)
     */
    @PostMapping("/compose")
    public ResponseEntity<Map<String, Object>> composeVideo(
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) request.get("shots");

            if (shots == null || shots.isEmpty()) {
                result.put("success", false);
                result.put("message", "镜头列表不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            long missingImages = shots.stream()
                    .filter(s -> s.get("imageUrl") == null || ((String) s.get("imageUrl")).isBlank())
                    .count();
            if (missingImages > 0) {
                result.put("success", false);
                result.put("message", "有 " + missingImages + " 个镜头尚未生成图片");
                return ResponseEntity.badRequest().body(result);
            }

            log.info("Composing video with {} shots", shots.size());
            Map<String, Object> videoData = videoComposeService.compose(shots);

            result.put("success", true);
            result.put("video", videoData);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Video compose failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "视频合成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private Map<String, Object> imageToMap(ImageGenerateResponse resp) {
        Map<String, Object> map = new HashMap<>();
        map.put("imageUrl", resp.getImageUrl());
        map.put("prompt", resp.getPrompt());
        map.put("width", resp.getWidth());
        map.put("height", resp.getHeight());
        return map;
    }
}
