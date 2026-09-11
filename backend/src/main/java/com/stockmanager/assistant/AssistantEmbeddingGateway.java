package com.stockmanager.assistant;

import java.util.List;

/** 调用 Python AI 服务生成百炼文本向量，浏览器和业务服务都不直接持有云端密钥。 */
public interface AssistantEmbeddingGateway {
    record EmbeddingBatch(String model, int dimensions, List<List<Double>> vectors) {}
    EmbeddingBatch embed(List<String> inputs, String traceId);
}
