CREATE TABLE agent_workspace_bindings (
    id UUID PRIMARY KEY,
    principal_key TEXT NOT NULL,
    client_id TEXT NOT NULL,
    project_key TEXT NOT NULL,
    repository_fingerprint TEXT NOT NULL,
    root_path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX agent_workspace_bindings_owner_idx
    ON agent_workspace_bindings (principal_key, project_key, expires_at);

CREATE TABLE research_contexts (
    id UUID PRIMARY KEY,
    workspace_binding_id UUID NOT NULL REFERENCES agent_workspace_bindings(id) ON DELETE RESTRICT,
    principal_key TEXT NOT NULL,
    client_id TEXT NOT NULL,
    project_key TEXT NOT NULL,
    context_epoch UUID NOT NULL,
    task_intent TEXT NOT NULL,
    query_hash CHAR(64) NOT NULL,
    trace_coverage TEXT NOT NULL DEFAULT 'server_observed',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (task_intent IN ('locate','explain','change','debug','recall')),
    CHECK (trace_coverage IN ('server_observed','agent_reported','partial')),
    CHECK (expires_at > created_at)
);

CREATE INDEX research_contexts_owner_idx
    ON research_contexts (principal_key, project_key, expires_at);

CREATE TABLE research_context_targets (
    context_id UUID NOT NULL REFERENCES research_contexts(id) ON DELETE CASCADE,
    target_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    symbol_ref TEXT,
    target_role TEXT NOT NULL,
    resolved_hash CHAR(64) NOT NULL,
    start_line INTEGER,
    end_line INTEGER,
    PRIMARY KEY (context_id, target_id),
    UNIQUE (context_id, relative_path),
    CHECK (target_id ~ '^t[1-9][0-9]*$'),
    CHECK (start_line IS NULL OR start_line > 0),
    CHECK (end_line IS NULL OR (start_line IS NOT NULL AND end_line >= start_line))
);

CREATE TABLE research_observations (
    id UUID PRIMARY KEY,
    context_id UUID NOT NULL REFERENCES research_contexts(id) ON DELETE CASCADE,
    target_id TEXT NOT NULL,
    operation_kind TEXT NOT NULL,
    resource_ref TEXT NOT NULL,
    raw_content_hash CHAR(64) NOT NULL,
    redacted_content_hash CHAR(64) NOT NULL,
    workspace_snapshot_id UUID NOT NULL,
    byte_count INTEGER NOT NULL,
    provenance TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (context_id, target_id)
        REFERENCES research_context_targets(context_id, target_id) ON DELETE CASCADE,
    CHECK (operation_kind IN ('source_read')),
    CHECK (provenance IN ('server_observed','agent_reported')),
    CHECK (byte_count >= 0)
);

CREATE INDEX research_observations_context_idx
    ON research_observations (context_id, observed_at);

CREATE TABLE learning_operations (
    request_id UUID PRIMARY KEY,
    learning_handle UUID NOT NULL UNIQUE,
    context_id UUID REFERENCES research_contexts(id) ON DELETE SET NULL,
    principal_key TEXT NOT NULL,
    project_key TEXT NOT NULL,
    payload_hash CHAR(64),
    operation_kind TEXT NOT NULL DEFAULT 'memory.learn',
    state TEXT NOT NULL DEFAULT 'ISSUED',
    result_json JSONB,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (operation_kind = 'memory.learn'),
    CHECK (state IN ('ISSUED','PROCESSING','QUEUED','COMPLETED','RETRYABLE_ERROR')),
    CHECK ((payload_hash IS NULL AND state = 'ISSUED') OR payload_hash IS NOT NULL),
    CHECK (expires_at > created_at)
);

CREATE INDEX learning_operations_owner_idx
    ON learning_operations (principal_key, project_key, expires_at);

CREATE TABLE memory_learning_profiles (
    memory_id UUID PRIMARY KEY REFERENCES memory_items(id) ON DELETE CASCADE,
    project_key TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    discovery_kind TEXT NOT NULL,
    origin TEXT NOT NULL,
    verification_level TEXT NOT NULL,
    usefulness_state TEXT NOT NULL,
    learning_revision BIGINT NOT NULL DEFAULT 1,
    canonical_hash CHAR(64) NOT NULL,
    created_from_context_id UUID,
    producer_runtime TEXT NOT NULL,
    producer_model TEXT,
    producer_contract_version TEXT NOT NULL,
    capture_coverage TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_key, canonical_hash),
    CHECK (verification_level IN ('SUPPORTED','INFERRED','UNVERIFIED')),
    CHECK (usefulness_state IN ('ACTIVE','STALE','ARCHIVED')),
    CHECK (learning_revision > 0),
    CHECK (capture_coverage IN ('server_observed','agent_reported','partial'))
);

CREATE TABLE memory_learning_evidence (
    id UUID PRIMARY KEY,
    memory_id UUID NOT NULL REFERENCES memory_learning_profiles(memory_id) ON DELETE CASCADE,
    claim_hash CHAR(64) NOT NULL,
    evidence_kind TEXT NOT NULL,
    canonical_ref TEXT NOT NULL,
    observed_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    workspace_snapshot_id UUID NOT NULL,
    evidence_origin TEXT NOT NULL,
    relation_to_claim TEXT NOT NULL,
    UNIQUE (memory_id, canonical_ref, observed_hash),
    CHECK (evidence_kind IN ('source')),
    CHECK (evidence_origin IN ('server_observed','agent_reported')),
    CHECK (relation_to_claim IN ('supports','limits'))
);

CREATE TABLE memory_navigation_anchors (
    id UUID PRIMARY KEY,
    memory_id UUID NOT NULL REFERENCES memory_learning_profiles(memory_id) ON DELETE CASCADE,
    locator_index INTEGER NOT NULL,
    canonical_ref TEXT NOT NULL,
    anchor_role TEXT NOT NULL,
    priority INTEGER NOT NULL,
    symbol_key TEXT,
    resolution_state TEXT NOT NULL,
    UNIQUE (memory_id, locator_index),
    CHECK (locator_index >= 0 AND locator_index < 8),
    CHECK (priority > 0 AND priority <= 8),
    CHECK (resolution_state IN ('RESOLVED','AMBIGUOUS','MISSING'))
);
