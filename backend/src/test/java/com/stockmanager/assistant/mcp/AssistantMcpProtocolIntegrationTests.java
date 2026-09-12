package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantAttributionToolService;
import com.stockmanager.assistant.AssistantBacktestToolService;
import com.stockmanager.assistant.AssistantKnowledgeToolService;
import com.stockmanager.assistant.AssistantPortfolioSnapshotService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AssistantMcpProtocolIntegrationTests.TestApplication.class,
        properties = "app.assistant.mcp.endpoint=/internal/mcp")
class AssistantMcpProtocolIntegrationTests {
    @LocalServerPort int port;
    @Autowired AssistantPortfolioSnapshotService portfolio;
    @Autowired ObjectMapper mapper;

    @Test
    void initializesDiscoversAndCallsToolOverStreamableHttp() {
        when(portfolio.getOrCreate(7L, "conversation-1"))
                .thenReturn(new AssistantPortfolioSnapshotService.FrozenSnapshot(
                        "snapshot-1", "{\"available\":true}"));
        var client = new SdkAssistantMcpClient(
                mapper, "http://127.0.0.1:" + port, "/internal/mcp", "test-token", 10);
        try {
            assertEquals(5, client.listTools().size());

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "get_current_portfolio_snapshot", Map.of(), Map.of(
                    McpAssistantToolGateway.META_USER_ID, 7L,
                    McpAssistantToolGateway.META_CONVERSATION_ID, "conversation-1",
                    McpAssistantToolGateway.META_REQUEST_ID, "request-1",
                    McpAssistantToolGateway.META_TRACE_ID, "trace-1")));

            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertEquals("snapshot-1", ((Map<?, ?>) result.structuredContent()).get("snapshotId"));
            verify(portfolio).getOrCreate(7L, "conversation-1");
        } finally {
            client.close();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            RedisAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    })
    @Import({AssistantMcpServerConfiguration.class, AssistantMcpToolRegistry.class})
    static class TestApplication {
        @Bean AssistantPortfolioSnapshotService portfolio() { return mock(AssistantPortfolioSnapshotService.class); }
        @Bean AssistantAttributionToolService attribution() { return mock(AssistantAttributionToolService.class); }
        @Bean AssistantBacktestToolService backtest() { return mock(AssistantBacktestToolService.class); }
        @Bean AssistantKnowledgeToolService knowledge() { return mock(AssistantKnowledgeToolService.class); }
        @Bean
        FilterRegistrationBean<InternalMcpTokenFilter> mcpTokenFilter() {
            var registration = new FilterRegistrationBean<>(
                    new InternalMcpTokenFilter("test-token", "/internal/mcp"));
            registration.addUrlPatterns("/internal/mcp", "/internal/mcp/*");
            registration.setOrder(-100);
            return registration;
        }
    }
}
