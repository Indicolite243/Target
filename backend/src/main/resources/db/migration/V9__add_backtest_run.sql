CREATE TABLE IF NOT EXISTS backtest_run (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    strategy_filename VARCHAR(255) NOT NULL,
    engine_type VARCHAR(32) NOT NULL,
    benchmark_symbol VARCHAR(32),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    runtime_path VARCHAR(512) NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_backtest_run_task (task_id),
    KEY idx_backtest_run_user_created (user_id, created_at DESC),
    CONSTRAINT fk_backtest_run_task FOREIGN KEY (task_id) REFERENCES quant_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
