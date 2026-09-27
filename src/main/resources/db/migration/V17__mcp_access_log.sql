CREATE TABLE IF NOT EXISTS mcp_access_log (
    id UUID PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    project_key TEXT,
    client_id TEXT,
    api_key_prefix TEXT,
    tool_name TEXT NOT NULL,
    query_hash TEXT,
    result_count INTEGER,
    latency_ms INTEGER,
    decision TEXT NOT NULL,
    error_class TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT mcp_access_log_decision_check
        CHECK (decision IN ('success', 'denied_auth', 'denied_scope', 'error'))
);

CREATE INDEX mcp_access_log_project_timestamp_idx
    ON mcp_access_log (project_key, timestamp DESC);

CREATE INDEX mcp_access_log_decision_timestamp_idx
    ON mcp_access_log (decision, timestamp DESC);
