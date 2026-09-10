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

/**
 * 清理已结束回测任务的运行目录；MySQL 中的任务摘要和结果记录保留。
 *
 * <p>清理条件由数据库筛选（任务类型、终态、完成时间），文件删除仍通过
 * {@link BacktestInputStorageService} 的路径边界校验完成，避免误删共享目录。</p>
 */
@Service
public class BacktestRuntimeCleanupService {
    private static final Logger log = LoggerFactory.getLogger(BacktestRuntimeCleanupService.class);
    private final QuantTaskMapper taskMapper;
    private final BacktestInputStorageService storageService;

    /** 注入任务查询和受路径边界保护的文件存储服务。 */
    public BacktestRuntimeCleanupService(QuantTaskMapper taskMapper, BacktestInputStorageService storageService) {
        this.taskMapper = taskMapper;
        this.storageService = storageService;
    }

    /** 清理完成时间早于 cutoff 的终态回测目录，并返回成功删除数量。 */
    public int cleanupCompletedBefore(LocalDateTime cutoff) {
        // 只清理 SUCCEEDED/FAILED/CANCELLED；PENDING/RUNNING 的输入仍可能被 Worker 使用。
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
