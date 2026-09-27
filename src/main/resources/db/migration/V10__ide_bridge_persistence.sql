CREATE TABLE IF NOT EXISTS ide_bridge_requests (
    id UUID PRIMARY KEY,
    owner_user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    model TEXT,
    status TEXT NOT NULL,
    payload_encrypted BYTEA NOT NULL,
    response_encrypted BYTEA,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ide_bridge_requests_status_check CHECK (status IN (
        'pending', 'in_flight', 'completed', 'failed', 'expired'
    ))
);

CREATE INDEX IF NOT EXISTS ide_bridge_requests_owner_status_idx
    ON ide_bridge_requests (tenant_id, owner_user_id, status, created_at);

CREATE INDEX IF NOT EXISTS ide_bridge_requests_expires_idx
    ON ide_bridge_requests (expires_at);

CREATE TABLE IF NOT EXISTS ide_bridge_events (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES ide_bridge_requests(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    model TEXT,
    status TEXT NOT NULL,
    latency_ms BIGINT,
    error_code TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ide_bridge_events_type_check CHECK (event_type IN (
        'created', 'polled', 'completed', 'failed', 'expired', 'purged', 'owner_mismatch'
    )),
    CONSTRAINT ide_bridge_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS ide_bridge_events_request_idx
    ON ide_bridge_events (request_id, created_at DESC);
