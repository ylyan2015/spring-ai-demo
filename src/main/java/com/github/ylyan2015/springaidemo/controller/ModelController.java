package com.github.ylyan2015.springaidemo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型管理控制器
 * 提供模型切换和查询功能
 */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
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
}
