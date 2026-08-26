CREATE TABLE IF NOT EXISTS attribution_result (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    dimension VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    range_start DATE,
    range_end DATE,
    data_version VARCHAR(128),
    result_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_attribution_result_task (task_id),
    KEY idx_attribution_account_created (account_id, created_at DESC),
    CONSTRAINT fk_attribution_result_task FOREIGN KEY (task_id) REFERENCES quant_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
