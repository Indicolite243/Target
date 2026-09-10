package com.stockmanager.integration.quant;

import com.stockmanager.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring Boot访问Python FastAPI量化服务的统一内部客户端。
 *
 * <p>本类是Java业务层与quant-service之间的HTTP边界。Controller和Service不直接拼接
 * Python地址、认证请求头或解析弱类型JSON，而是统一通过这里完成以下工作：</p>
 *
 * <ol>
 *     <li>根据业务类型选择“实时短超时”或“计算长超时”客户端；</li>
 *     <li>按路由要求发送内部Token、TraceId、RequestId和协议版本；</li>
 *     <li>解析FastAPI统一的{@code {success, data, traceId, durationMs}}响应；</li>
 *     <li>把HTTP 4xx、5xx、超时和响应结构错误转换成Spring可识别的BusinessException；</li>
 *     <li>下单、撤单只返回QMT结果，不在本类内执行自动重试或修改数据库状态。</li>
 * </ol>
 *
 * <p>特别注意：网络超时不等于QMT没有执行。订单Service会把不确定结果保存为UNKNOWN或
 * CANCEL_PENDING并等待后台对账，所以QuantClient绝不能自行重发下单/撤单请求。</p>
 */
@Component
public class QuantClient {
    /**
     * 长耗时客户端：用于风险计算、历史重建和回测。
     * 读取超时固定为360秒，允许Python完成CPU计算和历史行情读取。
     */
    private final RestClient restClient;

    /**
     * 实时客户端：用于账户、行情、订单和健康状态读取。
     * 较短超时可以避免QMT异常时长期占用Spring请求线程。
     */
    private final RestClient realtimeRestClient;

    /** Spring和FastAPI之间的共享内部令牌，只写入请求头，不进入响应或业务日志。 */
    private final String internalToken;

    /**
     * 根据配置创建两套复用连接配置的RestClient。
     *
     * @param builder Spring提供的RestClient构建器
     * @param baseUrl FastAPI内部接口根地址，通常包含{@code /internal/v1}
     * @param internalToken Spring与FastAPI共享的内部认证令牌
     * @param connectTimeoutMs 建立TCP连接的最大等待时间，默认500毫秒
     * @param realtimeReadTimeoutMs 实时接口读取响应的最大等待时间，默认2000毫秒
     */
    public QuantClient(RestClient.Builder builder,
                       @Value("${app.quant-service.base-url}") String baseUrl,
                       @Value("${app.quant-service.internal-token}") String internalToken,
                       @Value("${app.quant-service.connect-timeout-ms:500}") int connectTimeoutMs,
                       @Value("${app.quant-service.realtime-read-timeout-ms:2000}") int realtimeReadTimeoutMs) {
        // 实时请求工厂负责账户、行情和交易接口，连接/读取都采用较短超时。
        SimpleClientHttpRequestFactory realtimeRequestFactory = new SimpleClientHttpRequestFactory();
        // 连接超时限制“无法连接FastAPI”时的等待时间。
        realtimeRequestFactory.setConnectTimeout(connectTimeoutMs);
        // 读取超时限制“连接成功但QMT迟迟不返回”时占用线程的时间。
        realtimeRequestFactory.setReadTimeout(realtimeReadTimeoutMs);
        // baseUrl在这里统一设置，后续方法只需要传相对路径。
        this.realtimeRestClient = builder.requestFactory(realtimeRequestFactory).baseUrl(baseUrl).build();

        // 计算型请求可能需要读取历史文件或执行回测，因此使用独立的长超时请求工厂。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 建立连接仍使用短超时：FastAPI未启动时没有必要等待数分钟。
        requestFactory.setConnectTimeout(connectTimeoutMs);
        // 风险、历史和回测最多等待360秒；这不是订单接口的超时配置。
        requestFactory.setReadTimeout(360_000);
        // 创建长耗时客户端，与实时客户端共享同一个baseUrl但使用不同读取超时。
        this.restClient = builder.requestFactory(requestFactory).baseUrl(baseUrl).build();
        // 保存内部令牌，后续由post/query/backtest方法写入X-Internal-Token请求头。
        this.internalToken = internalToken;
    }

