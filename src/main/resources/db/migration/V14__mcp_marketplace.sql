CREATE TABLE IF NOT EXISTS mcp_manifests (
    id TEXT NOT NULL,
    version TEXT NOT NULL,
    name TEXT NOT NULL,
    manifest_digest TEXT NOT NULL,
    signature TEXT NOT NULL,
    public_key TEXT NOT NULL,
    status TEXT NOT NULL,
    permission_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    network_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    filesystem_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    submitted_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (id, version),
    CONSTRAINT mcp_manifests_status_check CHECK (status IN (
        'submitted', 'security_reviewed', 'approved', 'revoked'
    )),
    CONSTRAINT mcp_manifests_permission_scopes_array_check CHECK (jsonb_typeof(permission_scopes) = 'array'),
    CONSTRAINT mcp_manifests_network_allowlist_array_check CHECK (jsonb_typeof(network_allowlist) = 'array'),
    CONSTRAINT mcp_manifests_filesystem_allowlist_array_check CHECK (jsonb_typeof(filesystem_allowlist) = 'array'),
    CONSTRAINT mcp_manifests_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS mcp_manifests_status_idx ON mcp_manifests (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS mcp_approval_events (
    id UUID PRIMARY KEY,
    manifest_id TEXT NOT NULL,
    manifest_version TEXT NOT NULL,
    stage TEXT NOT NULL,
    decision TEXT NOT NULL,
    actor TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mcp_approval_events_stage_check CHECK (stage IN (
        'submit', 'security_review', 'admin_approve', 'revoke', 'permission_check'
    )),
    CONSTRAINT mcp_approval_events_decision_check CHECK (decision IN (
        'submitted', 'approved', 'denied', 'revoked', 'allowed', 'blocked'
    )),
    CONSTRAINT mcp_approval_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT mcp_approval_events_manifest_fk FOREIGN KEY (manifest_id, manifest_version)
        REFERENCES mcp_manifests(id, version) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mcp_approval_events_manifest_idx
    ON mcp_approval_events (manifest_id, manifest_version, created_at DESC);
