package com.stockmanager.assistant;

import java.util.List;
import java.util.Map;

/** Agent 编排层与工具协议之间的边界；生产实现通过 MCP 发现并调用工具。 */
public interface AssistantToolGateway {
    record InvocationContext(long userId, String conversationId, String requestId, String traceId) {}
    record ToolResult(String content, Map<String, Object> metadata) {
        public ToolResult {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    List<AssistantModelGateway.ToolDefinition> tools();

    ToolResult call(AssistantModelGateway.ToolCall call, InvocationContext context);
}
