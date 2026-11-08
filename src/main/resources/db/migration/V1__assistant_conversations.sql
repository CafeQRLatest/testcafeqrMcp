-- Built-in "Ask CafeQR" chat history and AI usage (used for quotas and cost tracking).
CREATE TABLE ai_conversation (
    id          UUID PRIMARY KEY,
    client_id   UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    title       VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_conversation_owner ON ai_conversation (client_id, user_id, updated_at);

CREATE TABLE ai_message (
    id                 UUID PRIMARY KEY,
    conversation_id    UUID        NOT NULL REFERENCES ai_conversation (id) ON DELETE CASCADE,
    client_id          UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    role               VARCHAR(16) NOT NULL,
    content            TEXT        NOT NULL,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    model              VARCHAR(100),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_message_conversation ON ai_message (conversation_id, created_at);
CREATE INDEX idx_ai_message_quota ON ai_message (client_id, role, created_at);
