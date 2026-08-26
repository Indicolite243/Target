-- User-managed order history cleanup.  This is a soft delete: broker/QMT
-- orders and their audit/status history remain intact.
ALTER TABLE trade_order
    ADD COLUMN deleted_at DATETIME NULL;

CREATE INDEX idx_trade_order_user_created_deleted
    ON trade_order (user_id, created_at DESC, deleted_at);
