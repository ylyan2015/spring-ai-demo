package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.ModelParamPreset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理控制器
 * 提供模型切换和查询功能
 */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private final ModelService modelService;
    private final UserRepository userRepository;

    public ModelController(ModelService modelService, UserRepository userRepository) {
        this.modelService = modelService;
        this.userRepository = userRepository;
    }

    /**
     * 获取当前激活的模型
     */
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentModel() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("model", modelService.getCurrentModel());
        result.put("modelName", modelService.getCurrentModelName());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有可用的模型列表
     */
    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("models", modelService.getAvailableModels());
        return ResponseEntity.ok(result);
    }

    /**
     * 切换模型
     */
    @PostMapping("/switch")
    public ResponseEntity<Map<String, Object>> switchModel(@RequestBody ModelSwitchRequest request) {
        try {
            modelService.switchModel(request.getModel());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "模型已切换到: " + modelService.getCurrentModelName());
            result.put("model", modelService.getCurrentModel());
            result.put("modelName", modelService.getCurrentModelName());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "切换失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 模型切换请求DTO
     */
    public static class ModelSwitchRequest {
        private String model;

        public ModelSwitchRequest() {
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    // ==================== 参数预设接口 ====================

    /**
     * 获取当前用户对某模型的参数预设
     */
    @GetMapping("/params/{modelKey}")
    public ResponseEntity<Map<String, Object>> getPreset(@PathVariable String modelKey) {
        Map<String, Object> result = new HashMap<>();
        Long userId = getCurrentUserId();
        if (userId == null) {
            result.put("success", false);
            result.put("message", "用户未登录");
            return ResponseEntity.status(401).body(result);
        }
        ModelParamPreset preset = modelService.getPreset(userId, modelKey);
        result.put("success", true);
        result.put("preset", preset);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取当前用户所有模型的参数预设
     */
    @GetMapping("/params")
    public ResponseEntity<Map<String, Object>> getAllPresets() {
        Map<String, Object> result = new HashMap<>();
        Long userId = getCurrentUserId();
        if (userId == null) {
            result.put("success", false);
            result.put("message", "用户未登录");
            return ResponseEntity.status(401).body(result);
        }
        List<ModelParamPreset> presets = modelService.getAllPresets(userId);
        result.put("success", true);
        result.put("presets", presets);
        return ResponseEntity.ok(result);
    }

    /**
     * 保存/更新参数预设
     */
    @PostMapping("/params/{modelKey}")
    public ResponseEntity<Map<String, Object>> savePreset(
            @PathVariable String modelKey,
            @RequestBody ModelParamPreset preset) {
        Map<String, Object> result = new HashMap<>();
        Long userId = getCurrentUserId();
        if (userId == null) {
            result.put("success", false);
            result.put("message", "用户未登录");
            return ResponseEntity.status(401).body(result);
        }
        preset.setUserId(userId);
        preset.setModelKey(modelKey);
        ModelParamPreset saved = modelService.savePreset(preset);
        result.put("success", true);
        result.put("message", "参数已保存");
        result.put("preset", saved);
        return ResponseEntity.ok(result);
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName())
                .map(User::getId).orElse(null);
    }
}
