package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 把 FastAPI 的受控 SSE 转成 Java 事件；不记录上游响应体和内部令牌。 */
@Component
public class QwenAssistantModelGateway implements AssistantModelGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String internalToken;

    public QwenAssistantModelGateway(ObjectMapper mapper,
                                     @Value("${app.quant-service.base-url}") String baseUrl,
                                     @Value("${app.quant-service.internal-token}") String internalToken,
                                     @Value("${app.quant-service.connect-timeout-ms:500}") int connectTimeoutMs) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/assistant/stream");
        this.internalToken = internalToken;
        this.client = HttpClient.newBuilder()
                // Uvicorn 的本地明文端口只支持 HTTP/1.1；禁止 JDK 发起 h2c 升级探测。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 100)))
                .build();
    }

    @Override
    public StreamResult stream(List<ModelMessage> messages, List<ToolDefinition> tools, String traceId,
                               Consumer<ModelEvent> consumer, Cancellation cancellation) {
        InputStream body = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messages", messages.stream().map(this::messagePayload).toList());
            if (tools != null && !tools.isEmpty()) {
                payload.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function", Map.of(
                        "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()))).toList());
            }
            String json = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(310))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            cancellation.attach(body);
            if (response.statusCode() != 200) throw new AssistantGatewayException("模型服务暂时不可用");

            boolean done = false;
            List<ToolCall> calls = new ArrayList<>();
            String event = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while (!cancellation.cancelled() && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        JsonNode data = mapper.readTree(line.substring(5).trim());
                        if ("delta".equals(event)) {
                            consumer.accept(new ModelEvent("delta", data.path("text").asText(""), null, null));
                        } else if ("status".equals(event)) {
                            consumer.accept(new ModelEvent("status", null, data.path("phase").asText("GENERATING"),
                                    data.path("message").asText("正在生成回答")));
                        } else if ("done".equals(event)) {
                            done = true;
                        } else if ("tool_calls".equals(event)) {
                            for (JsonNode call : data.path("calls")) {
                                calls.add(new ToolCall(call.path("id").asText(), call.path("name").asText(),
                                        call.path("arguments").asText("{}")));
                            }
                        } else if ("error".equals(event)) {
                            throw new AssistantGatewayException(data.path("message").asText("模型生成失败"));
                        }
                    }
                }
            }
            if (!cancellation.cancelled() && !done && calls.isEmpty()) {
                throw new AssistantGatewayException("模型连接提前结束，回答可能不完整");
            }
            return new StreamResult(List.copyOf(calls));
        } catch (AssistantGatewayException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!cancellation.cancelled()) throw new AssistantGatewayException("回答生成已中断");
        } catch (IOException | IllegalArgumentException exception) {
            if (!cancellation.cancelled()) throw new AssistantGatewayException("模型连接失败，请稍后重试");
        } finally {
            if (body != null) {
                try { body.close(); } catch (IOException ignored) { }
            }
        }
        return new StreamResult(List.of());
    }

    private Map<String, Object> messagePayload(ModelMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content() == null ? "" : message.content());
        if (message.toolCalls() != null) {
            payload.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(), "type", "function", "function", Map.of(
                            "name", call.name(), "arguments", call.arguments()))).toList());
        }
        if (message.toolCallId() != null) payload.put("tool_call_id", message.toolCallId());
        if (message.name() != null) payload.put("name", message.name());
        return payload;
    }

    public static class AssistantGatewayException extends RuntimeException {
        public AssistantGatewayException(String message) { super(message); }
    }
}
