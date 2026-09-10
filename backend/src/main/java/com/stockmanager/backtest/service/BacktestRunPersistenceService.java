package com.stockmanager.backtest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.backtest.entity.BacktestRun;
import com.stockmanager.backtest.mapper.BacktestRunMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 回测结果唯一持久化边界：任务目录保存运行文件，本表保存可检索的元数据和结果 JSON。
 * taskId 既是幂等键，也是从异步任务跳转回完整回测报告的关联键。
 */
@Service
public class BacktestRunPersistenceService {
    private final BacktestRunMapper mapper;
    private final ObjectMapper objectMapper;

    /** 注入回测结果 Mapper 和 JSON 编解码器。 */
    public BacktestRunPersistenceService(BacktestRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 以 taskId 为幂等键保存回测元数据和完整结果 JSON。 */
    @Transactional
    public BacktestRun save(Long taskId, Long userId, String strategyFilename, String engineType,
                            String benchmarkSymbol, LocalDate startDate, LocalDate endDate,
                            String runtimePath, Map<String, Object> result) {
        // Worker 重试或消息重复投递时直接复用已有结果，不生成第二份回测报告。
        // 先按唯一关联键查询，避免同一个 Worker 被意外重复调用时重复插入报告。
        BacktestRun existing = mapper.selectOne(Wrappers.<BacktestRun>lambdaQuery().eq(BacktestRun::getTaskId, taskId));
        if (existing != null) return existing;
        // 所有时间字段在同一时刻生成，便于审计“结果第一次持久化”的准确时间。
        LocalDateTime now = LocalDateTime.now();
        // 新建结果实体；主键由 MyBatis-Plus ASSIGN_ID 在 insert 时生成。
        BacktestRun run = new BacktestRun();
        // taskId 是任务与结果之间的稳定关联键，数据库还有唯一索引和外键约束。
        run.setTaskId(taskId);
        run.setUserId(userId);
        // 以下字段保存本次执行口径，运行目录清理后仍能从数据库解释报告来源。
        run.setStrategyFilename(strategyFilename);
        run.setEngineType(engineType);
        run.setBenchmarkSymbol(benchmarkSymbol);
        run.setStartDate(startDate);
        run.setEndDate(endDate);
        // 只保存相对任务目录，避免把服务器绝对路径作为接口数据长期暴露。
        run.setRuntimePath(runtimePath);
        // 收益曲线、指标、成交记录和执行元数据统一序列化到 MySQL JSON 字段。
        run.setResultJson(json(result));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        // insert 成功后 run.id 会被 MyBatis-Plus 回填，供 quant_task.result_id 建立结果指针。
        mapper.insert(run);
        return run;
    }

    /** 按任务 ID 读取并反序列化完整回测报告。 */
    public Map<String, Object> requireTaskResult(Long taskId) {
        // 任务详情先查 MySQL，再解析结果 JSON；文件目录被清理后仍可查看关键结果。
        BacktestRun run = mapper.selectOne(Wrappers.<BacktestRun>lambdaQuery().eq(BacktestRun::getTaskId, taskId));
        if (run == null) throw new BusinessException(404702, "回测结果不存在", HttpStatus.NOT_FOUND);
        try { return objectMapper.readValue(run.getResultJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException(500702, "回测结果数据损坏", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** 序列化完整回测结果，失败时返回明确的内部业务错误。 */
    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new BusinessException(500702, "回测结果序列化失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }
}
