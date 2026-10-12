-- Work trails: bounded retrieval telemetry of what one agent request touched (edited / cited / supporting symbols,
-- the checks it ran with their real status). Not durable project memory: never served as a fact, pruned to the
-- newest rows per project. One row per (project, request), upserted when the Stop hook fires again.
CREATE TABLE work_trails (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    trail_key TEXT NOT NULL,
    client TEXT,
    code_fingerprint TEXT,
    request_text TEXT NOT NULL,
    edited JSONB NOT NULL DEFAULT '[]'::jsonb,
    cited JSONB NOT NULL DEFAULT '[]'::jsonb,
    supporting_reads JSONB NOT NULL DEFAULT '[]'::jsonb,
    checks JSONB NOT NULL DEFAULT '[]'::jsonb,
    fact_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT work_trails_project_trail_key UNIQUE (project_key, trail_key),
    CONSTRAINT work_trails_arrays CHECK (jsonb_typeof(edited) = 'array' AND jsonb_typeof(cited) = 'array'
        AND jsonb_typeof(supporting_reads) = 'array' AND jsonb_typeof(checks) = 'array'
        AND jsonb_typeof(fact_ids) = 'array')
);
CREATE INDEX work_trails_project_recent ON work_trails (project_key, updated_at DESC);
