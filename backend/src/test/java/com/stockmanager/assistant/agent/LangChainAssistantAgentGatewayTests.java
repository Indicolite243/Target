package com.stockmanager.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.stockmanager.assistant.AssistantModelGateway;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LangChainAssistantAgentGatewayTests {
    @Test
    void sendsTrustedInvocationContextAndParsesAgentEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> payloads = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/assistant/agent/stream", exchange -> {
            assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            payloads.add(mapper.readTree(exchange.getRequestBody()));
            byte[] body = ("event: status\ndata: {\"phase\":\"READING_DATA\",\"message\":\"读取中\"}\n\n"
                    + "event: tool_result\ndata: {\"metadata\":{\"snapshotId\":\"snapshot-1\"}}\n\n"
                    + "event: delta\ndata: {\"text\":\"分析完成\"}\n\n"
                    + "event: done\ndata: {\"status\":\"COMPLETED\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            LangChainAssistantAgentGateway gateway = new LangChainAssistantAgentGateway(
                    mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1",
                    "test-token", 500);
            List<AssistantAgentGateway.AgentEvent> events = new ArrayList<>();
            TestCancellation cancellation = new TestCancellation();
            gateway.stream(List.of(
                            new AssistantModelGateway.ModelMessage("system", "只读"),
                            new AssistantModelGateway.ModelMessage("user", "分析持仓")),
                    new AssistantAgentGateway.InvocationContext(
                            7L, "conversation-1", "request-1", "trace-1"),
                    events::add, cancellation);

            assertEquals(1, payloads.size());
            JsonNode context = payloads.getFirst().path("context");
            assertEquals(7L, context.path("userId").asLong());
            assertEquals("conversation-1", context.path("conversationId").asText());
            assertEquals("request-1", context.path("requestId").asText());
            assertEquals("trace-1", context.path("traceId").asText());
            assertEquals(3, events.size());
            assertEquals("READING_DATA", events.getFirst().phase());
            assertEquals("snapshot-1", events.get(1).metadata().get("snapshotId"));
            assertEquals("分析完成", events.getLast().text());
            assertNotNull(cancellation.upstream);
        } finally {
            server.stop(0);
        }
    }

    private static final class TestCancellation implements AssistantModelGateway.Cancellation {
        private Closeable upstream;
        @Override public boolean cancelled() { return false; }
        @Override public void attach(Closeable closeable) { upstream = closeable; }
    }
}
