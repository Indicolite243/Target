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

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accountService;
    private final PortfolioLiveService portfolioLiveService;

    public AccountController(AccountService accountService, PortfolioLiveService portfolioLiveService) {
        this.accountService = accountService;
        this.portfolioLiveService = portfolioLiveService;
    }

    @GetMapping
    public ApiResponse<List<AccountView>> list(Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(accountService.listAccounts(userId(authentication)), traceId(request));
    }

    @GetMapping("/{accountId}")
    public ApiResponse<AccountView> detail(@PathVariable Long accountId, Authentication authentication,
                                           HttpServletRequest request) {
        return ApiResponse.success(accountService.getAccount(userId(authentication), accountId), traceId(request));
    }

    @GetMapping("/{accountId}/positions")
    public ApiResponse<List<PositionView>> positions(@PathVariable Long accountId, Authentication authentication,
                                                     HttpServletRequest request) {
        return ApiResponse.success(accountService.listPositions(userId(authentication), accountId), traceId(request));
    }

    @PostMapping("/{accountId}/sync")
    public ApiResponse<AccountSyncView> sync(@PathVariable Long accountId, Authentication authentication,
                                             HttpServletRequest request) {
        String traceId = traceId(request);
        CurrentPortfolioSnapshot snapshot = portfolioLiveService.refresh(userId(authentication), accountId, traceId);
        return ApiResponse.success("QMT账户同步完成", toSyncView(snapshot), traceId);
    }

    @GetMapping("/{accountId}/live")
    public ApiResponse<CurrentPortfolioSnapshot> live(@PathVariable Long accountId, Authentication authentication,
                                                       HttpServletRequest request) {
        return ApiResponse.success(portfolioLiveService.current(userId(authentication), accountId), traceId(request));
    }

    @PostMapping("/{accountId}/refresh")
    public ApiResponse<CurrentPortfolioSnapshot> refresh(@PathVariable Long accountId, Authentication authentication,
                                                          HttpServletRequest request) {
        String traceId = traceId(request);
        return ApiResponse.success("QMT实时组合已刷新",
                portfolioLiveService.refresh(userId(authentication), accountId, traceId), traceId);
    }

    @GetMapping("/{accountId}/snapshots")
    public ApiResponse<List<AccountHistorySnapshot>> snapshots(@PathVariable Long accountId,
                                                        @RequestParam(required = false) LocalDate from,
                                                        @RequestParam(required = false) LocalDate to,
                                                        Authentication authentication,
                                                        HttpServletRequest request) {
        return ApiResponse.success(accountService.listSnapshots(userId(authentication), accountId, from, to),
                traceId(request));
    }

    @GetMapping("/qmt/status")
    public ApiResponse<Map<String, Object>> qmtStatus(HttpServletRequest request) {
        String traceId = traceId(request);
        return ApiResponse.success(accountService.qmtStatus(traceId), traceId);
    }

    private Long userId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }

    private String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("traceId"));
    }

    private AccountSyncView toSyncView(CurrentPortfolioSnapshot snapshot) {
        return new AccountSyncView(snapshot.account(), snapshot.positions(), snapshot.source().toLowerCase(),
                snapshot.snapshotTime(), snapshot.warnings());
    }
}
