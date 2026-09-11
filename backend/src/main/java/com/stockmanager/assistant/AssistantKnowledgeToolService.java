package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 执行用户隔离的知识检索，并把本次证据冻结为可追溯的消息快照。 */
@Service
public class AssistantKnowledgeToolService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssistantKnowledgeService knowledgeService;

    public AssistantKnowledgeToolService(JdbcTemplate jdbc, ObjectMapper mapper,
                                         AssistantKnowledgeService knowledgeService) {
        this.jdbc = jdbc; this.mapper = mapper; this.knowledgeService = knowledgeService;
    }

    public record KnowledgeContext(String id, String modelJson) {}

    @Transactional
    public KnowledgeContext retrieve(long userId, String conversationId, String arguments, String traceId) {
        try {
            Integer ownedConversation = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=?",
                    Integer.class, conversationId, userId);
            if (ownedConversation == null || ownedConversation == 0)
                throw new BusinessException(404106, "助手会话不存在", HttpStatus.NOT_FOUND);
            JsonNode parsed = mapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            String query = parsed.path("query").asText("").strip();
            AssistantKnowledgeService.SearchResult found = knowledgeService.search(userId, query, 6, traceId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("available", !found.evidence().isEmpty());
            if (found.evidence().isEmpty()) payload.put("reason", "当前用户尚未上传可检索文档，或知识库没有可用切片");
            payload.put("query", query);
            payload.put("retrievalMethod", "百炼向量召回 + 关键词召回 + RRF融合");
            payload.put("embeddingModel", found.embeddingModel());
            payload.put("citationRule", "回答引用文档证据时使用[K1]、[K2]格式，并同时给出文档名；不得伪造未返回的引用");
            payload.put("evidence", found.evidence());
            payload.put("limitations", List.of("文档证据只属于当前登录用户", "检索结果不替代账户和回测事实", "资料不足时需明确说明"));
            String json = mapper.writeValueAsString(payload);
            String snapshotId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ai_data_snapshot(id,conversation_id,account_id,snapshot_type,snapshot_json,captured_at,source_version)
                    VALUES(?,?,NULL,'KNOWLEDGE',?,?,?)
                    """, snapshotId, conversationId, json, Timestamp.valueOf(LocalDateTime.now()), found.embeddingModel());
            return new KnowledgeContext(snapshotId, json);
        } catch (BusinessException | QwenAssistantModelGateway.AssistantGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(500106, "知识检索失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
