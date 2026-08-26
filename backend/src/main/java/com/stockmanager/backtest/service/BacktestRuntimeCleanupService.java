package com.stockmanager.backtest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.mapper.QuantTaskMapper;
import com.stockmanager.quanttask.service.QuantTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Deletes only terminal task-owned runtime directories; MySQL summaries/results are retained. */
@Service
public class BacktestRuntimeCleanupService {
    private static final Logger log = LoggerFactory.getLogger(BacktestRuntimeCleanupService.class);
    private final QuantTaskMapper taskMapper;
    private final BacktestInputStorageService storageService;

    public BacktestRuntimeCleanupService(QuantTaskMapper taskMapper, BacktestInputStorageService storageService) {
        this.taskMapper = taskMapper;
        this.storageService = storageService;
    }

    public int cleanupCompletedBefore(LocalDateTime cutoff) {
        List<QuantTask> eligible = taskMapper.selectList(Wrappers.<QuantTask>lambdaQuery()
                .eq(QuantTask::getTaskType, "BACKTEST")
                .in(QuantTask::getStatus, QuantTaskService.SUCCEEDED, QuantTaskService.FAILED, QuantTaskService.CANCELLED)
                .isNotNull(QuantTask::getFinishedAt)
                .lt(QuantTask::getFinishedAt, cutoff));
        int deleted = 0;
        for (QuantTask task : eligible) {
            try {
                if (storageService.deleteTaskDirectory(task.getId())) deleted++;
            } catch (Exception ex) {
                log.warn("回测运行目录清理失败，taskId={}, reason={}", task.getId(), ex.getMessage());
            }
        }
        return deleted;
    }
}
