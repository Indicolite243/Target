package com.stockmanager.assistant;

import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

/** Java 与 Python 模型流之间的窄边界，不包含账户工具和交易能力。 */
public interface AssistantModelGateway {
    record ModelMessage(String role, String content) {}
    record ModelEvent(String type, String text, String phase, String message) {}

    interface Cancellation {
        boolean cancelled();
        void attach(Closeable closeable);
    }

    void stream(List<ModelMessage> messages, String traceId,
                Consumer<ModelEvent> consumer, Cancellation cancellation);
}

