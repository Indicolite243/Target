package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantAttributionToolService;
import com.stockmanager.assistant.AssistantBacktestToolService;
import com.stockmanager.assistant.AssistantKnowledgeToolService;
import com.stockmanager.assistant.AssistantPortfolioSnapshotService;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantMcpToolRegistryTests {
    @Test
    void exposesFiveToolsAndUsesTrustedMetadataForPortfolioCall() {
        ObjectMapper mapper = new ObjectMapper();
        AssistantPortfolioSnapshotService portfolio = mock(AssistantPortfolioSnapshotService.class);
        when(portfolio.getOrCreate(7L, "conversation-1"))
                .thenReturn(new AssistantPortfolioSnapshotService.FrozenSnapshot(
                        "snapshot-1", "{\"available\":true}"));
        AssistantMcpToolRegistry registry = new AssistantMcpToolRegistry(mapper,
                new JacksonMcpJsonMapper(mapper), portfolio,
                mock(AssistantAttributionToolService.class), mock(AssistantBacktestToolService.class),
                mock(AssistantKnowledgeToolService.class));

        List<McpServerFeatures.SyncToolSpecification> tools = registry.tools();
        assertEquals(5, tools.size());
        McpServerFeatures.SyncToolSpecification selected = tools.stream()
                .filter(tool -> tool.tool().name().equals("get_current_portfolio_snapshot"))
                .findFirst().orElseThrow();
        McpSchema.CallToolResult result = selected.callHandler().apply(null,
                new McpSchema.CallToolRequest(selected.tool().name(), Map.of(), Map.of(
                        McpAssistantToolGateway.META_USER_ID, 7L,
                        McpAssistantToolGateway.META_CONVERSATION_ID, "conversation-1",
                        McpAssistantToolGateway.META_REQUEST_ID, "request-1",
                        McpAssistantToolGateway.META_TRACE_ID, "trace-1")));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("snapshot-1", ((Map<?, ?>) result.structuredContent()).get("snapshotId"));
        verify(portfolio).getOrCreate(7L, "conversation-1");
    }

    @Test
    void rejectsCallsWithoutTrustedIdentityMetadata() {
        ObjectMapper mapper = new ObjectMapper();
        AssistantPortfolioSnapshotService portfolio = mock(AssistantPortfolioSnapshotService.class);
        AssistantMcpToolRegistry registry = new AssistantMcpToolRegistry(mapper,
                new JacksonMcpJsonMapper(mapper), portfolio,
                mock(AssistantAttributionToolService.class), mock(AssistantBacktestToolService.class),
                mock(AssistantKnowledgeToolService.class));
        McpServerFeatures.SyncToolSpecification selected = registry.tools().getFirst();

        McpSchema.CallToolResult result = selected.callHandler().apply(null,
                new McpSchema.CallToolRequest(selected.tool().name(), Map.of()));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        verifyNoInteractions(portfolio);
    }
}