    /**
     * 调用兼容账户同步接口，从QMT读取账户资产、持仓、委托和成交快照。
     *
     * @param request 账户号、交易环境及是否包含委托/成交等同步参数
     * @param traceId 当前Spring请求链路号
     * @return FastAPI响应中的data对象
     */
    public Map<String, Object> syncAccount(Map<String, Object> request, String traceId) {
        // 账户同步属于用户等待的实时链路，使用短超时客户端；失败统一映射为503601。
        return post(realtimeRestClient, "/accounts/sync", request, traceId, 503601, "账户同步服务暂时不可用");
    }

    /**
     * 一次获取账户资产和完整持仓的实时组合快照。
     *
     * <p>该接口服务于Redis实时快照链路，Spring拿到结果后再决定写Redis/MySQL；
     * QuantClient本身不缓存也不持久化返回数据。</p>
     */
    public Map<String, Object> livePortfolio(Map<String, Object> request, String traceId) {
        // 一次请求同时带回资产与全部持仓，避免N+1式的多次QMT调用。
        return post(realtimeRestClient, "/accounts/live", request, traceId, 503601, "QMT实时组合服务暂时不可用");
    }

    /**
     * 查询FastAPI运行模式、QMT连接/订阅状态以及读写开关。
     *
     * <p>FastAPI健康路由用于本机运行状态探测，当前协议只要求TraceId；它不会返回完整资金账号、
     * Token或持仓数据。其它业务路由仍必须通过内部Token认证。</p>
     */
    public Map<String, Object> health(String traceId) {
        try {
            // 使用短超时客户端，健康探测不能因QMT卡顿长期阻塞页面或Spring健康链路。
            Map<?, ?> response = realtimeRestClient.get().uri("/health")
                    // 透传链路号，便于把Spring日志与FastAPI日志对应起来。
                    .header("X-Trace-Id", traceId)
                    // retrieve触发HTTP请求，并由RestClient对非2xx状态抛出异常。
                    .retrieve()
                    // FastAPI统一响应先解析成弱类型Map，随后再校验success和data。
                    .body(Map.class);

            // null响应或success不为true都表示没有拿到可信的健康状态。
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503602, "QMT连接状态暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 业务所需健康字段都位于统一响应的data对象中。
            Object data = response.get("data");
            // data必须是JSON对象；数组、字符串或null说明Python/Java协议版本不一致。
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502602, "Python健康检查返回结构错误", HttpStatus.BAD_GATEWAY);
            }

