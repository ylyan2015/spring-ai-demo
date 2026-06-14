package com.github.ylyan2015.springaidemo.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RAG 服务
 * 负责文档上传、分块、向量化存储，以及相似度检索
 */
@Service
public class RagService {

    private final VectorStore vectorStore;

    /** 记录已上传的文档元信息（文档ID -> 文档信息） */
    private final Map<String, DocInfo> documentRegistry = new ConcurrentHashMap<>();
    private final AtomicInteger docIdCounter = new AtomicInteger(0);

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 上传并处理文档：解析 -> 分块 -> 向量化 -> 存储
     *
     * @param file 上传的文件
     * @return 文档信息
     */
    public DocInfo uploadDocument(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String docId = "doc-" + docIdCounter.incrementAndGet();

        // 1. 使用 Tika 解析文档
        InputStreamResource resource = new InputStreamResource(file.getInputStream());
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // 2. 为每个 Document 添加元数据标记（便于按文档删除）
        for (Document doc : documents) {
            doc.getMetadata().put("docId", docId);
            doc.getMetadata().put("fileName", originalFilename);
        }

        // 3. 分块（TokenTextSplitter 默认配置）
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);

        // 4. 存入向量库（自动调用 EmbeddingModel 生成向量）
        vectorStore.add(chunks);

        // 5. 记录文档元信息
        DocInfo info = new DocInfo(docId, originalFilename, chunks.size(), file.getSize());
        documentRegistry.put(docId, info);

        System.out.println("✓ RAG 文档已上传: " + originalFilename + ", 分块数: " + chunks.size());
        return info;
    }

    /**
     * 根据用户问题检索相关文档片段
     *
     * @param query 用户问题
     * @param topK  返回的最相关片段数量
     * @return 相关文档片段列表
     */
    public List<String> searchRelevantContext(String query, int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.5)
                        .build()
        );
        return results.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已上传的文档列表
     */
    public List<DocInfo> listDocuments() {
        return new ArrayList<>(documentRegistry.values());
    }

    /**
     * 删除文档（从注册表中移除；向量库中的分块在 SimpleVectorStore 中无法按条件删除，
     * 实际生产中应使用支持删除的 VectorStore 如 PgVector）
     */
    public boolean deleteDocument(String docId) {
        DocInfo removed = documentRegistry.remove(docId);
        if (removed != null) {
            System.out.println("✓ RAG 文档已删除: " + removed.getFileName());
            return true;
        }
        return false;
    }

    /**
     * 是否有可用的知识库文档
     */
    public boolean hasDocuments() {
        return !documentRegistry.isEmpty();
    }

    /**
     * 文档元信息
     */
    public static class DocInfo {
        private final String docId;
        private final String fileName;
        private final int chunkCount;
        private final long fileSize;
        private final String uploadedAt;

        public DocInfo(String docId, String fileName, int chunkCount, long fileSize) {
            this.docId = docId;
            this.fileName = fileName;
            this.chunkCount = chunkCount;
            this.fileSize = fileSize;
            this.uploadedAt = java.time.LocalDateTime.now().toString();
        }

        public String getDocId() { return docId; }
        public String getFileName() { return fileName; }
        public int getChunkCount() { return chunkCount; }
        public long getFileSize() { return fileSize; }
        public String getUploadedAt() { return uploadedAt; }
    }
}

