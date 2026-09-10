package com.stockmanager.backtest.service;

import com.stockmanager.backtest.service.BacktestInputStorageService.BacktestInput;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.service.BacktestTaskWorker;
import com.stockmanager.quanttask.service.QuantTaskService;
import com.stockmanager.quanttask.vo.QuantTaskView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 回测 HTTP 提交阶段的应用服务，负责参数校验、任务建档、文件暂存和 Worker 投递。
 *
 * <p>本类不执行回测算法；成功暂存输入后立即返回任务视图，实际计算在独立量化线程池中完成。</p>
 */
@Service
public class BacktestTaskSubmissionService {
    private final QuantTaskService taskService;
    private final BacktestInputStorageService inputStorageService;
    private final BacktestTaskWorker backtestTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    /** 注入任务状态机、输入暂存、回测 Worker 和有界线程池。 */
    public BacktestTaskSubmissionService(QuantTaskService taskService, BacktestInputStorageService inputStorageService,
                                         BacktestTaskWorker backtestTaskWorker,
                                         @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.taskService = taskService;
        this.inputStorageService = inputStorageService;
        this.backtestTaskWorker = backtestTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    /**
     * 受理一项回测并返回可轮询任务。相同幂等键会复用首次创建的任务。
     */
    public QuantTaskView submit(Long userId, MultipartFile strategyFile, List<MultipartFile> marketFiles,
                                String startDate, String endDate, String engineType, String benchmarkSymbol,
                                boolean bearProtection, String idempotencyKey, String traceId) {
        /*
         * 回测提交只负责“验参、落任务、暂存文件、投递 Worker”，不在 HTTP 线程执行策略。
         * 返回任务视图后，前端通过 taskId 查询状态；这样上传接口的耗时不随回测天数线性增长。
         *
         * 数据时序：
         * 请求参数 -> PENDING 任务 -> 任务目录(strategy.py/行情文件) -> 有界线程池 -> FastAPI -> 结果落库。
         */
        // 尽早把外部字符串转换成强类型日期；格式错误在创建数据库任务前直接返回 400。
        LocalDate start = parseDate(startDate, "开始日期格式错误");
        LocalDate end = parseDate(endDate, "结束日期格式错误");
        // request 只保存可检索、可审计的小型摘要，不把策略源码或整份 Excel 塞进任务表。
        Map<String, Object> request = new LinkedHashMap<>();
        // 原始文件名用于任务详情展示；真正执行文件会在受控目录中统一命名。
        request.put("strategyFilename", strategyFile == null ? "" : strategyFile.getOriginalFilename());
        // 只记录数量，避免 request_json 随上传附件大小增长。
        request.put("marketFileCount", marketFiles == null ? 0 : marketFiles.size());
        // 保存用户提交的日期文本，便于事后还原本次计算参数。
        request.put("startDate", startDate);
        request.put("endDate", endDate);
        // null 统一按 auto 记录，保证任务摘要中始终有明确引擎字段。
        request.put("engineType", engineType == null ? "auto" : engineType);
        // 基准代码允许为空，由 Python 执行层应用默认值。
        request.put("benchmarkSymbol", benchmarkSymbol == null ? "" : benchmarkSymbol);
        request.put("bearProtection", bearProtection);
        // 任务幂等键只保证重复提交复用同一个任务，不会复用或覆盖另一个用户的文件目录。
        QuantTask task = taskService.create(userId, null, "BACKTEST", request, idempotencyKey);
        // 幂等命中可能返回一个已经完成或正在运行的旧任务；只有新建/仍待执行的任务需要暂存和投递。
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                // 每个任务拥有独立目录，并在进入 Worker 前完成路径和大小校验。
                BacktestInput input = inputStorageService.stage(task.getId(), strategyFile, marketFiles, start, end,
                        engineType, benchmarkSymbol, bearProtection);
                // execute 只提交任务，不等待；队列满时显式记录失败，避免回退到 Web 请求线程。
                // Lambda 只捕获不可变路径描述和标识，不捕获生命周期受 HTTP 请求约束的 MultipartFile。
                quantTaskExecutor.execute(() -> backtestTaskWorker.execute(task.getId(), userId, input, traceId));
            } catch (BusinessException ex) {
                // 文件格式、大小或路径校验失败时同步返回原业务错误，同时让已创建任务进入 FAILED 终态。
                taskService.fail(task.getId(), String.valueOf(ex.getCode()), ex.getMessage());
                throw ex;
            } catch (RejectedExecutionException ex) {
                // 有界线程池已满时不在请求线程兜底执行，以免一个长回测拖垮全部 HTTP 请求。
                taskService.reject(task.getId());
            }
        }
        // 重新从数据库读取，确保返回的是投递失败/校验失败后最新的任务状态，而不是 create() 的旧对象。
        return taskService.view(taskService.requireOwned(userId, task.getId()));
    }

    /** 解析必需 ISO 日期，并转换为稳定业务异常。 */
    private LocalDate parseDate(String value, String message) {
        try { return LocalDate.parse(value); }
        catch (Exception ex) { throw new BusinessException(400701, message, HttpStatus.BAD_REQUEST); }
    }
}
