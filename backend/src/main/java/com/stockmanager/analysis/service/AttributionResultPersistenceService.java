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

/**
 * 归因结果持久化服务，以量化任务 ID 作为唯一键保存完整 JSON 结果和稳定筛选字段。
 */
@Service
public class AttributionResultPersistenceService {
    private final AttributionResultMapper mapper;
    private final ObjectMapper objectMapper;

    /** 注入归因结果 Mapper 和 JSON 编解码器。 */
    public AttributionResultPersistenceService(AttributionResultMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 幂等保存任务归因结果；同一 taskId 已存在时直接返回原记录。 */
    @Transactional
    public AttributionResult save(Long taskId, Long accountId, String dimension, String source,
                                  Map<String, Object> result) {
        // taskId在数据库有唯一约束；先查询可以让重复投递直接复用第一次成功结果。
        AttributionResult existing = mapper.selectOne(Wrappers.<AttributionResult>lambdaQuery()
                .eq(AttributionResult::getTaskId, taskId));
        if (existing != null) return existing;
        // 本行所有审计时间使用同一个now，避免字段之间出现无意义的毫秒差异。
        LocalDateTime now = LocalDateTime.now();
        // 主键在insert时由MyBatis-Plus生成并回填。
        AttributionResult row = new AttributionResult();
        // taskId/accountId建立“任务—结果—账户”三者之间的追溯关系。
        row.setTaskId(taskId);
        row.setAccountId(accountId);
        // 空维度和来源使用稳定默认值，保证后续筛选字段不为空。
        row.setDimension(blank(dimension, "ASSET"));
        row.setSource(blank(source, "MYSQL"));
        // 日期从弱类型结果Map中提取；解析失败只让筛选列为空，不丢失完整JSON报告。
        row.setRangeStart(date(result.get("range_start")));
        row.setRangeEnd(date(result.get("range_end")));
        // 上游未提供data_version时构造可读版本号，至少包含账户和实际区间。
        row.setDataVersion(blank(text(result.get("data_version")),
                "attribution-" + accountId + "-" + text(result.get("range_start")) + "-" + text(result.get("range_end"))));
        // 明细数组可能很大，只在结果表保存一次，不复制到quant_task轮询摘要。
        row.setResultJson(json(result));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        // insert成功后row.id被回填，Worker会把它写入quant_task.result_id。
        mapper.insert(row);
        return row;
    }

    /** 按任务 ID 读取并反序列化完整结果。 */
    public Map<String, Object> requireTaskResult(Long taskId) {
        // 结果接口以任务ID访问，因此这里不要求前端知道内部结果表主键。
        AttributionResult row = mapper.selectOne(Wrappers.<AttributionResult>lambdaQuery()
                .eq(AttributionResult::getTaskId, taskId));
        if (row == null) throw new BusinessException(404802, "归因结果不存在", HttpStatus.NOT_FOUND);
        // TypeReference保留Map<String,Object>泛型结构，数组和嵌套对象会恢复成普通Java集合。
        try { return objectMapper.readValue(row.getResultJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException(500802, "归因结果数据损坏", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** 把完整归因结果序列化为 JSON。 */
    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new BusinessException(500802, "归因结果序列化失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** 尝试解析结果中的日期字段，非法值不影响主结果保存。 */
    private LocalDate date(Object value) {
        try { return value == null ? null : LocalDate.parse(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    /** 将可空弱类型值转换为文本。 */
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    /** 空白文本使用指定默认值。 */
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
