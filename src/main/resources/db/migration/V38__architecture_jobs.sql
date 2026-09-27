CREATE TABLE IF NOT EXISTS architecture_jobs (
    id           UUID PRIMARY KEY,
    project_key  TEXT NOT NULL,
    cache_key    TEXT NOT NULL,
    status       TEXT NOT NULL,
    phase        TEXT,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    result       JSONB,
    error        TEXT,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- B2: unique partial index — at most one reusable (queued/running/completed) job per cache_key.
-- ON CONFLICT DO NOTHING in queueJob uses this index to make concurrent duplicate inserts atomic.
CREATE UNIQUE INDEX IF NOT EXISTS ux_architecture_jobs_reusable
    ON architecture_jobs (cache_key)
    WHERE status IN ('queued', 'running', 'completed');
