CREATE TABLE memory_vector_projection_state (
    memory_id UUID PRIMARY KEY REFERENCES memory_items(id) ON DELETE CASCADE,
    applied_revision BIGINT NOT NULL CHECK (applied_revision > 0),
    vector_id UUID NOT NULL,
    retrieval_text_hash CHAR(64) NOT NULL,
    projection_hash CHAR(64) NOT NULL,
    embedding_fingerprint CHAR(64) NOT NULL,
    applied_operation VARCHAR(16) NOT NULL CHECK (applied_operation IN ('UPSERT', 'DELETE')),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memory_vector_projection_work (
    memory_id UUID PRIMARY KEY REFERENCES memory_items(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    previous_vector_id UUID,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error VARCHAR(1000),
    dead_lettered_at TIMESTAMPTZ
);

CREATE INDEX memory_vector_projection_work_available_idx
    ON memory_vector_projection_work (available_at, requested_at, memory_id)
    WHERE dead_lettered_at IS NULL;
