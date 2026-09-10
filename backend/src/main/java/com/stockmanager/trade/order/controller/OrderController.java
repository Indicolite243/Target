/**
 * 订单 HTTP 接口层。
 *
 * <p>Controller 只负责 HTTP 协议适配：读取请求头、请求体和认证主体，
 * 把日期字符串转换成查询边界，再调用 OrderService。真正的幂等、状态机、
 * MySQL 事务和 QMT 调用均在 Service 层完成。</p>
 */
package com.stockmanager.trade.order.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.trade.order.dto.CancelOrderRequest;
import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.service.OrderService;
import com.stockmanager.trade.order.vo.OrderPageView;
import com.stockmanager.trade.order.vo.OrderTimelineView;
import com.stockmanager.trade.order.vo.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 交易委托 HTTP 接口，负责幂等键、日期参数和认证主体的协议层校验。
 *
 * <p>下单和撤单会产生 QMT 外部副作用；订单列表、时间线和历史删除只操作本地 MySQL 数据。</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    /** 订单应用服务；Controller 不直接操作 Mapper 或 QuantClient。 */
    private final OrderService orderService;

    /** 通过构造器注入订单应用服务，便于 Spring 管理和单元测试替换。 */
    public OrderController(OrderService orderService) { this.orderService = orderService; }

    /**
     * 提交一笔带幂等键的委托请求。
     *
     * <p>请求路径是 POST /api/v1/orders。X-Idempotency-Key 用来识别一次用户意图，
     * 前端超时重试时必须复用同一个值，Service 才能返回第一次提交结果而不是再次下单。</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderView> submit(@RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                         @Valid @RequestBody SubmitOrderRequest body,
                                         Authentication authentication, HttpServletRequest request) {
        // Header 缺失会由 Spring 先拦截；空白值仍需要在这里显式拒绝，避免空键破坏幂等语义。
        if (idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少幂等键");

        // userId 从JWT认证主体读取，不能相信请求体中的用户字段；traceId用于日志、审计和跨服务排查。
        return ApiResponse.success("委托已受理", orderService.submit(userId(authentication), idempotencyKey,
                body, traceId(request)), traceId(request));
    }

    /**
     * 分页查询本地订单历史，可按账户和日期范围筛选。
     *
     * <p>该接口只读本地 MySQL，不在用户刷新页面时同步访问 QMT；订单状态由后台调度器对账更新。</p>
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Long accountId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "50") long pageSize,
                                                 @RequestParam(name = "start_date", required = false) String startDate,
                                                 @RequestParam(name = "end_date", required = false) String endDate,
                                                 Authentication authentication, HttpServletRequest request) {
        // start_date是包含边界；end_date会在parseDate中转换为次日零点的开区间，覆盖整天数据。
        OrderPageView result = orderService.list(userId(authentication), accountId, page, pageSize,
                parseDate(startDate, false), parseDate(endDate, true));

        // Map结构保持既有前端协议；分页字段直接来自Service，避免Controller重新计算总页数。
        return ApiResponse.success(Map.of("items", result.items(), "page", result.page(), "pageSize", result.pageSize(),
                "total", result.total(), "totalPages", result.totalPages(), "hasNext", result.hasNext()), traceId(request));
    }

    /**
     * 仅软删除一条本地历史记录，不会向 QMT 发送撤单。
     *
     * <p>“删除历史”和“撤单”是两个完全不同的动作：前者只隐藏终态订单，后者会产生券商副作用。</p>
     */
    @DeleteMapping("/{orderId}")
    public ApiResponse<Map<String, Object>> deleteHistory(@PathVariable Long orderId,
                                                           Authentication authentication,
                                                           HttpServletRequest request) {
        // Service会再次校验订单归属、软删除状态和是否为终态，Controller不自行复制规则。
        orderService.deleteHistory(userId(authentication), orderId, traceId(request));
        // deleted=1表示本次调用完成了隐藏动作；幂等重复删除仍返回成功。
        return ApiResponse.success(Map.of("deleted", 1), traceId(request));
    }

    /** 软删除可选日期范围内的全部可见终态历史记录。 */
    @DeleteMapping("/history")
    public ApiResponse<Map<String, Object>> deleteFilteredHistory(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            Authentication authentication, HttpServletRequest request) {
        // 批量删除沿用列表的日期边界规则，并由Service过滤进行中的订单。
        int deleted = orderService.deleteFilteredHistory(userId(authentication), parseDate(startDate, false),
                parseDate(endDate, true), traceId(request));
        // 返回实际被标记deleted_at的数量，而不是请求匹配数量。
        return ApiResponse.success(Map.of("deleted", deleted), traceId(request));
    }

    /** 查询订单状态与审计时间线；时间线只允许订单所属用户访问。 */
    @GetMapping("/{orderId}/timeline")
    public ApiResponse<OrderTimelineView> timeline(@PathVariable Long orderId, Authentication authentication,
                                                    HttpServletRequest request) {
        // requireOrder在Service内完成用户隔离校验，防止越权读取其它用户的交易轨迹。
        return ApiResponse.success(orderService.timeline(userId(authentication), orderId), traceId(request));
    }

    /**
     * 发起带幂等键的撤单请求；最终状态仍由后台对账确认。
     *
     * <p>撤单请求体当前只保留兼容字段，真正定位订单依赖路径中的 orderId 和幂等键，
     * 不允许客户端自行提交外部订单号覆盖数据库值。</p>
     */
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable Long orderId,
                                         @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                         @RequestBody(required = false) CancelOrderRequest body,
                                         Authentication authentication, HttpServletRequest request) {
        // body暂不参与撤单定位；保留参数是为了兼容前端传入reason的协议。
        return ApiResponse.success("撤单请求已受理", orderService.cancel(userId(authentication), orderId,
                idempotencyKey, traceId(request)), traceId(request));
    }

    /**
     * 从认证主体读取用户 ID。
     *
     * <p>JWT过滤器把用户主键放在 Authentication.name 中；如果认证已通过，这里不再从URL读取用户ID。</p>
     */
    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }

    /**
     * 把 YYYY-MM-DD 转换为查询边界，结束日期使用次日零点的开区间。
     *
     * <p>例如 end_date=2026-08-28 会转换为 2026-08-29T00:00，SQL使用 lt 后可包含28日全部时分秒。</p>
     */
    private LocalDateTime parseDate(String value, boolean endExclusive) {
        // 未提供日期表示不限制该方向的时间范围，交给Service构造不带日期条件的查询。
        if (value == null || value.isBlank()) return null;
        try {
            // LocalDate严格按ISO YYYY-MM-DD解析，避免依赖服务器默认时区和格式。
            LocalDate date = LocalDate.parse(value);
            // 开始日期取当天零点；结束日期加一天后取零点，作为SQL小于条件的右边界。
            return (endExclusive ? date.plusDays(1) : date).atStartOfDay();
        } catch (DateTimeParseException ex) {
            // 协议错误在Controller层转换为400，Service只接收已经解析的时间对象。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式必须为 YYYY-MM-DD");
        }
    }

    /** 获取当前请求链路 ID；过滤器未设置时以字符串形式保留，保证响应字段非空。 */
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
