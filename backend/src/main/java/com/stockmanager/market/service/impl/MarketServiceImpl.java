package com.stockmanager.market.service.impl;

import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.market.cache.MarketQuoteCache;
import com.stockmanager.market.service.MarketService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时行情查询实现，使用 Redis 短缓存合并重复读取，并把缓存缺口批量交给 FastAPI/QMT。
 *
 * <p>该类不持久化历史行情。缓存用于降低两秒刷新和多组件复用造成的 QMT 调用压力；最终返回顺序
 * 始终与调用方传入的证券代码顺序一致。</p>
 */
@Service
public class MarketServiceImpl implements MarketService {
    /** Spring到FastAPI的内部HTTP客户端；最终由Python适配器调用QMT行情SDK。 */
    private final QuantClient quantClient;
    /** Redis毫秒级行情缓存；缓存异常必须自动降级到FastAPI/QMT。 */
    private final MarketQuoteCache quoteCache;

    /** 通过构造器注入FastAPI/QMT客户端和Redis行情缓存。 */
    public MarketServiceImpl(QuantClient quantClient, MarketQuoteCache quoteCache) {
        this.quantClient = quantClient;
        this.quoteCache = quoteCache;
    }

    /**
     * 批量获取最新行情。
     *
     * @param symbols 证券代码列表，可包含大小写和重复项
     * @param allowStale 是否允许复用短时缓存
     * @param traceId 链路追踪 ID，传递给量化服务
     * @return 行情、缺失代码和实际数据来源
     */
    @Override
    public Map<String, Object> latestQuotes(List<String> symbols, boolean allowStale, String traceId) {
        /*
         * 行情采用“按需批量读取 + Redis 短缓存”：
         * 先规范化并去重代码，再读取允许复用的缓存；缺失代码一次性发给 FastAPI/QMT，
         * 最后按用户请求的原顺序组装结果。allowStale=false 用于搜索首屏，避免读到空壳行情。
         */
        // 去除首尾空格、统一大写并按首次出现顺序去重。
        // 例如["600000.sh", " 600000.SH "]最终只保留"600000.SH"。
        List<String> normalizedSymbols = symbols.stream().map(symbol -> symbol.trim().toUpperCase()).distinct().toList();

        // 使用LinkedHashMap保存“证券代码→行情”，便于稳定合并Redis命中和QMT补查结果。
        Map<String, Map<String, Object>> quotesBySymbol = new LinkedHashMap<>();
        // 记录必须访问FastAPI/QMT的缓存缺口；最后只对这部分做一次批量上游请求。
        List<String> missingSymbols = new ArrayList<>();

        // 逐个检查规范化后的证券代码是否可以从Redis短缓存读取。
        for (String symbol : normalizedSymbols) {
            if (allowStale) {
                // 允许缓存时：命中结果放入quotesBySymbol；未命中则加入上游补查列表。
                quoteCache.get(symbol).ifPresentOrElse(
                        quote -> quotesBySymbol.put(symbol, quote),
                        () -> missingSymbols.add(symbol));
            } else {
                // 强制刷新时不读取Redis，全部代码都交给FastAPI/QMT获取当前数据。
                missingSymbols.add(symbol);
            }
        }

        // 没有缓存缺口时保持空Map；这样纯缓存请求不会产生任何FastAPI/QMT调用。
        Map<String, Object> upstream = Map.of();
        if (!missingSymbols.isEmpty()) {
            // 一个上游批量请求代替 N 个证券逐个请求，降低 QMT 调用次数和 HTTP 往返。
            // QuantClient会把Map序列化为JSON POST到/internal/v1/market/quotes。
            upstream = quantClient.quotes(Map.of(
                // 只发送Redis没有满足的证券，避免重复订阅和读取已命中标的。
                "symbols", missingSymbols,
                // fields表达Spring实际需要的行情字段，便于内部协议自描述和未来裁剪。
                "fields", List.of("lastPrice", "openPrice", "highPrice", "lowPrice", "volume", "amount"),
                // 当前业务明确优先使用QMT，不让下游静默切换成其它生产数据源。
                "preferredSource", "QMT",
                // 当前QMT接入只开放模拟环境，避免行情请求与交易环境口径不一致。
                "environment", "SIMULATION",
                // 把原始缓存策略继续传给Python；false时QMT适配器会尽量取得首个有效tick。
                "allowStale", allowStale
            ), traceId);

            // FastAPI data中的quotes应当是JSON数组；协议异常时安全忽略，不进行错误强转。
            Object rawQuotes = upstream.get("quotes");
            if (rawQuotes instanceof List<?> rows) {
                // 遍历Python返回的每一行弱类型JSON对象。
                for (Object row : rows) {
                    // 非Map行不是合法行情对象，跳过它而不影响其它证券结果。
                    if (!(row instanceof Map<?, ?> rawQuote)) continue;

                    // 将Map<?,?>复制成Map<String,Object>，避免泛型擦除数据直接泄漏到业务层。
                    Map<String, Object> quote = new LinkedHashMap<>();
                    // JSON对象键理论上是字符串；String.valueOf让异常数字键也能安全转换。
                    rawQuote.forEach((key, value) -> quote.put(String.valueOf(key), value));

                    // 每行必须包含证券代码，否则无法放入索引或与用户请求匹配。
                    Object rawSymbol = quote.get("symbol");
                    if (rawSymbol == null || String.valueOf(rawSymbol).isBlank()) continue;

                    // 再次规范化上游返回代码，防止大小写差异造成缓存键和结果匹配失败。
                    String symbol = String.valueOf(rawSymbol).trim().toUpperCase();
                    // QMT新数据覆盖同代码的旧缓存结果；强制刷新场景本来就没有预放缓存值。
                    quotesBySymbol.put(symbol, quote);
                    // 把有效QMT行情写入Redis短缓存，供紧随其后的组件刷新复用。
                    quoteCache.put(symbol, quote);
                }
            }
        }

        // 上游返回顺序不一定稳定，最终按前端输入顺序输出，保证表格与搜索项一一对应。
        // quotesBySymbol中不存在的代码会被过滤，并由missingSymbols字段告诉调用方。
        List<Map<String, Object>> orderedQuotes = normalizedSymbols.stream()
                .map(quotesBySymbol::get).filter(java.util.Objects::nonNull).toList();

        // 使用有序Map组装稳定响应字段，便于前端处理和日志阅读。
        Map<String, Object> result = new LinkedHashMap<>();
        // quotes严格按规范化后的用户请求顺序排列。
        result.put("quotes", orderedQuotes);
        // 缺失代码优先采用FastAPI判断结果；纯缓存请求没有缺失项。
        result.put("missingSymbols", upstream.getOrDefault("missingSymbols", List.of()));
        // 没有上游缺口说明本次完全来自Redis，否则沿用FastAPI返回的市场状态，默认标记QMT。
        result.put("marketStatus", missingSymbols.isEmpty() ? "REDIS_CACHE" : upstream.getOrDefault("marketStatus", "QMT"));
        // 返回业务data，由Controller再包装成统一ApiResponse。
        return result;
    }
}
