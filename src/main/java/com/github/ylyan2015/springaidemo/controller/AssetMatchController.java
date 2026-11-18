package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.AssetMatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 素材库自动推荐端点（spec §2.2）：触发匹配返回候选，不写库——确认走 updateShot.referenceAssetIds
 */
@RestController
@RequestMapping("/api/video/scripts")
public class AssetMatchController {

    private final AssetMatchService assetMatchService;

    public AssetMatchController(AssetMatchService assetMatchService) {
        this.assetMatchService = assetMatchService;
    }

    @PostMapping("/{scriptId}/asset-matches")
    public ResponseEntity<Map<String, Object>> match(@PathVariable Long scriptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("data", assetMatchService.match(scriptId));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