            // 运行时已经确认data是Map，只需抑制Java泛型擦除产生的未检查转换警告。
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            // 返回data本身，不把success、traceId等协议外壳泄漏给业务Service。
            return result;
        } catch (BusinessException ex) {
            // 保留上面已经分类好的业务错误码和HTTP状态，不进行二次包装。
            throw ex;
        } catch (Exception ex) {
            // 连接失败、读取超时、非2xx响应或反序列化错误统一降级为服务不可用。
            throw new BusinessException(503602, "QMT连接状态暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** 批量查询实时行情；request通常包含去重后的证券代码列表。 */
    public Map<String, Object> quotes(Map<String, Object> request, String traceId) {
        // 行情搜索是交互式实时请求，采用短超时，FastAPI内部负责QMT订阅和首tick等待。
        return post(realtimeRestClient, "/market/quotes", request, traceId, 503201, "行情服务暂时不可用");
    }

    /**
     * 向FastAPI/QMT提交一笔委托。
     *
     * <p>本方法只发送一次HTTP请求。4xx表示QMT或参数校验明确拒绝，会转换为422301；
     * 超时和5xx转换为503301，由OrderService把订单保存为UNKNOWN并等待对账。</p>
     */
    public Map<String, Object> submitOrder(Map<String, Object> request, String traceId) {
        // 下单必须使用实时短超时客户端，且显式区分“明确拒绝”和“结果不确定”。
        return post(realtimeRestClient, "/orders/submit", request, traceId, 503301, "交易服务暂时不可用",
                422301, "委托被QMT拒绝");
    }

    /**
     * 调用Python风险统计计算；计算可能遍历历史序列，因此使用长超时客户端。
     *
     * @param request 组合收益序列、基准序列和风险计算参数
     * @param traceId 当前量化任务链路号
     * @return 风险指标、风险等级及计算元数据
     */
    public Map<String, Object> calculateRisk(Map<String, Object> request, String traceId) {
        return post(restClient, "/risk/calculate", request, traceId, 503401, "风险计算服务暂时不可用");
    }

    /**
     * 回放组合历史行情或读取统一历史序列；历史行情读取允许最长360秒。
     *
     * @param request 账户、日期区间、持仓快照和数据来源等参数
     * @param traceId 当前量化任务链路号
     * @return 统一日期轴上的组合价值、收益率和基准序列
     */
    public Map<String, Object> portfolioHistory(Map<String, Object> request, String traceId) {
        return post(restClient, "/analysis/portfolio-history", request, traceId, 503402, "QMT历史行情服务暂时不可用");
    }

    /**
     * 向FastAPI/QMT发起撤单请求。
     *
     * <p>与下单相同，本方法绝不自动重试。4xx映射为明确拒绝，网络异常由订单层保留
     * CANCEL_PENDING并等待后台查询最终状态。</p>
     */
    public Map<String, Object> cancelOrder(Map<String, Object> request, String traceId) {
        // rejectedCode和rejectedMessage专门描述QMT明确拒绝撤单的情况。
        return post(realtimeRestClient, "/orders/cancel", request, traceId, 503301, "撤单服务暂时不可用",
                422302, "撤单请求被QMT拒绝");
    }

    /**
     * 批量查询当前QMT订单，供后台状态对账使用。
     *
     * <p>一次请求获取QMT当前可见订单，OrderService再按externalOrderNo建立索引与本地在途订单匹配，
     * 避免对每条订单分别发起HTTP/QMT查询。</p>
     *
     * @param traceId 调度任务链路号，当前通常为order-status-poller
     * @return 包含orders列表的FastAPI data对象
     */
    public Map<String, Object> queryOrders(String traceId) {
        try {
            // 后台轮询也是实时链路，使用短超时；单轮失败时调度器会保留MySQL最近确认状态。
            Map<?, ?> response = realtimeRestClient.get().uri("/orders/query")
                    // 内部Token阻止非Spring调用方访问订单数据。
                    .header("X-Internal-Token", internalToken)
                    // TraceId用于把一次调度轮询贯穿Spring和FastAPI日志。
                    .header("X-Trace-Id", traceId)
                    // 每次HTTP尝试生成独立RequestId，便于识别重复轮询和单次网络异常。
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    // 发送请求并把统一响应解析成Map。
                    .retrieve().body(Map.class);

            // FastAPI未返回内容或success=false时，本轮查询结果不可用于修改本地状态。
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503302, "委托查询服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 业务订单列表位于data中，外层协议字段不传给订单Service。
            Object data = response.get("data");
            // data必须是JSON对象；结构异常使用502表明上下游协议不一致。
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502602, "Python 委托查询返回结构错误", HttpStatus.BAD_GATEWAY);
            }

            // instanceof已经完成运行时类型检查，泛型转换只影响编译期提示。
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            // 返回data，具体orders行的状态归一化由OrderStatePersistenceService负责。
            return result;
        } catch (BusinessException ex) {
            // 保留结构错误或服务不可用的原始业务分类。
            throw ex;
        } catch (Exception ex) {
            // 超时、连接失败、401/500及反序列化异常统一映射为本轮查询不可用。
            throw new BusinessException(503302, "委托查询服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 使用量化任务目录中的持久化文件调用Python回测接口。
     *
     * <p>这里接收{@link Path}，而不是Controller上传阶段的MultipartFile。上传临时文件可能在HTTP请求
     * 结束后被清理，异步工作线程必须使用BacktestInputStorageService已经复制到任务目录的文件。</p>
     *
     * @param strategyPath 任务目录中的Python策略文件
     * @param marketPaths 可选行情Excel文件列表，文件名必须保留证券代码语义
     * @param startDate 回测开始日期
     * @param endDate 回测结束日期
     * @param engineType 回测引擎类型，空值时使用auto
     * @param benchmarkSymbol 基准证券代码，空值时交给Python使用默认基准
     * @param bearProtection 是否启用策略熊市保护选项
     * @param traceId 当前异步任务链路号
     * @return Python回测结果data对象
     */
    public Map<String, Object> runBacktest(Path strategyPath, List<Path> marketPaths,
                                           String startDate, String endDate, String engineType,
                                            String benchmarkSymbol, boolean bearProtection, String traceId) {
        try {
            // 只发送任务目录中的 Path，不直接持有请求线程里的 MultipartFile，避免请求结束后文件句柄失效。
            // MultipartBodyBuilder负责生成multipart/form-data的各个文件和文本分段。
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            // 主策略字段名固定为file；显式保留原始文件名，Python据此判断策略文件类型。
            body.part("file", new FileSystemResource(strategyPath)).filename(strategyPath.getFileName().toString());
            // 日期以字符串传输，Python/Pydantic负责进一步格式和范围校验。
            body.part("start_date", startDate);
            body.part("end_date", endDate);
            // engineType为空时回退auto，让Python根据策略内容自动选择兼容执行器。
            body.part("engine_type", engineType == null ? "auto" : engineType);
            // 基准为空时传空字符串，而不是multipart中的null值。
            body.part("benchmark_symbol", benchmarkSymbol == null ? "" : benchmarkSymbol);
            // multipart文本字段没有原生boolean类型，因此显式转换为true/false字符串。
            body.part("enable_bear_protection", String.valueOf(bearProtection));

            // 每个行情文件使用相同字段名market_files，FastAPI按列表接收多个文件。
            for (Path marketPath : marketPaths) {
                // 保留行情原文件名（如510300.SH.xlsx），避免改名后Python无法识别证券代码。
                body.part("market_files", new FileSystemResource(marketPath)).filename(marketPath.getFileName().toString());
            }

            // 回测属于长耗时计算，使用360秒读取超时的restClient。
            Map<?, ?> response = restClient.post().uri("/backtests/run")
                    // 回测路由同样是内部接口，必须携带共享Token。
                    .header("X-Internal-Token", internalToken)
                    // 透传异步任务的链路号。
                    .header("X-Trace-Id", traceId)
                    // 每次回测HTTP调用生成唯一RequestId，用于跨服务排错。
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    // 明确声明multipart/form-data，RestClient会为每个part生成边界。
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    // build之后得到可被Spring消息转换器序列化的multipart请求体。
                    .body(body.build())
                    // 执行请求；非2xx响应会抛出RestClient相关异常。
                    .retrieve()
                    // 先把统一响应解析为Map，再检查success和data结构。
                    .body(Map.class);

            // 空响应或success=false表示没有可用的回测结果。
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503701, "回测服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 回测净值、指标和交易记录位于统一响应的data对象中。
            Object data = response.get("data");
            // data不是对象通常表示Python协议或异常包装不符合约定。
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502701, "Python 回测服务返回结构错误", HttpStatus.BAD_GATEWAY);
            }

            // 完成运行时Map检查后转换成业务层使用的Map<String,Object>。
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            // 返回业务data，不返回外层success/traceId/durationMs。
            return result;
        } catch (BusinessException ex) {
            // 保留已经明确分类的回测结构错误和服务不可用错误。
            throw ex;
        } catch (Exception ex) {
            // 文件不存在、读取失败、连接/读取超时或HTTP错误统一转换为回测服务不可用。
            // 这里附带异常消息便于任务失败原因展示，但不会输出文件内容或内部Token。
            throw new BusinessException(503701, "回测服务暂时不可用: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 普通POST接口的便捷重载。
     *
     * <p>账户、行情、风险和历史接口不需要区分“业务明确拒绝”和“服务异常”，
     * 因此两类情况使用同一组错误码/文案，再委托给完整重载处理。</p>
     */
    private Map<String, Object> post(RestClient client, String path, Map<String, Object> request, String traceId,
                                     int errorCode, String errorMessage) {
        // rejectedCode/rejectedMessage与普通错误相同，避免每个简单接口重复传参。
        return post(client, path, request, traceId, errorCode, errorMessage, errorCode, errorMessage);
    }

    /**
     * 发送统一JSON POST请求并解析FastAPI内部协议。
     *
     * @param client 根据业务延迟选择的实时或长耗时RestClient
     * @param path 相对于app.quant-service.base-url的接口路径
     * @param request 将被序列化为JSON的业务请求体
     * @param traceId Spring当前链路号
     * @param errorCode 超时、5xx或一般服务异常的业务错误码
     * @param errorMessage 一般服务异常的用户可见消息
     * @param rejectedCode HTTP 4xx明确拒绝时使用的业务错误码
     * @param rejectedMessage HTTP 4xx明确拒绝时的用户可见消息
     * @return FastAPI统一响应中的data对象
     */
    private Map<String, Object> post(RestClient client, String path, Map<String, Object> request, String traceId,
                                     int errorCode, String errorMessage, int rejectedCode, String rejectedMessage) {
        /*
         * 统一解析 FastAPI 的 {success, data, traceId, durationMs} 协议。
         * 4xx 通常表示业务明确拒绝（例如 QMT 拒单），映射为可展示的业务异常；
         * 超时、5xx 或结构错误则映射为服务不可用，订单层会据此决定 REJECTED 还是 UNKNOWN。
         */
        try {
            // 构造一次JSON POST请求。RestClient实例已在构造器中绑定baseUrl和超时策略。
            Map<?, ?> response = client.post().uri(path)
                    // 所有业务路由必须通过Spring/FastAPI共享Token认证。
                    .header("X-Internal-Token", internalToken)
                    // TraceId贯穿浏览器请求、Spring业务、FastAPI和QMT适配器日志。
                    .header("X-Trace-Id", traceId)
                    // RequestId标识本次具体HTTP尝试；同一TraceId下可包含多次不同内部请求。
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    // 声明当前Java/Python内部JSON协议版本，便于后续兼容升级。
                    .header("X-Schema-Version", "1")
                    // Map由Spring消息转换器序列化为application/json。
                    .body(request)
                    // retrieve执行请求，HTTP非2xx时抛出RestClientResponseException。
                    .retrieve()
                    // FastAPI响应先按Map读取，再执行显式结构校验。
                    .body(Map.class);

            // 只有非空且success严格为Boolean.TRUE的响应才可继续使用。
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(errorCode, errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
            }

            // data是业务层真正需要的对象；traceId和durationMs留在跨服务协议外壳中。
            Object data = response.get("data");
            // 当前所有QuantClient业务方法都约定data为JSON对象，而不是数组或基本类型。
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502601, "Python 服务返回数据结构错误", HttpStatus.BAD_GATEWAY);
            }

            // instanceof已经确认原始类型是Map，抑制泛型擦除导致的unchecked警告。
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            // 返回data本体，使上层Service只处理业务字段，不依赖FastAPI响应包装。
            return result;
        } catch (BusinessException ex) {
            // 本方法主动抛出的协议/服务异常已经包含正确业务码和HTTP状态，直接向上传递。
            throw ex;
        } catch (RestClientResponseException ex) {
            // FastAPI返回4xx表示请求参数、环境或QMT业务规则明确拒绝。
            if (ex.getStatusCode().is4xxClientError()) {
                // 对下单/撤单返回422，上层可以安全区分REJECTED与UNKNOWN。
                throw new BusinessException(rejectedCode, rejectedMessage, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // 5xx等服务端错误无法证明QMT没有执行，统一按服务不可用处理。
            throw new BusinessException(errorCode, errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception ex) {
            // 连接拒绝、读取超时、序列化错误及其它客户端异常统一转换为服务不可用。
            // 不拼接原始异常详情，避免把内部地址、Token相关信息暴露给前端。
            throw new BusinessException(errorCode, errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
