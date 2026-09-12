package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantModelGateway;
import com.stockmanager.assistant.AssistantToolGateway;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAssistantToolGatewayTests {
    @Test
    void discoversToolsAndKeepsIdentityOutOfModelSchema() {
        AssistantMcpClient client = mock(AssistantMcpClient.class);
        when(client.listTools()).thenReturn(List.of(new McpSchema.Tool(
                "portfolio", null, "读取持仓",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null),
                null, null, null)));
        McpAssistantToolGateway gateway = new McpAssistantToolGateway(client, new ObjectMapper());

        var tools = gateway.tools();

        assertEquals(1, tools.size());
        assertEquals("portfolio", tools.getFirst().name());
        assertFalse(tools.getFirst().parameters().toString().contains("userId"));
        verify(client, times(1)).listTools();
        gateway.tools();
        verifyNoMoreInteractions(client);
    }

    @Test
    void sendsTrustedIdentityAsMcpMetadataAndReturnsStructuredMetadata() {
        AssistantMcpClient client = mock(AssistantMcpClient.class);
        McpSchema.Tool definition = new McpSchema.Tool("knowledge", null, "检索",
                new McpSchema.JsonSchema("object", Map.of("query", Map.of("type", "string")),
                        List.of("query"), false, null, null), null, null, null);
        when(client.listTools()).thenReturn(List.of(definition));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("{\"available\":true}")), false,
                Map.of("knowledgeSnapshotId", "snapshot-1"), Map.of()));
        McpAssistantToolGateway gateway = new McpAssistantToolGateway(client, new ObjectMapper());

        AssistantToolGateway.ToolResult result = gateway.call(
                new AssistantModelGateway.ToolCall("call-1", "knowledge", "{\"query\":\"回撤\"}"),
                new AssistantToolGateway.InvocationContext(7L, "conversation-1", "request-1", "trace-1"));

        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertEquals("回撤", request.getValue().arguments().get("query"));
        assertEquals(7L, request.getValue().meta().get(McpAssistantToolGateway.META_USER_ID));
        assertEquals("conversation-1", request.getValue().meta().get(McpAssistantToolGateway.META_CONVERSATION_ID));
        assertEquals("snapshot-1", result.metadata().get("knowledgeSnapshotId"));
        assertEquals("{\"available\":true}", result.content());
    }
}
