CREATE TABLE IF NOT EXISTS agent_tasks (
    id UUID PRIMARY KEY,
    parent_task_id UUID REFERENCES agent_tasks(id) ON DELETE SET NULL,
    task_type TEXT NOT NULL,
    assigned_role TEXT NOT NULL,
    status TEXT NOT NULL,
    title TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_tasks_type_check CHECK (task_type IN (
        'analyze_code', 'write_code', 'scan_codebase', 'general_question', 'ambiguous'
    )),
    CONSTRAINT agent_tasks_role_check CHECK (assigned_role IN (
        'orchestrator', 'analyst', 'software', 'scanner'
    )),
    CONSTRAINT agent_tasks_status_check CHECK (status IN (
        'pending', 'in_progress', 'awaiting_approval', 'completed', 'rejected', 'failed'
    )),
    CONSTRAINT agent_tasks_payload_object_check CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX IF NOT EXISTS agent_tasks_status_idx ON agent_tasks (status, created_at DESC);
CREATE INDEX IF NOT EXISTS agent_tasks_role_status_idx ON agent_tasks (assigned_role, status);

CREATE TABLE IF NOT EXISTS agent_artifacts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    content_hash TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_artifacts_kind_check CHECK (kind IN (
        'plan', 'analysis', 'diff', 'test_result', 'scan_result', 'review'
    )),
    CONSTRAINT agent_artifacts_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS agent_artifacts_task_idx ON agent_artifacts (task_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_events_type_check CHECK (event_type IN (
        'created', 'status_changed', 'artifact_added', 'guardrail_checked'
    )),
    CONSTRAINT agent_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS agent_events_task_idx ON agent_events (task_id, created_at DESC);
