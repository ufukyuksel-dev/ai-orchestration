ALTER TABLE scanner_file_state
    ADD COLUMN IF NOT EXISTS project_key TEXT NOT NULL DEFAULT 'AI_ORCHESTRATION';

ALTER TABLE scanner_file_state
    DROP CONSTRAINT IF EXISTS scanner_file_state_pkey;

ALTER TABLE scanner_file_state
    ADD CONSTRAINT scanner_file_state_pkey PRIMARY KEY (project_key, file_path);

ALTER TABLE scanner_file_state
    ALTER COLUMN project_key DROP DEFAULT;

CREATE INDEX IF NOT EXISTS scanner_file_state_project_last_scanned_idx
    ON scanner_file_state (project_key, last_scanned_at DESC);
