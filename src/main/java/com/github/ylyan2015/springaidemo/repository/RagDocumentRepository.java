package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * RAG 知识库文档元信息仓库
 * 用于持久化文档元数据，支持按 docId 查询和删除
 */
@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    Optional<RagDocument> findByDocId(String docId);

    void deleteByDocId(String docId);
}
