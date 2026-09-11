ALTER TABLE backtest_run
    ADD COLUMN strategy_source MEDIUMTEXT NULL AFTER runtime_path;

ALTER TABLE ai_conversation
    ADD COLUMN active_backtest_task_id BIGINT NULL AFTER active_snapshot_id,
    ADD KEY idx_ai_conversation_backtest (active_backtest_task_id),
    ADD CONSTRAINT fk_ai_conversation_backtest FOREIGN KEY (active_backtest_task_id)
        REFERENCES quant_task(id) ON DELETE SET NULL;

ALTER TABLE ai_message
    ADD COLUMN backtest_task_id BIGINT NULL AFTER snapshot_id,
    ADD KEY idx_ai_message_backtest (backtest_task_id),
    ADD CONSTRAINT fk_ai_message_backtest FOREIGN KEY (backtest_task_id)
        REFERENCES quant_task(id) ON DELETE SET NULL;
