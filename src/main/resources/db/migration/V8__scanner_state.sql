CREATE TABLE IF NOT EXISTS scanner_file_state (
    file_path TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    extracted_memory_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_scanned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scanner_file_state_memory_ids_array_check CHECK (jsonb_typeof(extracted_memory_ids) = 'array')
);

CREATE INDEX IF NOT EXISTS scanner_file_state_last_scanned_idx
    ON scanner_file_state (last_scanned_at DESC);
