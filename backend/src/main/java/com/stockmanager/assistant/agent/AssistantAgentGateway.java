package com.stockmanager.assistant.agent;

import com.stockmanager.assistant.AssistantModelGateway;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 完整 Agent 编排网关；业务身份由 Java 注入，模型不可修改。 */
public interface AssistantAgentGateway {
    record InvocationContext(long userId, String conversationId, String requestId, String traceId) {}
    record AgentEvent(String type, String text, String phase, String message, Map<String, Object> metadata) {}

    void stream(List<AssistantModelGateway.ModelMessage> messages, InvocationContext context,
                Consumer<AgentEvent> consumer, AssistantModelGateway.Cancellation cancellation);
}
