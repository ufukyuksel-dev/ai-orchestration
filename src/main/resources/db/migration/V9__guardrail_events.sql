CREATE TABLE IF NOT EXISTS guardrail_events (
    id UUID PRIMARY KEY,
    task_id UUID REFERENCES agent_tasks(id) ON DELETE SET NULL,
    guardrail_type TEXT NOT NULL,
    decision TEXT NOT NULL,
    reason TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT guardrail_events_type_check CHECK (guardrail_type IN (
        'plan_approval', 'sandbox', 'command_policy', 'test_gate', 'secret_scan', 'artifact_path'
    )),
    CONSTRAINT guardrail_events_decision_check CHECK (decision IN ('allowed', 'blocked')),
    CONSTRAINT guardrail_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS guardrail_events_task_idx ON guardrail_events (task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS guardrail_events_type_decision_idx ON guardrail_events (guardrail_type, decision);
