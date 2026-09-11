package com.stockmanager.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** 会话持久化：所有访问校验当前用户，欢迎消息不读取账户资产。 */
@Service
public class AssistantConversationService {
    public static final String WELCOME = "我是你的个人投研助手。我可以读取当前账户的持仓、历史业绩归因和最近一次回测结果，帮助你发现组合结构、收益来源和策略风险，并结合本地知识库提供参考分析。你可以直接向我提问。";
    private static final String SYSTEM_PROMPT = """
            你是 Target 投研助手，请使用中文回答。只有当问题确实依赖当前账户或持仓时，才调用
            get_current_portfolio_snapshot；普通知识问题不要调用。工具结果是会话冻结的 MySQL 最近确认快照，
            不是实时行情。snapshotFrozenAt 只是本会话冻结数据的时间，描述数据截至时间时只能使用
            sourceDataAsOf。当前尚未提供历史成交、业绩归因、回测或知识库工具，相关问题必须说明缺少数据。
            使用通用知识时标明未由本地数据验证且可能存在时效限制，不得编造任何账户事实。
            不得声称已经下单、修改持仓、运行回测或写入策略文件。除非用户明确要求，否则不输出源码。
            """;
    private static final List<AssistantModelGateway.ToolDefinition> PORTFOLIO_TOOLS = List.of(
            new AssistantModelGateway.ToolDefinition("get_current_portfolio_snapshot",
                    "读取当前登录用户唯一账户在 MySQL 中最近确认并冻结到本会话的账户总览、完整持仓与集中度指标。仅在问题依赖实际账户数据时调用。",
                    Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
    private final JdbcTemplate jdbc;
    private final AssistantModelGateway modelGateway;
    private final AssistantPortfolioSnapshotService snapshotService;
    private final ExecutorService generationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, ActiveGeneration> active = new ConcurrentHashMap<>();

    @Autowired
    public AssistantConversationService(JdbcTemplate jdbc, AssistantModelGateway modelGateway,
                                        AssistantPortfolioSnapshotService snapshotService) {
        this.jdbc = jdbc;
        this.modelGateway = modelGateway;
        this.snapshotService = snapshotService;
    }
    AssistantConversationService(JdbcTemplate jdbc, AssistantModelGateway modelGateway) { this(jdbc, modelGateway, null); }
    AssistantConversationService(JdbcTemplate jdbc) { this(jdbc, null, null); }
    public record Conversation(String id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Message(String id, String role, String content, String status, LocalDateTime createdAt) {}

    @PostConstruct
    void recoverInterruptedMessages() {
        jdbc.update("UPDATE ai_message SET status='FAILED' WHERE status IN ('PENDING','GENERATING')");
    }

    public List<Conversation> list(long userId) {
        return jdbc.query("SELECT id,title,created_at,updated_at FROM ai_conversation WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT 100",
                (rs, n) -> new Conversation(rs.getString("id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), userId);
    }
    @Transactional
    public Conversation create(long userId) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        // 账户关联延迟到首次分析；不能随意选择多账户用户的第一条记录。
        jdbc.update("INSERT INTO ai_conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                id, userId, "新对话", Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbc.update("INSERT INTO ai_message(id,conversation_id,role,content,status,created_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, "assistant", WELCOME, "COMPLETED", Timestamp.valueOf(now));
        return new Conversation(id, "新对话", now, now);
    }
    public List<Message> messages(long userId, String id) {
        requireOwner(userId, id);
        return jdbc.query("""
                SELECT id,role,content,status,created_at FROM ai_message WHERE conversation_id=?
                ORDER BY created_at,CASE role WHEN 'user' THEN 0 WHEN 'assistant' THEN 1 ELSE 2 END,id
                """,
                (rs, n) -> new Message(rs.getString("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()), id);
    }
    @Transactional
    public void rename(long userId, String id, String title) {
        if (title == null || title.isBlank() || title.strip().length() > 100)
            throw new BusinessException(400100, "标题必须为1至100个字符", HttpStatus.BAD_REQUEST);
        requireOwner(userId, id);
        jdbc.update("UPDATE ai_conversation SET title=?,updated_at=? WHERE id=? AND user_id=?",
                title.strip(), Timestamp.valueOf(LocalDateTime.now()), id, userId);
    }
    @Transactional
    public void delete(long userId, String id) {
        if (active.containsKey(id))
            throw new BusinessException(409100, "请先停止当前回答再删除会话", HttpStatus.CONFLICT);
        if (jdbc.update("DELETE FROM ai_conversation WHERE id=? AND user_id=?", id, userId) != 1)
            throw new BusinessException(404100, "会话不存在", HttpStatus.NOT_FOUND);
    }
    private void requireOwner(long userId, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=?", Integer.class, id, userId);
        if (count == null || count != 1) throw new BusinessException(404100, "会话不存在", HttpStatus.NOT_FOUND);
    }

    public SseEmitter ask(long userId, String id, String question, String traceId) {
        if (question == null || question.isBlank() || question.strip().length() > 8000)
            throw new BusinessException(400101, "问题必须为1至8000个字符", HttpStatus.BAD_REQUEST);
        requireOwner(userId, id);
        if (modelGateway == null)
            throw new BusinessException(503100, "模型服务尚未配置", HttpStatus.SERVICE_UNAVAILABLE);

        String requestId = UUID.randomUUID().toString();
        String userMessageId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(320_000L);
        ActiveGeneration generation = new ActiveGeneration(userId, id, requestId, userMessageId,
                assistantMessageId, emitter);
        if (active.putIfAbsent(id, generation) != null)
            throw new BusinessException(409101, "当前会话正在生成回答，请先停止", HttpStatus.CONFLICT);

        String normalized = question.strip();
        try {
            List<AssistantModelGateway.ModelMessage> context = context(id);
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("INSERT INTO ai_message(id,conversation_id,request_id,role,content,status,created_at) VALUES(?,?,?,?,?,?,?)",
                    userMessageId, id, requestId, "user", normalized, "PENDING", Timestamp.valueOf(now));
            jdbc.update("INSERT INTO ai_message(id,conversation_id,request_id,role,content,status,created_at) VALUES(?,?,?,?,?,?,?)",
                    assistantMessageId, id, requestId, "assistant", "", "GENERATING", Timestamp.valueOf(now.plusNanos(1_000)));
            jdbc.update("UPDATE ai_conversation SET title=CASE WHEN title='新对话' THEN ? ELSE title END,updated_at=? WHERE id=? AND user_id=?",
                    normalized.substring(0, Math.min(normalized.length(), 30)), Timestamp.valueOf(now), id, userId);
            context.add(new AssistantModelGateway.ModelMessage("user", normalized));
            emit(emitter, "accepted", Map.of("requestId", requestId, "userMessageId", userMessageId,
                    "assistantMessageId", assistantMessageId));
            Future<?> future = generationExecutor.submit(() -> generate(generation, context, traceId));
            generation.attach(future);
            return emitter;
        } catch (RuntimeException exception) {
            active.remove(id, generation);
            try { jdbc.update("DELETE FROM ai_message WHERE conversation_id=? AND request_id=?", id, requestId); }
            catch (RuntimeException ignored) { }
            emitter.completeWithError(exception);
            throw exception;
        }
    }

    public boolean cancel(long userId, String id) {
        requireOwner(userId, id);
        ActiveGeneration generation = active.get(id);
        if (generation == null || generation.userId != userId || !generation.terminal.compareAndSet(false, true)) return false;
        generation.cancelled.set(true);
        generation.closeUpstream();
        generation.cancelFuture();
        try {
            boolean saved = finishSafely(generation, "CANCELLED");
            emit(generation.emitter, "cancelled", Map.of("status", "CANCELLED", "message",
                    saved ? "已停止生成，当前内容不完整" : "已停止生成，但状态保存失败"));
        } finally {
            generation.emitter.complete();
            active.remove(id, generation);
        }
        return true;
    }

    private List<AssistantModelGateway.ModelMessage> context(String conversationId) {
        List<AssistantModelGateway.ModelMessage> result = new ArrayList<>();
        result.add(new AssistantModelGateway.ModelMessage("system", SYSTEM_PROMPT));
        List<AssistantModelGateway.ModelMessage> recent = jdbc.query("""
                SELECT m.role,m.content FROM ai_message m
                WHERE m.conversation_id=? AND m.status='COMPLETED'
                  AND (m.request_id IS NULL OR EXISTS (
                    SELECT 1 FROM ai_message paired
                    WHERE paired.conversation_id=m.conversation_id AND paired.request_id=m.request_id
                      AND paired.status='COMPLETED' AND paired.role<>m.role))
                ORDER BY m.created_at DESC,CASE m.role WHEN 'assistant' THEN 0 ELSE 1 END,m.id DESC LIMIT 40
                """, (rs, n) -> new AssistantModelGateway.ModelMessage(rs.getString("role"), rs.getString("content")), conversationId);
        Collections.reverse(recent);
        result.addAll(recent);
        return result;
    }

    private void generate(ActiveGeneration generation, List<AssistantModelGateway.ModelMessage> context, String traceId) {
        try {
            emit(generation.emitter, "status", Map.of("phase", "SELECTING", "message", "正在判断所需数据"));
            StringBuffer directAnswer = new StringBuffer();
            AssistantModelGateway.StreamResult plan = modelGateway.stream(context, PORTFOLIO_TOOLS, traceId,
                    event -> { if ("delta".equals(event.type()) && event.text() != null) directAnswer.append(event.text()); }, generation);
            if (plan.requestedTools()) {
                if (snapshotService == null) throw new QwenAssistantModelGateway.AssistantGatewayException("账户工具尚未配置");
                emit(generation.emitter, "status", Map.of("phase", "READING_DATA", "message", "正在读取会话冻结的账户快照"));
                context.add(AssistantModelGateway.ModelMessage.assistantTools(plan.toolCalls()));
                AssistantPortfolioSnapshotService.FrozenSnapshot snapshot = snapshotService.getOrCreate(
                        generation.userId, generation.conversationId);
                generation.snapshotId = snapshot.id();
                jdbc.update("UPDATE ai_message SET snapshot_id=? WHERE conversation_id=? AND request_id=?",
                        snapshot.id(), generation.conversationId, generation.requestId);
                for (AssistantModelGateway.ToolCall call : plan.toolCalls()) {
                    String result = "get_current_portfolio_snapshot".equals(call.name())
                            ? snapshot.modelJson() : "{\"available\":false,\"reason\":\"不允许的工具\"}";
                    context.add(AssistantModelGateway.ModelMessage.toolResult(call, result));
                }
                emit(generation.emitter, "status", Map.of("phase", "GENERATING", "message", "正在结合账户快照生成回答"));
                AssistantModelGateway.StreamResult finalResult = modelGateway.stream(context, List.of(), traceId,
                        event -> acceptLiveEvent(generation, event), generation);
                if (finalResult.requestedTools())
                    throw new QwenAssistantModelGateway.AssistantGatewayException("模型重复请求了未开放工具");
            } else {
                if (directAnswer.isEmpty())
                    throw new QwenAssistantModelGateway.AssistantGatewayException("模型没有返回回答");
                acceptLiveEvent(generation, new AssistantModelGateway.ModelEvent("delta", directAnswer.toString(), null, null));
            }
            if (generation.terminal.compareAndSet(false, true)) {
                if (finishSafely(generation, "COMPLETED"))
                    emit(generation.emitter, "done", Map.of("status", "COMPLETED"));
                else
                    emit(generation.emitter, "error", Map.of("status", "FAILED", "message", "回答已生成，但保存失败"));
                generation.emitter.complete();
            }
        } catch (RuntimeException exception) {
            if (generation.terminal.compareAndSet(false, true)) {
                finishSafely(generation, "FAILED");
                emit(generation.emitter, "error", Map.of("status", "FAILED", "message", safeMessage(exception)));
                generation.emitter.complete();
            }
        } finally {
            active.remove(generation.conversationId, generation);
        }
    }

    private void acceptLiveEvent(ActiveGeneration generation, AssistantModelGateway.ModelEvent event) {
        if (generation.cancelled.get()) return;
        if ("delta".equals(event.type()) && event.text() != null) {
            generation.content.append(event.text());
            if (generation.content.length() - generation.persistedLength >= 512) persistPartial(generation);
            emit(generation.emitter, "delta", Map.of("text", event.text()));
        } else if ("status".equals(event.type())) {
            emit(generation.emitter, "status", Map.of("phase", event.phase(), "message", event.message()));
        }
    }

    private void persistPartial(ActiveGeneration generation) {
        String content = generation.content.toString();
        jdbc.update("UPDATE ai_message SET content=? WHERE id=? AND conversation_id=?", content,
                generation.assistantMessageId, generation.conversationId);
        generation.persistedLength = content.length();
    }

    private void finish(ActiveGeneration generation, String status) {
        persistPartial(generation);
        jdbc.update("UPDATE ai_message SET status=? WHERE id IN (?,?) AND conversation_id=?",
                status, generation.userMessageId, generation.assistantMessageId, generation.conversationId);
        jdbc.update("UPDATE ai_conversation SET updated_at=? WHERE id=? AND user_id=?",
                Timestamp.valueOf(LocalDateTime.now()), generation.conversationId, generation.userId);
    }

    private boolean finishSafely(ActiveGeneration generation, String status) {
        try { finish(generation, status); return true; }
        catch (RuntimeException ignored) { return false; }
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof QwenAssistantModelGateway.AssistantGatewayException && exception.getMessage() != null)
            return exception.getMessage();
        return "回答生成失败，已生成内容可能不完整";
    }

    private void emit(SseEmitter emitter, String name, Object data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException | IllegalStateException ignored) { }
    }

    @PreDestroy
    void shutdown() {
        active.values().forEach(generation -> {
            generation.cancelled.set(true);
            generation.closeUpstream();
            generation.cancelFuture();
        });
        generationExecutor.shutdownNow();
    }

    private static final class ActiveGeneration implements AssistantModelGateway.Cancellation {
        private final long userId;
        private final String conversationId;
        private final String requestId;
        private final String userMessageId;
        private final String assistantMessageId;
        private final SseEmitter emitter;
        private final StringBuffer content = new StringBuffer();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private volatile int persistedLength;
        private volatile Closeable upstream;
        private volatile Future<?> future;
        private volatile String snapshotId;

        private ActiveGeneration(long userId, String conversationId, String requestId, String userMessageId,
                                 String assistantMessageId, SseEmitter emitter) {
            this.userId = userId; this.conversationId = conversationId; this.requestId = requestId;
            this.userMessageId = userMessageId; this.assistantMessageId = assistantMessageId; this.emitter = emitter;
        }
        @Override public boolean cancelled() { return cancelled.get(); }
        @Override public void attach(Closeable closeable) {
            this.upstream = closeable;
            if (cancelled.get()) closeUpstream();
        }
        private void attach(Future<?> future) {
            this.future = future;
            if (cancelled.get()) future.cancel(true);
        }
        private void closeUpstream() {
            Closeable value = upstream;
            if (value != null) try { value.close(); } catch (IOException ignored) { }
        }
        private void cancelFuture() { Future<?> value = future; if (value != null) value.cancel(true); }
    }
}
