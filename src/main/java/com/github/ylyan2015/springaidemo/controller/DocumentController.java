package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库文档管理控制器
 * 支持文档上传、列表查询、删除操作
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final RagService ragService;

    public DocumentController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 上传文档到知识库
     * 支持 TXT、PDF、MD 等格式
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("message", "文件不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            RagService.DocInfo info = ragService.uploadDocument(file);
            result.put("success", true);
            result.put("message", "文档上传成功");
            result.put("document", docInfoToMap(info));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "文档上传失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取所有已上传的文档列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listDocuments() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> docs = ragService.listDocuments().stream()
                .map(this::docInfoToMap)
                .collect(Collectors.toList());
        result.put("success", true);
        result.put("documents", docs);
        result.put("count", docs.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定文档
     */
    @DeleteMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String docId) {
        Map<String, Object> result = new HashMap<>();
        boolean deleted = ragService.deleteDocument(docId);
        result.put("success", deleted);
        result.put("message", deleted ? "文档已删除" : "文档不存在");
        return deleted ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    private Map<String, Object> docInfoToMap(RagService.DocInfo info) {
        Map<String, Object> map = new HashMap<>();
        map.put("docId", info.getDocId());
        map.put("fileName", info.getFileName());
        map.put("chunkCount", info.getChunkCount());
        map.put("fileSize", info.getFileSize());
        map.put("uploadedAt", info.getUploadedAt());
        return map;
    }
}
