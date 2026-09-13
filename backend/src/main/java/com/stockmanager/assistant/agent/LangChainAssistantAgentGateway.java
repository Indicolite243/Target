package com.stockmanager.assistant.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantModelGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 把 FastAPI LangChain Agent 事件转成 Java 内部事件。 */
@Component
public class LangChainAssistantAgentGateway implements AssistantAgentGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String internalToken;

    public LangChainAssistantAgentGateway(ObjectMapper mapper,
            @Value("${app.quant-service.base-url}") String baseUrl,
            @Value("${app.quant-service.internal-token}") String internalToken,
            @Value("${app.quant-service.connect-timeout-ms:500}") int connectTimeoutMs) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/assistant/agent/stream");
        this.internalToken = internalToken;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 100))).build();
    }

    @Override
    public void stream(List<AssistantModelGateway.ModelMessage> messages, InvocationContext context,
                       Consumer<AgentEvent> consumer, AssistantModelGateway.Cancellation cancellation) {
        InputStream body = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messages", messages.stream().map(this::messagePayload).toList());
            payload.put("context", Map.of(
                    "userId", context.userId(),
                    "conversationId", context.conversationId(),
                    "requestId", context.requestId(),
                    "traceId", context.traceId() == null ? "" : context.traceId()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(310))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", context.traceId() == null ? "" : context.traceId())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            cancellation.attach(body);
            if (response.statusCode() != 200) throw new AssistantAgentException("Agent 服务暂时不可用");
            boolean done = false;
            String event = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while (!cancellation.cancelled() && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        JsonNode data = mapper.readTree(line.substring(5).trim());
                        switch (event) {
                            case "delta" -> consumer.accept(new AgentEvent("delta", data.path("text").asText(""),
                                    null, null, Map.of()));
                            case "status" -> consumer.accept(new AgentEvent("status", null,
                                    data.path("phase").asText("GENERATING"),
                                    data.path("message").asText("正在生成回答"), Map.of()));
                            case "tool_result" -> consumer.accept(new AgentEvent("tool_result", null, null, null,
                                    mapper.convertValue(data.path("metadata"), new TypeReference<>() {})));
                            case "done" -> done = true;
                            case "error" -> throw new AssistantAgentException(
                                    data.path("message").asText("Agent 执行失败"));
                            default -> { }
                        }
                    }
                }
            }
            if (!cancellation.cancelled() && !done)
                throw new AssistantAgentException("Agent 连接提前结束，回答可能不完整");
        } catch (AssistantAgentException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!cancellation.cancelled()) throw new AssistantAgentException("Agent 执行已中断");
        } catch (IOException | IllegalArgumentException exception) {
            if (!cancellation.cancelled()) throw new AssistantAgentException("Agent 连接失败，请稍后重试");
        } finally {
            close(body);
        }
    }

    private Map<String, Object> messagePayload(AssistantModelGateway.ModelMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content() == null ? "" : message.content());
        return payload;
    }

    private void close(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    public static class AssistantAgentException extends RuntimeException {
        public AssistantAgentException(String message) { super(message); }
    }
}
