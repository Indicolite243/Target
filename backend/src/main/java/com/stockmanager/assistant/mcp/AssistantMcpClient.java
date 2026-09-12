package com.stockmanager.assistant.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;

/** 对官方 MCP Client 的窄封装，便于测试 Agent 与协议边界。 */
public interface AssistantMcpClient {
    List<McpSchema.Tool> listTools();
    McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request);
}
