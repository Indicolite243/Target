package com.stockmanager.backtest.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestInputStorageServiceTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesUploadsOnlyInsideTheTaskDirectory() throws Exception {
        BacktestInputStorageService service = new BacktestInputStorageService(temporaryDirectory.toString(),
                5 * 1024 * 1024, 20 * 1024 * 1024, 50 * 1024 * 1024);
        MockMultipartFile strategy = new MockMultipartFile("file", "../../unsafe.py", "text/x-python",
                "print('safe')".getBytes());
        MockMultipartFile market = new MockMultipartFile("market_files", "../market.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3});

        BacktestInputStorageService.BacktestInput input = service.stage(42L, strategy, List.of(market),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), "auto", "510300.SH", false);

        Path expectedRoot = temporaryDirectory.resolve("42").resolve("input").toAbsolutePath().normalize();
        assertThat(input.strategyPath()).startsWith(expectedRoot);
        assertThat(input.marketPaths()).allMatch(path -> path.startsWith(expectedRoot));
        assertThat(input.marketPaths()).extracting(path -> path.getFileName().toString()).containsExactly("market.xlsx");
        assertThat(Files.readString(input.strategyPath())).isEqualTo("print('safe')");
    }

    @Test
    void removesOnlyTheRequestedTaskOwnedDirectory() throws Exception {
        BacktestInputStorageService service = new BacktestInputStorageService(temporaryDirectory.toString(),
                5 * 1024 * 1024, 20 * 1024 * 1024, 50 * 1024 * 1024);
        Path taskFile = temporaryDirectory.resolve("42").resolve("input").resolve("strategy.py");
        Path retainedFile = temporaryDirectory.resolve("43").resolve("input").resolve("strategy.py");
        Files.createDirectories(taskFile.getParent());
        Files.createDirectories(retainedFile.getParent());
        Files.writeString(taskFile, "print('cleanup')");
        Files.writeString(retainedFile, "print('keep')");

        assertThat(service.deleteTaskDirectory(42L)).isTrue();
        assertThat(temporaryDirectory.resolve("42")).doesNotExist();
        assertThat(retainedFile).exists();
        assertThat(service.deleteTaskDirectory(42L)).isFalse();
    }

    @Test
    void rejectsMarketFilesWhoseSanitizedNamesCollide() {
        BacktestInputStorageService service = new BacktestInputStorageService(temporaryDirectory.toString(),
                5 * 1024 * 1024, 20 * 1024 * 1024, 50 * 1024 * 1024);
        MockMultipartFile strategy = new MockMultipartFile("file", "strategy.py", "text/x-python",
                "print('safe')".getBytes());
        MockMultipartFile first = new MockMultipartFile("market_files", "510300.SH.xlsx", "application/octet-stream",
                new byte[] {1});
        MockMultipartFile second = new MockMultipartFile("market_files", "510300.SH.xlsx", "application/octet-stream",
                new byte[] {2});

        assertThatThrownBy(() -> service.stage(44L, strategy, List.of(first, second),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), "auto", "", false))
                .hasMessageContaining("行情文件名重复");
    }
}
