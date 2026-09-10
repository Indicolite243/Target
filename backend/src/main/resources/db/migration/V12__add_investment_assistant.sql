CREATE TABLE ai_conversation (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NULL,
    title VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_ai_conversation_user_updated (user_id, updated_at),
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_ai_conversation_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_ai_message_conversation_created (conversation_id, created_at, id),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
