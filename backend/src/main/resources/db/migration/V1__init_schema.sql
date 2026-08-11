CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    role_code VARCHAR(32) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_no VARCHAR(64) NOT NULL,
    account_name VARCHAR(100) NOT NULL,
    broker VARCHAR(50) NOT NULL,
    environment VARCHAR(20) NOT NULL DEFAULT 'SIMULATION',
    currency VARCHAR(10) NOT NULL DEFAULT 'CNY',
    total_asset DECIMAL(20,4),
    cash DECIMAL(20,4),
    market_value DECIMAL(20,4),
    profit_loss DECIMAL(20,4),
    last_sync_time DATETIME,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uk_account_user_no (user_id, account_no),
    KEY idx_account_user_status (user_id, status),
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS position (
    id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    security_code VARCHAR(32) NOT NULL,
    security_name VARCHAR(100),
    quantity DECIMAL(20,4) NOT NULL DEFAULT 0,
    available_quantity DECIMAL(20,4) NOT NULL DEFAULT 0,
    cost_price DECIMAL(20,6),
    last_price DECIMAL(20,6),
    market_value DECIMAL(20,4),
    profit_loss DECIMAL(20,4),
    industry VARCHAR(100),
    region VARCHAR(100),
    UNIQUE KEY uk_position_account_security (account_id, security_code),
    CONSTRAINT fk_position_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sync_task (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT,
    task_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    progress INT,
    stage VARCHAR(200),
    result_json JSON,
    error_code VARCHAR(50),
    error_message VARCHAR(500),
    created_at DATETIME NOT NULL,
    started_at DATETIME,
    finished_at DATETIME,
    KEY idx_task_user_type_created (user_id, task_type, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
