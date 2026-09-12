package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 暴露受内部令牌保护的 Streamable HTTP MCP Server。 */
@Configuration
public class AssistantMcpServerConfiguration {
    @Bean
    McpJsonMapper assistantMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    HttpServletStreamableServerTransportProvider assistantMcpTransport(McpJsonMapper mapper,
            @Value("${app.assistant.mcp.endpoint:/internal/mcp}") String endpoint) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint(InternalMcpTokenFilter.normalizeEndpoint(endpoint))
                .disallowDelete(false)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> assistantMcpServlet(
            HttpServletStreamableServerTransportProvider transport,
            @Value("${app.assistant.mcp.endpoint:/internal/mcp}") String endpoint) {
        String normalizedEndpoint = InternalMcpTokenFilter.normalizeEndpoint(endpoint);
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, normalizedEndpoint, normalizedEndpoint + "/*");
        registration.setName("assistantMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer assistantMcpServer(HttpServletStreamableServerTransportProvider transport,
                                     AssistantMcpToolRegistry registry) {
        return McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("target-investment-tools", "1.0.0"))
                .instructions("只读投研数据工具；用户身份由可信 MCP Client 元数据提供")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .requestTimeout(Duration.ofSeconds(45))
                .tools(registry.tools())
                .build();
    }
}
