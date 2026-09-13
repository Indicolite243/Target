package com.stockmanager.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.assistant.AssistantAttributionToolService;
import com.stockmanager.assistant.AssistantBacktestToolService;
import com.stockmanager.assistant.AssistantKnowledgeToolService;
import com.stockmanager.assistant.AssistantPortfolioSnapshotService;
import com.stockmanager.common.exception.BusinessException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 注册 Target 投研域 MCP 工具，处理器只调用已有的用户隔离只读服务。 */
@Component
public class AssistantMcpToolRegistry {
    public static final String HEADER_USER_ID = "X-Target-User-Id";
    public static final String HEADER_CONVERSATION_ID = "X-Target-Conversation-Id";
    public static final String HEADER_REQUEST_ID = "X-Target-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String EMPTY_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;
    private static final String KNOWLEDGE_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","description":"用于检索私有文档的完整问题"}},"required":["query"],"additionalProperties":false}
            """;

    private final ObjectMapper mapper;
    private final McpJsonMapper mcpJsonMapper;
    private final AssistantPortfolioSnapshotService portfolio;
    private final AssistantAttributionToolService attribution;
    private final AssistantBacktestToolService backtest;
    private final AssistantKnowledgeToolService knowledge;

    public AssistantMcpToolRegistry(ObjectMapper mapper, McpJsonMapper mcpJsonMapper,
            AssistantPortfolioSnapshotService portfolio,
            AssistantAttributionToolService attribution,
            AssistantBacktestToolService backtest,
            AssistantKnowledgeToolService knowledge) {
        this.mapper = mapper;
        this.mcpJsonMapper = mcpJsonMapper;
        this.portfolio = portfolio;
        this.attribution = attribution;
        this.backtest = backtest;
        this.knowledge = knowledge;
    }

    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                tool("get_current_portfolio_snapshot",
                        "读取当前登录用户唯一账户在 MySQL 中最近确认并冻结到本会话的账户总览、完整持仓与集中度指标。仅在问题依赖实际账户数据时调用。",
                        EMPTY_SCHEMA, (context, request) -> {
                            var value = portfolio.getOrCreate(context.userId(), context.conversationId());
                            return success(value.modelJson(), Map.of("snapshotId", value.id()));
                        }),
                tool("get_performance_attribution",
                        "读取当前登录用户唯一账户最近30天的MySQL历史持仓快照，返回会话冻结的个股与行业收益贡献、区间和计算口径。询问收益来源、个股贡献或行业贡献时调用。",
                        EMPTY_SCHEMA, (context, request) -> {
                            var value = attribution.getOrCreate(context.userId(), context.conversationId(), context.traceId());
                            return success(value.modelJson(), Map.of("attributionSnapshotId", value.id()));
                        }),
                tool("get_latest_backtest_analysis_data",
                        "选择并固定当前用户最近创建的回测任务，读取其状态；成功时返回指标和全区间抽样收益曲线，不返回源码。回测分析时调用。",
                        EMPTY_SCHEMA, (context, request) -> backtest(context, false)),
                tool("get_selected_backtest_strategy_source",
                        "读取本会话已选回测的策略源码；仅当用户明确要求查看、解释或修改源码时调用。",
                        EMPTY_SCHEMA, (context, request) -> backtest(context, true)),
                tool("search_private_knowledge_base",
                        "检索当前登录用户上传的私有投研文档。询问文档方法、风险规则、策略说明，或需要文档证据辅助回答时调用。",
                        KNOWLEDGE_SCHEMA, (context, request) -> {
                            String arguments = mapper.writeValueAsString(request.arguments());
                            var value = knowledge.retrieve(context.userId(), context.conversationId(), arguments,
                                    context.traceId());
                            return success(value.modelJson(), Map.of("knowledgeSnapshotId", value.id()));
                        })
        );
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description, String inputSchema,
            ThrowingHandler handler) {
        McpSchema.Tool definition = McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(mcpJsonMapper, inputSchema).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(definition)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(context(exchange, request), request);
                    } catch (BusinessException exception) {
                        return failure(exception.getMessage());
                    } catch (Exception exception) {
                        return failure("MCP 工具执行失败");
                    }
                }).build();
    }

    private McpSchema.CallToolResult backtest(Context context, boolean includeSource) {
        var value = backtest.getOrSelect(context.userId(), context.conversationId(), includeSource);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (value.taskId() != null) metadata.put("backtestTaskId", value.taskId());
        return success(value.modelJson(), metadata);
    }

    private Context context(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object headerUserId = transport(exchange, HEADER_USER_ID);
        Object headerConversationId = transport(exchange, HEADER_CONVERSATION_ID);
        Object headerRequestId = transport(exchange, HEADER_REQUEST_ID);
        Object headerTraceId = transport(exchange, HEADER_TRACE_ID);
        if (headerUserId != null || headerConversationId != null || headerRequestId != null) {
            return new Context(requiredLong(Map.of(HEADER_USER_ID, value(headerUserId)), HEADER_USER_ID),
                    requiredText(Map.of(HEADER_CONVERSATION_ID, value(headerConversationId)), HEADER_CONVERSATION_ID),
                    requiredText(Map.of(HEADER_REQUEST_ID, value(headerRequestId)), HEADER_REQUEST_ID),
                    headerTraceId == null ? "" : String.valueOf(headerTraceId));
        }
        Map<String, Object> metadata = request.meta() == null ? Map.of() : request.meta();
        return new Context(requiredLong(metadata, McpAssistantToolGateway.META_USER_ID),
                requiredText(metadata, McpAssistantToolGateway.META_CONVERSATION_ID),
                requiredText(metadata, McpAssistantToolGateway.META_REQUEST_ID),
                String.valueOf(metadata.getOrDefault(McpAssistantToolGateway.META_TRACE_ID, "")));
    }

    private Object transport(McpSyncServerExchange exchange, String key) {
        return exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(key);
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }

    private long requiredLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) return Long.parseLong(text);
        throw new IllegalArgumentException("MCP 调用缺少身份上下文");
    }

    private String requiredText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank())
            throw new IllegalArgumentException("MCP 调用缺少会话上下文");
        return String.valueOf(value);
    }

    private McpSchema.CallToolResult success(String content, Map<String, Object> metadata) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(content)), false,
                metadata, Map.of());
    }

    private McpSchema.CallToolResult failure(String message) {
        String safe = message == null || message.isBlank() ? "MCP 工具执行失败" : message;
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(safe)), true,
                Map.of(), Map.of());
    }

    private record Context(long userId, String conversationId, String requestId, String traceId) {}

    @FunctionalInterface
    private interface ThrowingHandler {
        McpSchema.CallToolResult apply(Context context, McpSchema.CallToolRequest request) throws Exception;
    }
}
