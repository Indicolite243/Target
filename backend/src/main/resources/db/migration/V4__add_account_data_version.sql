ALTER TABLE account
    ADD COLUMN data_version BIGINT NOT NULL DEFAULT 0 AFTER last_sync_time;
