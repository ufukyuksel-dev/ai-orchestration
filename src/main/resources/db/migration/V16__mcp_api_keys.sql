CREATE TABLE IF NOT EXISTS mcp_api_keys (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    client_id TEXT NOT NULL,
    api_key_hash TEXT NOT NULL,
    key_prefix TEXT NOT NULL,
    scopes TEXT[] NOT NULL DEFAULT ARRAY['memory.read', 'knowledge.read', 'transcript.write'],
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT mcp_api_keys_hash_unique UNIQUE (api_key_hash)
);

CREATE INDEX mcp_api_keys_project_active_idx
    ON mcp_api_keys (project_key)
    WHERE revoked_at IS NULL;
