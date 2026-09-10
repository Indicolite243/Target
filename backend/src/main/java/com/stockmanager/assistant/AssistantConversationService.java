package com.stockmanager.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 会话持久化：所有访问校验当前用户，欢迎消息不读取账户资产。 */
@Service
public class AssistantConversationService {
    public static final String WELCOME = "我是你的个人投研助手。我可以读取当前账户的持仓、历史业绩归因和最近一次回测结果，帮助你发现组合结构、收益来源和策略风险，并结合本地知识库提供参考分析。你可以直接向我提问。";
    private final JdbcTemplate jdbc;
    public AssistantConversationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Conversation(String id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Message(String id, String role, String content, String status, LocalDateTime createdAt) {}

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
        return jdbc.query("SELECT id,role,content,status,created_at FROM ai_message WHERE conversation_id=? ORDER BY created_at,id",
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
        if (jdbc.update("DELETE FROM ai_conversation WHERE id=? AND user_id=?", id, userId) != 1)
            throw new BusinessException(404100, "会话不存在", HttpStatus.NOT_FOUND);
    }
    private void requireOwner(long userId, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=?", Integer.class, id, userId);
        if (count == null || count != 1) throw new BusinessException(404100, "会话不存在", HttpStatus.NOT_FOUND);
    }
}
