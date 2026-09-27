ALTER TABLE memory_items DROP CONSTRAINT IF EXISTS memory_items_source_type_check;

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_source_type_check CHECK (source_type IN (
        'manual',
        'correction_signal',
        'approval_signal',
        'session_summary',
        'promotion',
        'scanned',
        'auto_curated',
        'mcp_external'
    ));

CREATE TABLE IF NOT EXISTS knowledge_ingest_jobs (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    client_id TEXT NOT NULL,
    document_url TEXT NOT NULL,
    source_type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    chunks_indexed INTEGER NOT NULL DEFAULT 0,
    run_id UUID,
    error_class TEXT,
    error_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT knowledge_ingest_jobs_status_check
        CHECK (status IN ('queued', 'running', 'completed', 'failed'))
);

CREATE INDEX knowledge_ingest_jobs_project_status_idx
    ON knowledge_ingest_jobs (project_key, status, submitted_at DESC);

CREATE INDEX knowledge_ingest_jobs_pending_idx
    ON knowledge_ingest_jobs (status, submitted_at)
    WHERE status IN ('queued', 'running');
