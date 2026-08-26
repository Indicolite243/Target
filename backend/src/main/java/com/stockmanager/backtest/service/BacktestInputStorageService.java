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

/** Moves request-scoped uploads to a task-owned directory before asynchronous execution begins. */
@Service
public class BacktestInputStorageService {
    private final Path storageRoot;
    private final long maxStrategyBytes;
    private final long maxMarketFileBytes;
    private final long maxMarketTotalBytes;

    public BacktestInputStorageService(@Value("${app.backtest.storage-root:../runtime/backtests}") String storageRoot,
                                       @Value("${app.backtest.max-strategy-bytes:5242880}") long maxStrategyBytes,
                                       @Value("${app.backtest.max-market-file-bytes:20971520}") long maxMarketFileBytes,
                                       @Value("${app.backtest.max-market-total-bytes:52428800}") long maxMarketTotalBytes) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxStrategyBytes = maxStrategyBytes;
        this.maxMarketFileBytes = maxMarketFileBytes;
        this.maxMarketTotalBytes = maxMarketTotalBytes;
    }

    public BacktestInput stage(Long taskId, MultipartFile strategyFile, List<MultipartFile> marketFiles,
                               LocalDate startDate, LocalDate endDate, String engineType,
                               String benchmarkSymbol, boolean bearProtection) {
        validate(strategyFile, marketFiles, startDate, endDate);
        Path taskDir = storageRoot.resolve(String.valueOf(taskId)).normalize();
        if (!taskDir.startsWith(storageRoot)) throw new BusinessException(400702, "非法回测任务目录", HttpStatus.BAD_REQUEST);
        Path inputDir = taskDir.resolve("input");
        try {
            Files.createDirectories(inputDir);
            String strategyName = safeName(strategyFile.getOriginalFilename(), "strategy.py");
            Path strategyPath = inputDir.resolve("strategy.py");
            Files.copy(strategyFile.getInputStream(), strategyPath, StandardCopyOption.REPLACE_EXISTING);
            List<Path> marketPaths = new ArrayList<>();
            Set<String> marketNames = new HashSet<>();
            for (MultipartFile marketFile : marketFiles == null ? List.<MultipartFile>of() : marketFiles) {
                if (marketFile == null || marketFile.isEmpty()) continue;
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
    public boolean deleteTaskDirectory(Long taskId) {
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

    private String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String name = Path.of(value).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isBlank() ? fallback : name;
    }

    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public record BacktestInput(Long taskId, Path strategyPath, String strategyFilename, List<Path> marketPaths,
                                LocalDate startDate, LocalDate endDate, String engineType,
                                String benchmarkSymbol, boolean bearProtection, String runtimePath) {}
}
