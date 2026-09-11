package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 通过内部令牌访问 FastAPI 的 Embedding 端点，不暴露百炼凭证和上游错误正文。 */
@Component
public class DashScopeAssistantEmbeddingGateway implements AssistantEmbeddingGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String internalToken;

    public DashScopeAssistantEmbeddingGateway(ObjectMapper mapper,
                                              @Value("${app.quant-service.base-url}") String baseUrl,
                                              @Value("${app.quant-service.internal-token}") String internalToken,
                                              @Value("${app.quant-service.connect-timeout-ms:500}") int connectTimeoutMs) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/assistant/embeddings");
        this.internalToken = internalToken;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(100, connectTimeoutMs))).build();
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs, String traceId) {
        try {
            String json = mapper.writeValueAsString(Map.of("inputs", inputs));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量服务暂时不可用");
            JsonNode body = mapper.readTree(response.body());
            int dimensions = body.path("dimensions").asInt(0);
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode vector : body.path("embeddings")) {
                List<Double> values = new ArrayList<>();
                for (JsonNode value : vector) values.add(value.asDouble());
                if (dimensions <= 0 || values.size() != dimensions)
                    throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量维度异常");
                vectors.add(List.copyOf(values));
            }
            if (vectors.size() != inputs.size())
                throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量数量异常");
            return new EmbeddingBatch(body.path("model").asText("text-embedding-v4"), dimensions,
                    List.copyOf(vectors));
        } catch (QwenAssistantModelGateway.AssistantGatewayException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量生成已中断");
        } catch (Exception exception) {
            throw new QwenAssistantModelGateway.AssistantGatewayException("知识库向量服务连接失败");
        }
    }
}
