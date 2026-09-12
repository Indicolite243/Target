package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantModelGateway;
import com.stockmanager.assistant.AssistantToolGateway;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 MCP tools/list 与 tools/call 适配成现有千问 Function Calling 所需的数据结构。 */
@Component
public class McpAssistantToolGateway implements AssistantToolGateway {
    static final String META_USER_ID = "target/userId";
    static final String META_CONVERSATION_ID = "target/conversationId";
    static final String META_REQUEST_ID = "target/requestId";
    static final String META_TRACE_ID = "target/traceId";

    private final AssistantMcpClient client;
    private final ObjectMapper mapper;
    private volatile List<AssistantModelGateway.ToolDefinition> cachedTools;

    public McpAssistantToolGateway(AssistantMcpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public List<AssistantModelGateway.ToolDefinition> tools() {
        List<AssistantModelGateway.ToolDefinition> existing = cachedTools;
        if (existing != null) return existing;
        try {
            List<AssistantModelGateway.ToolDefinition> discovered = client.listTools().stream()
                    .map(tool -> new AssistantModelGateway.ToolDefinition(
                            tool.name(), tool.description(), schema(tool.inputSchema())))
                    .toList();
            if (discovered.isEmpty())
                throw new AssistantMcpGatewayException("MCP 工具服务暂时不可用");
            cachedTools = List.copyOf(discovered);
            return cachedTools;
        } catch (AssistantMcpGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AssistantMcpGatewayException("MCP 工具服务暂时不可用", exception);
        }
    }

    @Override
    public ToolResult call(AssistantModelGateway.ToolCall call, InvocationContext context) {
        if (tools().stream().noneMatch(tool -> tool.name().equals(call.name())))
            throw new IllegalArgumentException("模型请求了未发现的 MCP 工具");
        Map<String, Object> arguments;
        try {
            arguments = mapper.readValue(
                call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(),
                new TypeReference<>() {});
        } catch (Exception exception) {
            throw new AssistantMcpGatewayException("MCP 工具参数格式异常", exception);
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(META_USER_ID, context.userId());
            metadata.put(META_CONVERSATION_ID, context.conversationId());
            metadata.put(META_REQUEST_ID, context.requestId());
            metadata.put(META_TRACE_ID, context.traceId() == null ? "" : context.traceId());
            McpSchema.CallToolResult response = client.callTool(
                    new McpSchema.CallToolRequest(call.name(), arguments, metadata));
            String content = text(response.content());
            if (Boolean.TRUE.equals(response.isError()))
                throw new AssistantMcpGatewayException(content.isBlank() ? "MCP 工具执行失败" : content);
            Map<String, Object> resultMetadata = response.structuredContent() == null
                    ? Map.of()
                    : mapper.convertValue(response.structuredContent(), new TypeReference<>() {});
            return new ToolResult(content, resultMetadata);
        } catch (AssistantMcpGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AssistantMcpGatewayException("MCP 工具服务暂时不可用", exception);
        }
    }

    private Map<String, Object> schema(McpSchema.JsonSchema schema) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", schema == null || schema.type() == null ? "object" : schema.type());
        value.put("properties", schema == null || schema.properties() == null ? Map.of() : schema.properties());
        if (schema != null && schema.required() != null && !schema.required().isEmpty())
            value.put("required", schema.required());
        value.put("additionalProperties", schema != null && Boolean.TRUE.equals(schema.additionalProperties()));
        return value;
    }

    private String text(List<McpSchema.Content> content) {
        List<String> parts = new ArrayList<>();
        if (content != null) for (McpSchema.Content item : content)
            if (item instanceof McpSchema.TextContent text && text.text() != null) parts.add(text.text());
        return String.join("\n", parts);
    }

    static final class AssistantMcpGatewayException extends RuntimeException {
        AssistantMcpGatewayException(String message) { super(message); }
        AssistantMcpGatewayException(String message, Throwable cause) { super(message, cause); }
    }
}
