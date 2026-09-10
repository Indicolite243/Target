package com.stockmanager.trade.order.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 统一维护订单状态规则，确保下单、撤单、后台对账和历史删除使用同一套状态词汇。
 * 数据库中尽量保存 canonical 状态；QMT 的原始状态仍放在审计明细，便于排查 SDK 差异。
 */
public final class OrderStatusPolicy {
    /** 已结束状态：不会再接受新的成交/撤单状态迁移。 */
    private static final Set<String> TERMINAL = Set.of("FILLED", "CANCELED", "REJECTED", "FAILED");
    /** 正常轮询需要继续跟踪的应用状态。 */
    private static final Set<String> TRACKABLE = Set.of("PENDING_SUBMIT", "SUBMITTED", "PARTIALLY_FILLED",
            "CANCEL_PENDING", "UNKNOWN");
    /** 用户可以发起撤单的状态；终态和刚创建尚未提交的状态均不可撤。 */
    private static final Set<String> CANCELABLE = Set.of("SUBMITTED", "PARTIALLY_FILLED", "UNKNOWN");
    /** 升级前数据库可能存在的QMT原始状态，兼容迁移期间的查询。 */
    private static final Set<String> LEGACY_TRACKABLE = Set.of(
            "UNREPORTED", "WAIT_REPORTING", "REPORTED", "REPORTED_CANCEL",
            "PARTIALLY_FILLED_CANCEL_PENDING");

    private OrderStatusPolicy() {
        // 工具类不允许实例化，所有规则通过static方法调用。
    }

    /** 判断状态是否已进入不可再变化的终态。 */
    public static boolean isTerminal(String status) {
        // 先归一化未知/旧状态，再判断是否属于终态集合。
        return TERMINAL.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    /** 判断状态是否仍需后台查询 QMT 对账。 */
    public static boolean isTrackable(String status) {
        // UNKNOWN也会被视为可跟踪，让后台有机会通过QMT查询收敛它。
        return TRACKABLE.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    /** 判断当前状态是否允许用户发起撤单。 */
    public static boolean canCancel(String status) {
        // CANCEL_PENDING不在可撤集合中，避免重复点击重复发起撤单。
        return CANCELABLE.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    /**
     * 合并本地状态与一次券商状态观测。
     *
     * <p>撤单请求被受理后，QMT 的查询接口可能短暂返回撤单前缓存的 REPORTED/PARTIALLY_FILLED。
     * 这些非终态观测不能把本地状态从 CANCEL_PENDING 回退，否则页面会再次开放撤单并形成循环。
     * 只有 FILLED、CANCELED、REJECTED、FAILED 等明确终态能够结束撤单确认。</p>
     */
    public static String resolveBrokerObservation(String currentStatus, String rawBrokerStatus) {
        // current是本地已确认状态，observed是本轮QMT返回状态。
        String current = canonicalizeBrokerStatus(currentStatus, "UNKNOWN");
        String observed = canonicalizeBrokerStatus(rawBrokerStatus, current);
        // 撤单确认期间忽略非终态旧观测，防止状态从CANCEL_PENDING回退到SUBMITTED。
        if ("CANCEL_PENDING".equals(current) && !isTerminal(observed)) return "CANCEL_PENDING";
        // 只有QMT给出明确终态，才允许结束撤单确认流程。
        return observed;
    }

    /**
     * 判断“QMT 当日委托列表缺失”是否足以收敛一条历史撤单。
     *
     * <p>缺失本身不能证明普通订单已撤或已成；这里只处理已经发起撤单、且创建日期早于当前日期的
     * CANCEL_PENDING 订单。QMT 股票委托查询只返回当日数据，上一日撤单到下一日仍不存在时已不可能
     * 继续作为当日在途委托。</p>
     */
    public static boolean shouldReconcileMissingPriorDayCancellation(String status, LocalDateTime createdAt,
                                                                      LocalDate currentDate) {
        // 必须同时满足：仍在撤单确认、创建日期早于今天、日期参数有效。
        return "CANCEL_PENDING".equals(canonicalizeBrokerStatus(status, "UNKNOWN"))
                && createdAt != null && currentDate != null && createdAt.toLocalDate().isBefore(currentDate);
    }

    /**
     * 将 QMT 和历史版本的订单状态名称归一化为应用生命周期状态。
     * 原始券商状态保留在审计详情中，数据库主状态必须保持标准词汇，确保轮询、撤单和终态判断一致。
     */
    public static String canonicalizeBrokerStatus(String rawStatus, String fallback) {
        // QMT/旧版本系统可能返回不同名称，先标准化再参与终态、可撤单和轮询判断。
        String status = rawStatus == null || rawStatus.isBlank() ? fallback : rawStatus;
        // fallback也可能为空；统一落到UNKNOWN，保证调用方不会收到null状态。
        if (status == null || status.isBlank()) return "UNKNOWN";
        // trim和upper让大小写、首尾空格不影响状态机分支。
        return switch (status.trim().toUpperCase()) {
            // 未上报/待上报/本地待提交都归为同一阶段。
            case "UNREPORTED", "WAIT_REPORTING", "PENDING_SUBMIT" -> "PENDING_SUBMIT";
            // 已报和适配器返回的SUBMITTED表示外部已接收但未终态。
            case "REPORTED", "SUBMITTED" -> "SUBMITTED";
            // QMT的撤单中和本地撤单中统一为CANCEL_PENDING。
            case "REPORTED_CANCEL", "PARTIALLY_FILLED_CANCEL_PENDING", "CANCEL_PENDING" -> "CANCEL_PENDING";
            // 部分成交仍需继续跟踪，也通常允许撤剩余数量。
            case "PARTIALLY_FILLED" -> "PARTIALLY_FILLED";
            // 部分撤和全部撤都在应用层收敛为CANCELED终态。
            case "PARTIALLY_CANCELED", "CANCELED" -> "CANCELED";
            // 明确终态和UNKNOWN原样保留（已统一大写）。
            case "FILLED", "REJECTED", "FAILED", "UNKNOWN" -> status.trim().toUpperCase();
            // 未识别的券商新状态不能冒险当作已成或已撤，先以UNKNOWN等待对账。
            default -> "UNKNOWN";
        };
    }

    /** 把历史遗留原始状态也纳入轮询集合，升级后可在下一次对账时自动恢复到标准状态。 */
    public static Set<String> trackableStoredStates() {
        // 复制集合避免暴露内部Set；LinkedHashSet让结果顺序稳定，便于SQL和测试日志。
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(TRACKABLE);
        // 同时加入历史原始状态，保证升级后旧订单仍能被后台轮询。
        result.addAll(LEGACY_TRACKABLE);
        // Set.copyOf返回不可变集合，调用方无法修改状态规则。
        return Set.copyOf(result);
    }
}
