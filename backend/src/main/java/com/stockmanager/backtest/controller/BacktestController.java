package com.stockmanager.backtest.controller;

import com.stockmanager.backtest.service.BacktestTaskSubmissionService;
import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.stockmanager.quanttask.vo.QuantTaskView;

/** 回测任务上传与受理接口；文件校验、暂存和异步调度由提交服务完成。 */
@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestTaskSubmissionService taskSubmissionService;

    /** 注入回测任务提交服务。 */
    public BacktestController(BacktestTaskSubmissionService taskSubmissionService) {
        this.taskSubmissionService = taskSubmissionService;
    }

    /**
     * 接收策略文件、可选行情文件和回测参数，返回 202 与可轮询任务摘要。
     */
    @PostMapping(value = "/run", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<QuantTaskView>> run(
            // multipart 中名为 file 的必填分段：只负责接收，真正的类型、大小和路径校验在存储服务完成。
            @RequestParam("file") MultipartFile strategyFile,
            // 多个行情文件使用相同的 market_files 字段名；没有上传时允许为 null。
            @RequestParam(value = "market_files", required = false) List<MultipartFile> marketFiles,
            // 日期暂时以字符串接收，提交服务会按 ISO-8601（yyyy-MM-dd）解析为 LocalDate。
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            // auto 表示由 Python 根据策略源码特征判断 MindGo 或普通 Python/Backtrader。
            @RequestParam(value = "engine_type", defaultValue = "auto") String engineType,
            // 空基准由 Python 回退为默认的沪深300代码，不在 Controller 中写死业务默认值。
            @RequestParam(value = "benchmark_symbol", defaultValue = "") String benchmarkSymbol,
            // 熊市保护作为显式回测参数透传，不在上传接口中改变策略源码。
            @RequestParam(value = "enable_bear_protection", defaultValue = "false") boolean bearProtection,
            // 同一次逻辑提交重试时应复用该键，后端据此复用既有任务，避免重复回测。
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            // JWT 过滤器写入的认证主体；name 在本项目中保存当前登录用户的数据库 ID。
            Authentication authentication,
            // TraceIdFilter 把链路号放入 request attribute，后续继续传递给 Worker 和 FastAPI。
            HttpServletRequest request) {
        // 读取一次链路号，使“上传受理—异步执行—Python回测”可以在日志中串联起来。
        String traceId = String.valueOf(request.getAttribute("traceId"));
        // Controller 不读取文件内容，也不运行策略；应用服务负责建立任务、暂存输入并投递异步线程。
        QuantTaskView task = taskSubmissionService.submit(Long.valueOf(authentication.getName()), strategyFile,
                marketFiles, startDate, endDate, engineType, benchmarkSymbol, bearProtection, idempotencyKey, traceId);
        // 202 表示“已受理”而不是“回测已完成”；调用方必须使用返回的 taskId 继续查询状态。
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("回测任务已受理", task, traceId));
    }
}
