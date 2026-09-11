CREATE TABLE ai_knowledge_document (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    extracted_chars INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500) NULL,
    embedding_model VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ai_knowledge_user_sha (user_id, sha256),
    KEY idx_ai_knowledge_user_updated (user_id, updated_at),
    CONSTRAINT fk_ai_knowledge_document_user FOREIGN KEY (user_id)
        REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_knowledge_chunk (
    id CHAR(36) PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    embedding LONGTEXT NOT NULL,
    embedding_dimension SMALLINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ai_knowledge_chunk_order (document_id, chunk_index),
    KEY idx_ai_knowledge_chunk_user_document (user_id, document_id),
    CONSTRAINT fk_ai_knowledge_chunk_document FOREIGN KEY (document_id)
        REFERENCES ai_knowledge_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_knowledge_chunk_user FOREIGN KEY (user_id)
        REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_message
    ADD COLUMN knowledge_snapshot_id CHAR(36) NULL AFTER attribution_snapshot_id,
    ADD KEY idx_ai_message_knowledge_snapshot (knowledge_snapshot_id),
    ADD CONSTRAINT fk_ai_message_knowledge_snapshot FOREIGN KEY (knowledge_snapshot_id)
        REFERENCES ai_data_snapshot(id) ON DELETE SET NULL;
