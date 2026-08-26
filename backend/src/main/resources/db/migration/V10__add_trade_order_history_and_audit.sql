CREATE TABLE IF NOT EXISTS trade_order_status_history (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    previous_status VARCHAR(30),
    current_status VARCHAR(30) NOT NULL,
    filled_quantity DECIMAL(20,4) NOT NULL DEFAULT 0,
    average_filled_price DECIMAL(20,6),
    source VARCHAR(32) NOT NULL,
    broker_message VARCHAR(500),
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_order_status_history_order_time (order_id, observed_at DESC),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES trade_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trade_order_audit (
    id BIGINT PRIMARY KEY,
    order_id BIGINT,
    user_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(96),
    source VARCHAR(32) NOT NULL,
    detail_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_order_audit_idempotency (order_id, action, idempotency_key),
    KEY idx_order_audit_order_created (order_id, created_at DESC),
    KEY idx_order_audit_user_created (user_id, created_at DESC),
    CONSTRAINT fk_order_audit_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_order_audit_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
