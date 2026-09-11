package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantKnowledgeToolServiceTests {
    @Test
    void freezesRetrievedEvidenceWithCitationLabels() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantKnowledgeService knowledge = mock(AssistantKnowledgeService.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("conversation-1"), eq(42L))).thenReturn(1);
        when(knowledge.search(42L, "最大回撤", 6, "trace-1")).thenReturn(
                new AssistantKnowledgeService.SearchResult("text-embedding-v4", List.of(
                        new AssistantKnowledgeService.Evidence("K1", "doc-1", "风险手册.md", 0,
                                "最大回撤是峰值到谷底的跌幅。", 0.032))));

        AssistantKnowledgeToolService.KnowledgeContext result = new AssistantKnowledgeToolService(
                jdbc, new ObjectMapper(), knowledge).retrieve(42L, "conversation-1",
                "{\"query\":\"最大回撤\"}", "trace-1");

        assertThat(result.modelJson()).contains("K1", "风险手册.md", "RRF");
        verify(knowledge).search(42L, "最大回撤", 6, "trace-1");
        verify(jdbc).update(contains("INSERT INTO ai_data_snapshot"), any(Object[].class));
    }

    @Test
    void refusesToAttachKnowledgeEvidenceToAnotherUsersConversation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantKnowledgeService knowledge = mock(AssistantKnowledgeService.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("other-conversation"), eq(42L))).thenReturn(0);
        AssistantKnowledgeToolService service = new AssistantKnowledgeToolService(jdbc, new ObjectMapper(), knowledge);

        assertThatThrownBy(() -> service.retrieve(42L, "other-conversation",
                "{\"query\":\"最大回撤\"}", "trace-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("助手会话不存在");
        verifyNoInteractions(knowledge);
    }
}
