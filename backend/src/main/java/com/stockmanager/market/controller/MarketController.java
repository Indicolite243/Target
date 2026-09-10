package com.stockmanager.market.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.market.service.MarketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 行情查询HTTP入口，负责把前端查询参数转换成行情服务需要的证券代码列表。
 *
 * <p>本层只处理HTTP协议、输入长度、空值、重复值和单次查询数量，不直接读取Redis，
 * 也不直接访问FastAPI/QMT。缓存命中、上游补查和结果排序统一交给MarketService。</p>
 */
@Validated
@RestController//spring序列化为json 3
@RequestMapping("/api/v1/market")
public class MarketController {
    /** 行情应用服务接口；Controller不依赖具体的Redis/QMT实现。 */
    private final MarketService marketService;

    /** 通过构造器注入行情查询服务，便于Spring管理和测试替换。 */
    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    /**
     * 批量查询最多100个证券的最新行情。
     *
     * <p>调用示例：{@code GET /api/v1/market/quotes?symbols=600000.SH,510300.SH&allowStale=true}。</p>
     *
     * @param symbols 逗号分隔的原始证券代码，最长2000字符
     * @param allowStale 是否允许复用Redis中的毫秒级短缓存；false表示强制请求FastAPI/QMT
     * @param request 当前Servlet请求，用于读取TraceId
     * @return 统一响应，data中包含quotes、missingSymbols和marketStatus
     */
    @GetMapping("/quotes")
    public ApiResponse<Map<String, Object>> quotes(
            // @Size配合类上的@Validated限制URL参数长度，防止超长输入造成大量解析和上游订阅。
            @RequestParam @Size(max = 2000) String symbols,
            // 自动刷新默认允许短缓存；首次搜索或手动强制刷新可显式传false。
            @RequestParam(defaultValue = "true") boolean allowStale,//不传默认为true 它控制是否允许读取Redis里的短期行情缓存。
            HttpServletRequest request) {
        // 1. 按英文逗号拆分多个代码；例如"600000.SH, 510300.SH"会得到两项。
        List<String> requested = Arrays.stream(symbols.split(","))
                // 2. 删除每项首尾空格，避免空格影响证券代码匹配。
                .map(String::trim)
                // 3. 过滤连续逗号或纯空格产生的空代码。
                .filter(value -> !value.isBlank())
                // 4. 在当前大小写下先去重；Service还会转大写并做第二次最终去重。
                .distinct()
                // 5. 无论原始字符串包含多少项，一次请求最多进入业务层100只证券。
                .limit(100)
                // 6. 收集为不可修改的List，交给MarketService处理。
                .toList();

        // TraceId贯穿Controller、Service、QuantClient和FastAPI，方便定位一次完整行情请求。
        // Service返回业务data；ApiResponse补充success、message和traceId等统一协议外壳。
        return ApiResponse.success(marketService.latestQuotes(requested, allowStale, traceId(request)), traceId(request));
    }

    /** 获取TraceIdFilter预先写入当前请求属性的链路ID。 */
    private String traceId(HttpServletRequest request) {
        // String.valueOf可保证属性缺失时仍得到稳定文本，正常请求中该值由过滤器提前生成。
        return String.valueOf(request.getAttribute("traceId"));
    }
}
