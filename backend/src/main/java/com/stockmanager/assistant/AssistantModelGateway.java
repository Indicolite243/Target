package com.stockmanager.assistant;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Java 与 Python 模型流之间的窄边界，不包含账户工具和交易能力。 */
public interface AssistantModelGateway {
    record ToolCall(String id, String name, String arguments) {}
    record ToolDefinition(String name, String description, Map<String, Object> parameters) {}
    record StreamResult(List<ToolCall> toolCalls) {
        public boolean requestedTools() { return toolCalls != null && !toolCalls.isEmpty(); }
    }
    record ModelMessage(String role, String content, List<ToolCall> toolCalls,
                        String toolCallId, String name) {
        public ModelMessage(String role, String content) { this(role, content, null, null, null); }
        public static ModelMessage assistantTools(List<ToolCall> calls) {
            return new ModelMessage("assistant", "", calls, null, null);
        }
        public static ModelMessage toolResult(ToolCall call, String result) {
            return new ModelMessage("tool", result, null, call.id(), call.name());
        }
    }
    record ModelEvent(String type, String text, String phase, String message) {}

    interface Cancellation {
        boolean cancelled();
        void attach(Closeable closeable);
    }

    StreamResult stream(List<ModelMessage> messages, List<ToolDefinition> tools, String traceId,
                        Consumer<ModelEvent> consumer, Cancellation cancellation);
}
