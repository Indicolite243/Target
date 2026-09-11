package com.stockmanager.assistant;

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

    @Test void executesAttributionToolAndStreamsGroundedAnswer() throws Exception {
        JdbcTemplate jdbc = preparedJdbc();
        AssistantAttributionToolService attribution = mock(AssistantAttributionToolService.class);
        when(attribution.getOrCreate(eq(7L), eq("id"), anyString()))
                .thenReturn(new AssistantAttributionToolService.FrozenAttribution(
                        "attribution-snapshot", "{\"available\":true}"));
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
        AssistantConversationService service = new AssistantConversationService(
                jdbc, gateway, null, attribution, null);
        try {
            service.ask(7, "id", "详细分析业绩归因", "trace");
            assertTrue(generated.await(2, TimeUnit.SECONDS));
            verify(attribution).getOrCreate(eq(7L), eq("id"), anyString());
            verify(jdbc).update(eq("UPDATE ai_message SET attribution_snapshot_id=? WHERE conversation_id=? AND request_id=?"),
                    eq("attribution-snapshot"), eq("id"), anyString());
            verify(jdbc, timeout(2000)).update(eq("UPDATE ai_message SET content=? WHERE id=? AND conversation_id=?"),
                    eq("归因分析完成。"), anyString(), eq("id"));
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
