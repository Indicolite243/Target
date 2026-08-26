package com.stockmanager.analysis.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.analysis.entity.AttributionResult;
import com.stockmanager.analysis.mapper.AttributionResultMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AttributionResultPersistenceService {
    private final AttributionResultMapper mapper;
    private final ObjectMapper objectMapper;

    public AttributionResultPersistenceService(AttributionResultMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AttributionResult save(Long taskId, Long accountId, String dimension, String source,
                                  Map<String, Object> result) {
        AttributionResult existing = mapper.selectOne(Wrappers.<AttributionResult>lambdaQuery()
                .eq(AttributionResult::getTaskId, taskId));
        if (existing != null) return existing;
        LocalDateTime now = LocalDateTime.now();
        AttributionResult row = new AttributionResult();
        row.setTaskId(taskId);
        row.setAccountId(accountId);
        row.setDimension(blank(dimension, "ASSET"));
        row.setSource(blank(source, "MYSQL"));
        row.setRangeStart(date(result.get("range_start")));
        row.setRangeEnd(date(result.get("range_end")));
        row.setDataVersion(blank(text(result.get("data_version")),
                "attribution-" + accountId + "-" + text(result.get("range_start")) + "-" + text(result.get("range_end"))));
        row.setResultJson(json(result));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
        return row;
    }

    public Map<String, Object> requireTaskResult(Long taskId) {
        AttributionResult row = mapper.selectOne(Wrappers.<AttributionResult>lambdaQuery()
                .eq(AttributionResult::getTaskId, taskId));
        if (row == null) throw new BusinessException(404802, "归因结果不存在", HttpStatus.NOT_FOUND);
        try { return objectMapper.readValue(row.getResultJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException(500802, "归因结果数据损坏", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new BusinessException(500802, "归因结果序列化失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private LocalDate date(Object value) {
        try { return value == null ? null : LocalDate.parse(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
