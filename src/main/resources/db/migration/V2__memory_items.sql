CREATE TABLE memory_items (
    id UUID PRIMARY KEY,
    vector_id UUID NOT NULL UNIQUE,
    scope TEXT NOT NULL,
    project_key TEXT,
    memory_type TEXT NOT NULL,
    summary TEXT NOT NULL,
    text TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence DOUBLE PRECISION NOT NULL,
    status TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_ref TEXT,
    owner TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT memory_items_scope_check CHECK (scope IN ('global', 'project', 'episodic')),
    CONSTRAINT memory_items_type_check CHECK (memory_type IN ('rule', 'preference', 'correction', 'decision', 'anti_pattern')),
    CONSTRAINT memory_items_status_check CHECK (status IN ('active', 'pending_review', 'rejected', 'archived', 'stale')),
    CONSTRAINT memory_items_source_type_check CHECK (source_type IN ('manual', 'correction_signal', 'approval_signal', 'session_summary', 'promotion')),
    CONSTRAINT memory_items_confidence_check CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT memory_items_tags_array_check CHECK (jsonb_typeof(tags) = 'array'),
    CONSTRAINT memory_items_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT memory_items_project_scope_key_check CHECK (scope <> 'project' OR project_key IS NOT NULL)
);

CREATE INDEX memory_items_scope_status_idx
    ON memory_items (scope, status);

CREATE INDEX memory_items_project_key_status_idx
    ON memory_items (project_key, status);

CREATE INDEX memory_items_type_status_idx
    ON memory_items (memory_type, status);

CREATE INDEX memory_items_tags_gin_idx
    ON memory_items USING GIN (tags);
