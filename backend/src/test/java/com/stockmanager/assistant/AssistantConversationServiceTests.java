package com.stockmanager.assistant;

import com.stockmanager.assistant.agent.AssistantAgentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.stockmanager.common.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantConversationServiceTests {
    @Test void preventsReadingAnotherUsersMessages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("other"), eq(7L))).thenReturn(0);
        var error = assertThrows(BusinessException.class,
                () -> new AssistantConversationService(jdbc).messages(7, "other"));
        assertEquals(404, error.getStatus().value());
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq("other"), eq(7L));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void rejectsBlankTitleBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(BusinessException.class, () -> new AssistantConversationService(jdbc).rename(7, "id", "  "));
        verifyNoInteractions(jdbc);
    }
    @Test void deletesOnlyWithinAuthenticatedUserScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(BusinessException.class, () -> new AssistantConversationService(jdbc).delete(7, "id"));
        verify(jdbc).update("DELETE FROM ai_conversation WHERE id=? AND user_id=?", "id", 7L);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void completedPairIsPersistedAndPartialTextIsNotLost() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        CountDownLatch generated = new CountDownLatch(1);
        AssistantModelGateway gateway = (messages, tools, traceId, consumer, cancellation) -> {
            assertEquals("system", messages.getFirst().role());
            assertEquals("当前组合怎么样", messages.getLast().content());
            consumer.accept(new AssistantModelGateway.ModelEvent("delta", "尚未接入账户数据。", null, null));
            generated.countDown();
            return new AssistantModelGateway.StreamResult(List.of());
        };
        AssistantConversationService service = new AssistantConversationService(jdbc, gateway);
        try {
            service.ask(7, "id", "当前组合怎么样", "trace");
            assertTrue(generated.await(2, TimeUnit.SECONDS));
            verify(jdbc, timeout(2000)).update(eq("UPDATE ai_message SET status=? WHERE id IN (?,?) AND conversation_id=?"),
                    eq("COMPLETED"), anyString(), anyString(), eq("id"));
            verify(jdbc, atLeastOnce()).update(eq("UPDATE ai_message SET content=? WHERE id=? AND conversation_id=?"),
                    eq("尚未接入账户数据。"), anyString(), eq("id"));
        } finally {
            service.shutdown();
        }
    }

    @Test void cancellationClosesUpstreamAndPersistsIncompleteState() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AssistantModelGateway gateway = (messages, tools, traceId, consumer, cancellation) -> {
            cancellation.attach(() -> { released.countDown(); });
            attached.countDown();
            try { released.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new AssistantModelGateway.StreamResult(List.of());
        };
        AssistantConversationService service = new AssistantConversationService(jdbc, gateway);
        try {
            service.ask(7, "id", "请分析", "trace");
            assertTrue(attached.await(2, TimeUnit.SECONDS));
            assertTrue(service.cancel(7, "id"));
            assertTrue(released.await(2, TimeUnit.SECONDS));
            verify(jdbc).update(eq("UPDATE ai_message SET status=? WHERE id IN (?,?) AND conversation_id=?"),
                    eq("CANCELLED"), anyString(), anyString(), eq("id"));
            assertFalse(service.cancel(7, "id"));
        } finally {
            service.shutdown();
        }
    }

    @Test void rejectsEmptyQuestionBeforeCreatingMessages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantConversationService service = new AssistantConversationService(jdbc, mock(AssistantModelGateway.class));
        try {
            assertThrows(BusinessException.class, () -> service.ask(7, "id", " ", "trace"));
            verifyNoInteractions(jdbc);
        } finally {
            service.shutdown();
        }
    }

    @Test void langChainAgentStreamsAnswerAndPersistsToolMetadata() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        CountDownLatch generated = new CountDownLatch(1);
        AssistantAgentGateway agentGateway = (messages, context, consumer, cancellation) -> {
            assertEquals(7L, context.userId());
            assertEquals("id", context.conversationId());
            assertEquals("当前组合怎么样", messages.getLast().content());
            consumer.accept(new AssistantAgentGateway.AgentEvent(
                    "tool_result", null, null, null, java.util.Map.of("snapshotId", "snapshot-lc")));
            consumer.accept(new AssistantAgentGateway.AgentEvent(
                    "delta", "LangChain 分析完成。", null, null, java.util.Map.of()));
            generated.countDown();
        };
        AssistantConversationService service = new AssistantConversationService(
                jdbc, mock(AssistantModelGateway.class), mock(AssistantToolGateway.class), agentGateway, "langchain");
        try {
            service.ask(7, "id", "当前组合怎么样", "trace");
            assertTrue(generated.await(2, TimeUnit.SECONDS));
            verify(jdbc).update(eq("UPDATE ai_message SET snapshot_id=? WHERE conversation_id=? AND request_id=?"),
                    eq("snapshot-lc"), eq("id"), anyString());
            verify(jdbc, timeout(2000)).update(eq("UPDATE ai_message SET content=? WHERE id=? AND conversation_id=?"),
                    eq("LangChain 分析完成。"), anyString(), eq("id"));
            verify(jdbc, timeout(2000)).update(eq("UPDATE ai_message SET status=? WHERE id IN (?,?) AND conversation_id=?"),
                    eq("COMPLETED"), anyString(), anyString(), eq("id"));
        } finally {
            service.shutdown();
        }
    }

    @Test void executesAttributionToolAndStreamsGroundedAnswer() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        AssistantToolGateway toolGateway = mock(AssistantToolGateway.class);
        when(toolGateway.tools()).thenReturn(List.of(new AssistantModelGateway.ToolDefinition(
                "get_performance_attribution", "归因", java.util.Map.of("type", "object"))));
        when(toolGateway.call(any(), any())).thenReturn(new AssistantToolGateway.ToolResult(
                "{\"available\":true}", java.util.Map.of("attributionSnapshotId", "attribution-snapshot")));
        CountDownLatch generated = new CountDownLatch(1);
        AtomicInteger round = new AtomicInteger();
        AssistantModelGateway gateway = (messages, tools, traceId, consumer, cancellation) -> {
            if (round.getAndIncrement() == 0) {
                assertTrue(tools.stream().anyMatch(tool -> "get_performance_attribution".equals(tool.name())));
                return new AssistantModelGateway.StreamResult(List.of(
                        new AssistantModelGateway.ToolCall("call-1", "get_performance_attribution", "{}")));
            }
            assertEquals("tool", messages.getLast().role());
            consumer.accept(new AssistantModelGateway.ModelEvent("delta", "归因分析完成。", null, null));
            generated.countDown();
            return new AssistantModelGateway.StreamResult(List.of());
        };
        AssistantConversationService service = new AssistantConversationService(jdbc, gateway, toolGateway);
        try {
            service.ask(7, "id", "详细分析业绩归因", "trace");
            assertTrue(generated.await(2, TimeUnit.SECONDS));
            verify(toolGateway).call(argThat(call -> "get_performance_attribution".equals(call.name())),
                    argThat(context -> context.userId() == 7L && "id".equals(context.conversationId())));
            verify(jdbc).update(eq("UPDATE ai_message SET attribution_snapshot_id=? WHERE conversation_id=? AND request_id=?"),
                    eq("attribution-snapshot"), eq("id"), anyString());
            verify(jdbc, timeout(2000)).update(eq("UPDATE ai_message SET content=? WHERE id=? AND conversation_id=?"),
                    eq("归因分析完成。"), anyString(), eq("id"));
        } finally {
            service.shutdown();
        }
    }

    @Test void executesPrivateKnowledgeSearchAndAttachesEvidenceSnapshot() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        AssistantToolGateway toolGateway = mock(AssistantToolGateway.class);
        when(toolGateway.tools()).thenReturn(List.of(new AssistantModelGateway.ToolDefinition(
                "search_private_knowledge_base", "知识检索", java.util.Map.of("type", "object"))));
        when(toolGateway.call(any(), any())).thenReturn(new AssistantToolGateway.ToolResult(
                "{\"available\":true,\"evidence\":[]}",
                java.util.Map.of("knowledgeSnapshotId", "knowledge-snapshot")));
        CountDownLatch generated = new CountDownLatch(1);
        AtomicInteger round = new AtomicInteger();
        AssistantModelGateway gateway = (messages, tools, traceId, consumer, cancellation) -> {
            if (round.getAndIncrement() == 0) {
                assertTrue(tools.stream().anyMatch(tool -> "search_private_knowledge_base".equals(tool.name())));
                return new AssistantModelGateway.StreamResult(List.of(
                        new AssistantModelGateway.ToolCall("call-k1", "search_private_knowledge_base",
                                "{\"query\":\"最大回撤\"}")));
            }
            consumer.accept(new AssistantModelGateway.ModelEvent("delta", "根据文档[K1]。", null, null));
            generated.countDown();
            return new AssistantModelGateway.StreamResult(List.of());
        };
        AssistantConversationService service = new AssistantConversationService(jdbc, gateway, toolGateway);
        try {
            service.ask(7, "id", "按我的文档解释最大回撤", "trace");
            assertTrue(generated.await(2, TimeUnit.SECONDS));
            verify(toolGateway).call(argThat(call -> call.arguments().contains("最大回撤")),
                    argThat(context -> context.userId() == 7L && "id".equals(context.conversationId())));
            verify(jdbc).update(eq("UPDATE ai_message SET knowledge_snapshot_id=? WHERE conversation_id=? AND request_id=?"),
                    eq("knowledge-snapshot"), eq("id"), anyString());
        } finally {
            service.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private JdbcTemplate preparedJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("id"), eq(7L))).thenReturn(1);
        when(jdbc.query(contains("SELECT m.role"), any(RowMapper.class), eq("id"))).thenReturn(List.of());
        return jdbc;
    }
}
