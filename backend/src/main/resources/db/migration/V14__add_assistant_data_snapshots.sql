CREATE TABLE ai_data_snapshot (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    account_id BIGINT NULL,
    snapshot_json JSON NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    source_version VARCHAR(128) NULL,
    KEY idx_ai_snapshot_conversation_time (conversation_id, captured_at),
    CONSTRAINT fk_ai_snapshot_conversation FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_snapshot_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_conversation
    ADD COLUMN active_snapshot_id CHAR(36) NULL AFTER account_id,
    ADD KEY idx_ai_conversation_active_snapshot (active_snapshot_id);

ALTER TABLE ai_message
    ADD COLUMN snapshot_id CHAR(36) NULL AFTER request_id,
    ADD KEY idx_ai_message_snapshot (snapshot_id),
    ADD CONSTRAINT fk_ai_message_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES ai_data_snapshot(id) ON DELETE SET NULL;
