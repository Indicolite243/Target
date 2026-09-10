package com.stockmanager.market.service;

import java.util.List;
import java.util.Map;

/**
 * 实时行情批量查询的应用服务契约。
 *
 * <p>Controller只依赖这个接口，不感知行情来自Redis缓存还是FastAPI/QMT。
 * 实现类负责规范化证券代码、合并缓存命中与上游结果，并恢复调用方请求顺序。</p>
 */
public interface MarketService {
    /**
     * 按请求顺序批量返回最新行情。
     *
     * @param symbols 调用方请求的证券代码，可包含空格、大小写差异和重复项
     * @param allowStale true时允许读取Redis短缓存，false时全部代码直接请求FastAPI/QMT
     * @param traceId 当前请求链路号，继续透传给quant-service
     * @return 包含quotes、missingSymbols和marketStatus的稳定业务结果
     */
    Map<String, Object> latestQuotes(List<String> symbols, boolean allowStale, String traceId);
}
