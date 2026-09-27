CREATE TABLE IF NOT EXISTS transcript_capture_queue (
    id BIGSERIAL PRIMARY KEY,
    session_id TEXT NOT NULL,
    content TEXT NOT NULL,
    source TEXT NOT NULL,
    project_key TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed BOOLEAN NOT NULL DEFAULT false,
    processed_at TIMESTAMPTZ,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX transcript_queue_pending_idx
    ON transcript_capture_queue (project_key, submitted_at)
    WHERE processed = false;

CREATE INDEX transcript_queue_session_idx
    ON transcript_capture_queue (session_id, submitted_at DESC);
