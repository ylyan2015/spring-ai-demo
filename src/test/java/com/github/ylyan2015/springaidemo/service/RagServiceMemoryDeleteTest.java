package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RAG memory 模式删除测试（release 审查 M6：删除文档必须同步清除向量，否则删除承诺失效）
 */
class RagServiceMemoryDeleteTest {

    private VectorStore vectorStore;
    private RagService ragService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(VectorStore.class);
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        ragService = new RagService(vectorStore, repository);
        ReflectionTestUtils.setField(ragService, "storeType", "memory");
        // 直接种入注册表（上传链路依赖 Tika/Embedding，单测不覆盖）
        Map<String, RagService.DocInfo> registry =
                (Map<String, RagService.DocInfo>) ReflectionTestUtils.getField(ragService, "memoryRegistry");
        registry.put("doc-1", new RagService.DocInfo("doc-1", "secret.txt", 2, 100,
                "2026-01-01T00:00", List.of("chunk-a", "chunk-b")));
    }

    @Test
    void deleteRemovesVectorsAndRegistryEntry() {
        assertTrue(ragService.deleteDocument("doc-1"));
        verify(vectorStore).delete(List.of("chunk-a", "chunk-b"));
        assertFalse(ragService.hasDocuments());
        assertFalse(ragService.deleteDocument("doc-1"), "重复删除应返回 false");
    }

    @Test
    void deleteUnknownDocDoesNotTouchVectorStore() {
        assertFalse(ragService.deleteDocument("doc-999"));
        verify(vectorStore, never()).delete(anyList());
    }
}