package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.RagDocument;
import com.github.ylyan2015.springaidemo.repository.RagDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 服务
 * 负责文档上传、分块、向量化存储，以及相似度检索。
 * 支持两种存储模式：
 * - memory（默认）：内存向量库 + 内存文档注册表，服务重启后清空
 * - pgvector：PgVector 持久化向量库 + JPA 文档注册表，服务重启后数据保留
 */
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final RagDocumentRepository ragDocumentRepository;

    /** 存储模式：memory 或 pgvector */
    @Value("${rag.store-type:memory}")
    private String storeType;

    /** 内存模式下的文档注册表（仅 memory 模式使用） */
    private final Map<String, DocInfo> memoryRegistry = new ConcurrentHashMap<>();
    private final AtomicInteger docIdCounter = new AtomicInteger(0);

    public RagService(VectorStore vectorStore, RagDocumentRepository ragDocumentRepository) {
        this.vectorStore = vectorStore;
        this.ragDocumentRepository = ragDocumentRepository;
    }

    /**
     * 初始化 docIdCounter：持久化模式下从数据库已有记录中恢复最大编号，
     * 避免服务重启后生成重复的 docId
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        if ("pgvector".equalsIgnoreCase(storeType)) {
            List<RagDocument> existing = ragDocumentRepository.findAll();
            int maxId = existing.stream()
                    .map(RagDocument::getDocId)
                    .mapToInt(id -> {
                        Matcher m = Pattern.compile("doc-(\\d+)").matcher(id);
                        return m.find() ? Integer.parseInt(m.group(1)) : 0;
                    })
                    .max()
                    .orElse(0);
            docIdCounter.set(maxId);
            System.out.println("✓ RAG docIdCounter 已从数据库恢复，当前最大值: " + maxId
                    + ", 已有文档数: " + existing.size());
        }
    }

    /**
     * 上传并处理文档：解析 -> 分块 -> 向量化 -> 存储
     *
     * @param file 上传的文件
     * @return 文档信息
     */
    @Transactional
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

        // 5. 收集分块ID（用于后续删除）
        String chunkIdsJson = chunks.stream()
                .map(Document::getId)
                .collect(Collectors.joining(","));

        // 6. 持久化或缓存文档元信息
        DocInfo info = new DocInfo(docId, originalFilename, chunks.size(), file.getSize());
        if ("pgvector".equalsIgnoreCase(storeType)) {
            RagDocument entity = new RagDocument(docId, originalFilename, chunks.size(), file.getSize(), chunkIdsJson);
            ragDocumentRepository.save(entity);
        } else {
            memoryRegistry.put(docId, info);
        }

        System.out.println("✓ RAG 文档已上传: " + originalFilename + ", 分块数: " + chunks.size()
                + " [" + storeType + "模式]");
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
        if ("pgvector".equalsIgnoreCase(storeType)) {
            return ragDocumentRepository.findAll().stream()
                    .map(entity -> new DocInfo(
                            entity.getDocId(),
                            entity.getFileName(),
                            entity.getChunkCount(),
                            entity.getFileSize(),
                            entity.getUploadedAt() != null ? entity.getUploadedAt().toString() : ""))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(memoryRegistry.values());
    }

    /**
     * 删除文档：从向量库中移除分块向量，并从注册表中删除元信息
     */
    @Transactional
    public boolean deleteDocument(String docId) {
        if ("pgvector".equalsIgnoreCase(storeType)) {
            return deleteDocumentPersistent(docId);
        }
        return deleteDocumentMemory(docId);
    }

    /**
     * 持久化模式下的文档删除：从向量库和数据库同时移除
     */
    private boolean deleteDocumentPersistent(String docId) {
        Optional<RagDocument> opt = ragDocumentRepository.findByDocId(docId);
        if (opt.isEmpty()) {
            return false;
        }
        RagDocument entity = opt.get();

        // 1. 从向量库中删除对应的分块向量
        if (entity.getChunkIds() != null && !entity.getChunkIds().isEmpty()) {
            List<String> chunkIds = Arrays.asList(entity.getChunkIds().split(","));
            vectorStore.delete(chunkIds);
            System.out.println("✓ PgVector 已删除 " + chunkIds.size() + " 个分块向量");
        }

        // 2. 从数据库删除元信息
        ragDocumentRepository.delete(entity);
        System.out.println("✓ RAG 文档已删除 [持久化模式]: " + entity.getFileName());
        return true;
    }

    /**
     * 内存模式下的文档删除
     */
    private boolean deleteDocumentMemory(String docId) {
        DocInfo removed = memoryRegistry.remove(docId);
        if (removed != null) {
            System.out.println("✓ RAG 文档已删除 [内存模式]: " + removed.getFileName());
            return true;
        }
        return false;
    }

    /**
     * 是否有可用的知识库文档
     */
    public boolean hasDocuments() {
        if ("pgvector".equalsIgnoreCase(storeType)) {
            return ragDocumentRepository.count() > 0;
        }
        return !memoryRegistry.isEmpty();
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
            this(docId, fileName, chunkCount, fileSize, java.time.LocalDateTime.now().toString());
        }

        public DocInfo(String docId, String fileName, int chunkCount, long fileSize, String uploadedAt) {
            this.docId = docId;
            this.fileName = fileName;
            this.chunkCount = chunkCount;
            this.fileSize = fileSize;
            this.uploadedAt = uploadedAt;
        }

        public String getDocId() { return docId; }
        public String getFileName() { return fileName; }
        public int getChunkCount() { return chunkCount; }
        public long getFileSize() { return fileSize; }
        public String getUploadedAt() { return uploadedAt; }
    }
}
