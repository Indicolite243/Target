ALTER TABLE ai_data_snapshot
    ADD COLUMN snapshot_type VARCHAR(32) NOT NULL DEFAULT 'PORTFOLIO' AFTER account_id,
    ADD KEY idx_ai_snapshot_conversation_type (conversation_id, snapshot_type, captured_at);

ALTER TABLE ai_conversation
    ADD COLUMN active_attribution_snapshot_id CHAR(36) NULL AFTER active_snapshot_id,
    ADD KEY idx_ai_conversation_attribution_snapshot (active_attribution_snapshot_id);

ALTER TABLE ai_message
    ADD COLUMN attribution_snapshot_id CHAR(36) NULL AFTER snapshot_id,
    ADD KEY idx_ai_message_attribution_snapshot (attribution_snapshot_id),
    ADD CONSTRAINT fk_ai_message_attribution_snapshot FOREIGN KEY (attribution_snapshot_id)
        REFERENCES ai_data_snapshot(id) ON DELETE SET NULL;
