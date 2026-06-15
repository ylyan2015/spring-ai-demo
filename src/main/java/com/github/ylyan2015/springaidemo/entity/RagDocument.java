package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 知识库文档元信息实体
 * 持久化存储已上传文档的元数据和对应的向量分块ID，
 * 支持服务重启后恢复知识库文档列表并执行删除操作。
 */
@Entity
@Table(name = "rag_document")
@Data
@NoArgsConstructor
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文档唯一标识（如 doc-1, doc-2） */
    @Column(nullable = false, unique = true)
    private String docId;

    /** 原始文件名 */
    @Column(nullable = false)
    private String fileName;

    /** 分块数量 */
    private int chunkCount;

    /** 文件大小（字节） */
    private long fileSize;

    /** 上传时间 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * 向量库中对应的分块ID列表（JSON格式存储）
     * 用于删除文档时从向量库中移除对应的向量
     */
    @Column(columnDefinition = "TEXT")
    private String chunkIds;

    public RagDocument(String docId, String fileName, int chunkCount, long fileSize, String chunkIds) {
        this.docId = docId;
        this.fileName = fileName;
        this.chunkCount = chunkCount;
        this.fileSize = fileSize;
        this.uploadedAt = LocalDateTime.now();
        this.chunkIds = chunkIds;
    }
}
