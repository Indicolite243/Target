package com.stockmanager.account.controller;

import com.stockmanager.account.service.AccountService;
import com.stockmanager.account.live.CurrentPortfolioSnapshot;
import com.stockmanager.account.live.PortfolioLiveService;
import com.stockmanager.account.vo.AccountSyncView;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import com.stockmanager.account.entity.AccountHistorySnapshot;

/**
 * 账户与实时组合接口，统一从认证上下文提取用户 ID 并交给服务层做资源归属校验。
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    /** 提供 MySQL 当前账户、持仓、历史快照和量化服务状态查询。 */
    private final AccountService accountService;
    /** 提供 Redis 优先的实时组合读取以及用户手动 QMT 刷新。 */
    private final PortfolioLiveService portfolioLiveService;

    /** 注入账户兼容服务和统一实时组合服务。 */
    public AccountController(AccountService accountService, PortfolioLiveService portfolioLiveService) {
        this.accountService = accountService;
        this.portfolioLiveService = portfolioLiveService;
    }

    /** 查询当前用户的全部账户摘要。 */
    @GetMapping
    public ApiResponse<List<AccountView>> list(Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(accountService.listAccounts(userId(authentication)), traceId(request));
    }

    /** 查询单个账户摘要。 */
    @GetMapping("/{accountId}")
    public ApiResponse<AccountView> detail(@PathVariable Long accountId, Authentication authentication,
                                           HttpServletRequest request) {
        return ApiResponse.success(accountService.getAccount(userId(authentication), accountId), traceId(request));
    }

    /** 查询 MySQL 当前持仓兼容接口。实时页面优先使用 live 接口。 */
    @GetMapping("/{accountId}/positions")
    public ApiResponse<List<PositionView>> positions(@PathVariable Long accountId, Authentication authentication,
                                                     HttpServletRequest request) {
        return ApiResponse.success(accountService.listPositions(userId(authentication), accountId), traceId(request));
    }

    /** 兼容旧前端的手动同步入口，内部复用实时组合刷新链路。 */
    @PostMapping("/{accountId}/sync")
    public ApiResponse<AccountSyncView> sync(@PathVariable Long accountId, Authentication authentication,
                                             HttpServletRequest request) {
        // 同一 traceId 会继续透传 Java -> FastAPI -> QMT 适配器，便于跨进程日志定位。
        String traceId = traceId(request);
        CurrentPortfolioSnapshot snapshot = portfolioLiveService.refresh(userId(authentication), accountId, traceId);
        // 仅在旧接口返回前做视图转换；实际采集与持久化仍只有 PortfolioLiveService 一条链路。
        return ApiResponse.success("QMT账户同步完成", toSyncView(snapshot), traceId);
    }

    /** 读取 Redis 当前组合；缓存不可用或数据过期时由服务层显式降级。 */
    @GetMapping("/{accountId}/live")
    public ApiResponse<CurrentPortfolioSnapshot> live(@PathVariable Long accountId, Authentication authentication,
                                                       HttpServletRequest request) {
        return ApiResponse.success(portfolioLiveService.current(userId(authentication), accountId), traceId(request));
    }

    /** 用户手动触发一次 QMT 同步刷新，并立即更新 Redis 与 MySQL 当前事实。 */
    @PostMapping("/{accountId}/refresh")
    public ApiResponse<CurrentPortfolioSnapshot> refresh(@PathVariable Long accountId, Authentication authentication,
                                                          HttpServletRequest request) {
        String traceId = traceId(request);
        // refresh 是同步强制刷新：HTTP 返回时本次 QMT 快照已经更新 Redis 和 MySQL 当前表。
        return ApiResponse.success("QMT实时组合已刷新",
                portfolioLiveService.refresh(userId(authentication), accountId, traceId), traceId);
    }

    /** 查询用于时间对比、风险和归因的 MySQL 账户历史快照。 */
    @GetMapping("/{accountId}/snapshots")
    public ApiResponse<List<AccountHistorySnapshot>> snapshots(@PathVariable Long accountId,
                                                        @RequestParam(required = false) LocalDate from,
                                                        @RequestParam(required = false) LocalDate to,
                                                        Authentication authentication,
                                                        HttpServletRequest request) {
        return ApiResponse.success(accountService.listSnapshots(userId(authentication), accountId, from, to),
                traceId(request));
    }

    /** 查询 FastAPI/QMT 连接与交易开关状态。 */
    @GetMapping("/qmt/status")
    public ApiResponse<Map<String, Object>> qmtStatus(HttpServletRequest request) {
        String traceId = traceId(request);
        return ApiResponse.success(accountService.qmtStatus(traceId), traceId);
    }

    /** 从认证主体读取 Snowflake 用户 ID。 */
    private Long userId(Authentication authentication) {
        // JwtAuthenticationFilter 把用户 Snowflake ID 保存为 Authentication.name。
        return Long.valueOf(authentication.getName());
    }

    /** 获取当前请求链路 ID。 */
    private String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("traceId"));
    }

    /** 把统一实时快照转换为旧同步接口的兼容响应。 */
    private AccountSyncView toSyncView(CurrentPortfolioSnapshot snapshot) {
        // 旧接口没有 dataVersion/stale 等字段，只投影其能够识别的账户、持仓和警告。
        return new AccountSyncView(snapshot.account(), snapshot.positions(), snapshot.source().toLowerCase(),
                snapshot.snapshotTime(), snapshot.warnings());
    }
}
