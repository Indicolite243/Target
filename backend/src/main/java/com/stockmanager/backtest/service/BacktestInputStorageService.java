package com.stockmanager.backtest.service;

import com.stockmanager.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 回测输入文件的安全暂存边界。
 *
 * <pre>
 * Multipart 请求 --校验类型/大小/日期--> runtime/backtests/{taskId}/input
 *                                              |
 *                                              +--> Worker 只消费本地 Path
 *                                              +--> 任务清理时整目录删除
 * </pre>
 *
 * 异步任务不能依赖请求结束后仍然存在的 MultipartFile，因此必须在返回 202
 * 之前把文件复制到任务专属目录；目录名只由服务端 taskId 生成，避免路径穿越。
 */
@Service
public class BacktestInputStorageService {
    private final Path storageRoot;
    private final long maxStrategyBytes;
    private final long maxMarketFileBytes;
    private final long maxMarketTotalBytes;

    /** 读取并规范化存储根目录以及策略、行情文件大小上限。 */
    public BacktestInputStorageService(@Value("${app.backtest.storage-root:../runtime/backtests}") String storageRoot,
                                       @Value("${app.backtest.max-strategy-bytes:5242880}") long maxStrategyBytes,
                                       @Value("${app.backtest.max-market-file-bytes:20971520}") long maxMarketFileBytes,
                                       @Value("${app.backtest.max-market-total-bytes:52428800}") long maxMarketTotalBytes) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxStrategyBytes = maxStrategyBytes;
        this.maxMarketFileBytes = maxMarketFileBytes;
        this.maxMarketTotalBytes = maxMarketTotalBytes;
    }

    /**
     * 校验并暂存任务输入，只有全部文件复制成功后才返回可供 Worker 消费的路径集合。
     */
    public BacktestInput stage(Long taskId, MultipartFile strategyFile, List<MultipartFile> marketFiles,
                               LocalDate startDate, LocalDate endDate, String engineType,
                               String benchmarkSymbol, boolean bearProtection) {
        // 先完整校验，再创建目录和复制文件，避免半成品任务进入队列。
        validate(strategyFile, marketFiles, startDate, endDate);
        Path taskDir = storageRoot.resolve(String.valueOf(taskId)).normalize();
        if (!taskDir.startsWith(storageRoot)) throw new BusinessException(400702, "非法回测任务目录", HttpStatus.BAD_REQUEST);
        Path inputDir = taskDir.resolve("input");
        try {
            Files.createDirectories(inputDir);
            // 策略文件统一落为 strategy.py；原始名称只用于结果展示，不能决定执行路径。
            String strategyName = safeName(strategyFile.getOriginalFilename(), "strategy.py");
            Path strategyPath = inputDir.resolve("strategy.py");
            Files.copy(strategyFile.getInputStream(), strategyPath, StandardCopyOption.REPLACE_EXISTING);
            List<Path> marketPaths = new ArrayList<>();
            Set<String> marketNames = new HashSet<>();
            for (MultipartFile marketFile : marketFiles == null ? List.<MultipartFile>of() : marketFiles) {
                if (marketFile == null || marketFile.isEmpty()) continue;
                // 同名行情文件会覆盖输入，直接拒绝比静默覆盖更容易审计。
                String marketName = safeName(marketFile.getOriginalFilename(), "market.xlsx");
                if (!marketNames.add(marketName.toLowerCase())) {
                    throw new BusinessException(400701, "行情文件名重复: " + marketName, HttpStatus.BAD_REQUEST);
                }
                Path target = inputDir.resolve(marketName).normalize();
                if (!target.startsWith(inputDir)) {
                    throw new BusinessException(400702, "非法行情文件路径", HttpStatus.BAD_REQUEST);
                }
                Files.copy(marketFile.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                marketPaths.add(target);
            }
            return new BacktestInput(taskId, strategyPath, strategyName, List.copyOf(marketPaths), startDate, endDate,
                    blank(engineType, "auto"), blank(benchmarkSymbol, ""), bearProtection,
                    storageRoot.relativize(taskDir).toString().replace('\\', '/'));
        } catch (IOException ex) {
            throw new BusinessException(500702, "回测上传文件暂存失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Removes one task-owned runtime directory only after the caller has validated retention eligibility. */
    /**
     * 删除单个已结束任务的运行目录；路径必须严格位于配置的 storageRoot 下。
     */
    public boolean deleteTaskDirectory(Long taskId) {
        // 只允许删除 storageRoot 下的单个 taskId 目录；调用方应先判断保留策略。
        Path taskDir = storageRoot.resolve(String.valueOf(taskId)).normalize();
        if (!taskDir.startsWith(storageRoot) || taskDir.equals(storageRoot)) {
            throw new IllegalArgumentException("非法回测清理目录");
        }
        if (!Files.exists(taskDir)) return false;
        try (Stream<Path> paths = Files.walk(taskDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("回测运行目录清理失败", ex);
        }
    }

    /** 在产生文件副作用前完成日期、文件类型、单文件大小和总大小校验。 */
    private void validate(MultipartFile strategyFile, List<MultipartFile> marketFiles,
                          LocalDate startDate, LocalDate endDate) {
        if (strategyFile == null || strategyFile.isEmpty()) {
            throw new BusinessException(400701, "请选择 Python 策略文件", HttpStatus.BAD_REQUEST);
        }
        String strategyName = safeName(strategyFile.getOriginalFilename(), "");
        if (!strategyName.toLowerCase().endsWith(".py")) {
            throw new BusinessException(400701, "策略文件必须是 .py 文件", HttpStatus.BAD_REQUEST);
        }
        if (strategyFile.getSize() > maxStrategyBytes) {
            throw new BusinessException(400701, "策略文件不能超过5MB", HttpStatus.BAD_REQUEST);
        }
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new BusinessException(400701, "回测开始日期必须早于结束日期", HttpStatus.BAD_REQUEST);
        }
        long total = 0;
        for (MultipartFile marketFile : marketFiles == null ? List.<MultipartFile>of() : marketFiles) {
            if (marketFile == null || marketFile.isEmpty()) continue;
            String filename = safeName(marketFile.getOriginalFilename(), "");
            if (!filename.toLowerCase().endsWith(".xlsx")) {
                throw new BusinessException(400701, "行情文件必须是 .xlsx 文件", HttpStatus.BAD_REQUEST);
            }
            if (marketFile.getSize() > maxMarketFileBytes) {
                throw new BusinessException(400701, "单个行情文件不能超过20MB", HttpStatus.BAD_REQUEST);
            }
            total += marketFile.getSize();
        }
        if (total > maxMarketTotalBytes) {
            throw new BusinessException(400701, "行情文件总大小不能超过50MB", HttpStatus.BAD_REQUEST);
        }
    }

    /** 去除客户端路径，仅保留安全文件名；空名称使用后备值。 */
    private String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String name = Path.of(value).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isBlank() ? fallback : name;
    }

    /** 空白配置值使用默认值。 */
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    /** Worker 执行回测所需的不可变输入描述。 */
    public record BacktestInput(Long taskId, Path strategyPath, String strategyFilename, List<Path> marketPaths,
                                LocalDate startDate, LocalDate endDate, String engineType,
                                String benchmarkSymbol, boolean bearProtection, String runtimePath) {}
}
