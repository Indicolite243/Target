package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantKnowledgeServiceTests {
    @Test
    void hybridSearchAlwaysScopesChunksAndDocumentsToCurrentUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantEmbeddingGateway embeddings = (inputs, traceId) ->
                new AssistantEmbeddingGateway.EmbeddingBatch("text-embedding-v4", 2,
                        List.of(List.of(1d, 0d)));
        when(jdbc.queryForList(anyString(), eq(42L), eq(42L), eq(5000))).thenReturn(List.of(
                Map.of("id", "chunk-1", "document_id", "doc-1", "chunk_index", 0,
                        "content", "最大回撤用于描述净值从峰值到谷底的最大跌幅。",
                        "embedding", "[1.0,0.0]", "original_name", "风险手册.md",
                        "embedding_model", "text-embedding-v4")));

        AssistantKnowledgeService.SearchResult result = new AssistantKnowledgeService(
                jdbc, new ObjectMapper(), embeddings, "runtime/test-knowledge")
                .search(42L, "什么是最大回撤", 6, "trace-1");

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().getFirst().citation()).isEqualTo("K1");
        assertThat(result.evidence().getFirst().documentName()).isEqualTo("风险手册.md");
        verify(jdbc).queryForList(anyString(), eq(42L), eq(42L), eq(5000));
    }
}
