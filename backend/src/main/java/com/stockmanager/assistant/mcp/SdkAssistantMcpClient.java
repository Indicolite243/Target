package com.stockmanager.assistant.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;

/** 使用官方 SDK 连接内部 Streamable HTTP MCP 服务，并在首次调用时完成协议初始化。 */
@Component
public class SdkAssistantMcpClient implements AssistantMcpClient {
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String endpoint;
    private final String internalToken;
    private final Duration timeout;
    private volatile McpSyncClient client;

    public SdkAssistantMcpClient(ObjectMapper objectMapper,
            @Value("${app.assistant.mcp.base-url}") String baseUrl,
            @Value("${app.assistant.mcp.endpoint:/internal/mcp}") String endpoint,
            @Value("${app.assistant.mcp.internal-token}") String internalToken,
            @Value("${app.assistant.mcp.request-timeout-seconds:45}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.endpoint = InternalMcpTokenFilter.normalizeEndpoint(endpoint);
        this.internalToken = internalToken;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
    }

    @Override
    public List<McpSchema.Tool> listTools() {
        return current().listTools().tools();
    }

    @Override
    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        return current().callTool(request);
    }

    private McpSyncClient current() {
        McpSyncClient existing = client;
        if (existing != null && existing.isInitialized()) return existing;
        synchronized (this) {
            existing = client;
            if (existing != null && existing.isInitialized()) return existing;
            var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .endpoint(endpoint)
                    .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                    .customizeRequest(builder -> builder.header(InternalMcpTokenFilter.HEADER, internalToken))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            McpSyncClient created = McpClient.sync(transport)
                    .clientInfo(new McpSchema.Implementation("target-investment-agent", "1.0.0"))
                    .initializationTimeout(Duration.ofSeconds(10))
                    .requestTimeout(timeout)
                    .build();
            try {
                created.initialize();
                client = created;
                return created;
            } catch (RuntimeException exception) {
                created.closeGracefully();
                throw exception;
            }
        }
    }

    @PreDestroy
    void close() {
        McpSyncClient existing = client;
        if (existing != null) existing.closeGracefully();
    }
}
