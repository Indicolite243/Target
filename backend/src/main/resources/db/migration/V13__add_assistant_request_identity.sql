ALTER TABLE ai_message
    ADD COLUMN request_id CHAR(36) NULL AFTER conversation_id,
    ADD KEY idx_ai_message_request (conversation_id, request_id, status);

