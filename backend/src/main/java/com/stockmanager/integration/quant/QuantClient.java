package com.stockmanager.integration.quant;

import com.stockmanager.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QuantClient {
    private final RestClient restClient;
    private final String internalToken;

    public QuantClient(RestClient.Builder builder,
                       @Value("${app.quant-service.base-url}") String baseUrl,
                       @Value("${app.quant-service.internal-token}") String internalToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(360_000);
        this.restClient = builder.requestFactory(requestFactory).baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    public Map<String, Object> syncAccount(Map<String, Object> request, String traceId) {
        return post("/accounts/sync", request, traceId, 503601, "账户同步服务暂时不可用");
    }

    public Map<String, Object> health(String traceId) {
        try {
            Map<?, ?> response = restClient.get().uri("/health")
                    .header("X-Trace-Id", traceId)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503602, "QMT连接状态暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502602, "Python健康检查返回结构错误", HttpStatus.BAD_GATEWAY);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(503602, "QMT连接状态暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public Map<String, Object> quotes(Map<String, Object> request, String traceId) {
        return post("/market/quotes", request, traceId, 503201, "行情服务暂时不可用");
    }

    public Map<String, Object> submitOrder(Map<String, Object> request, String traceId) {
        return post("/orders/submit", request, traceId, 503301, "交易服务暂时不可用");
    }

    public Map<String, Object> calculateRisk(Map<String, Object> request, String traceId) {
        return post("/risk/calculate", request, traceId, 503401, "风险计算服务暂时不可用");
    }

    public Map<String, Object> portfolioHistory(Map<String, Object> request, String traceId) {
        return post("/analysis/portfolio-history", request, traceId, 503402, "QMT历史行情服务暂时不可用");
    }

    public Map<String, Object> cancelOrder(Map<String, Object> request, String traceId) {
        return post("/orders/cancel", request, traceId, 503301, "撤单服务暂时不可用");
    }

    public Map<String, Object> queryOrders(String traceId) {
        try {
            Map<?, ?> response = restClient.get().uri("/orders/query")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", traceId)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .retrieve().body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503302, "委托查询服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502602, "Python 委托查询返回结构错误", HttpStatus.BAD_GATEWAY);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(503302, "委托查询服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public Map<String, Object> runBacktest(MultipartFile strategyFile, List<MultipartFile> marketFiles,
                                           String startDate, String endDate, String engineType,
                                           String benchmarkSymbol, boolean bearProtection, String traceId) {
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("file", namedResource(strategyFile)).filename(strategyFile.getOriginalFilename());
            body.part("start_date", startDate);
            body.part("end_date", endDate);
            body.part("engine_type", engineType == null ? "auto" : engineType);
            body.part("benchmark_symbol", benchmarkSymbol == null ? "" : benchmarkSymbol);
            body.part("enable_bear_protection", String.valueOf(bearProtection));
            for (MultipartFile marketFile : marketFiles) {
                body.part("market_files", namedResource(marketFile)).filename(marketFile.getOriginalFilename());
            }
            Map<?, ?> response = restClient.post().uri("/backtests/run")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", traceId)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(503701, "回测服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
            }
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502701, "Python 回测服务返回结构错误", HttpStatus.BAD_GATEWAY);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(503701, "回测服务暂时不可用: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ByteArrayResource namedResource(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        return new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return filename; }
        };
    }

    private Map<String, Object> post(String path, Map<String, Object> request, String traceId,
                                     int errorCode, String errorMessage) {
        try {
            Map<?, ?> response = restClient.post().uri(path)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", traceId)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-Schema-Version", "1")
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new BusinessException(errorCode, errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
            }
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                throw new BusinessException(502601, "Python 服务返回数据结构错误", HttpStatus.BAD_GATEWAY);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) dataMap;
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(errorCode, errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
