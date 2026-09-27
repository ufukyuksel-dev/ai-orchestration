CREATE TABLE IF NOT EXISTS compliance_packs (
    id TEXT NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    legal_owner TEXT NOT NULL,
    status TEXT NOT NULL,
    effective_date DATE NOT NULL,
    rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version),
    CONSTRAINT compliance_packs_status_check CHECK (status IN ('illustrative', 'approved')),
    CONSTRAINT compliance_packs_rules_array_check CHECK (jsonb_typeof(rules) = 'array')
);

CREATE INDEX IF NOT EXISTS compliance_packs_status_idx ON compliance_packs (status, effective_date DESC);

CREATE TABLE IF NOT EXISTS compliance_findings (
    id UUID PRIMARY KEY,
    task_id UUID REFERENCES agent_tasks(id) ON DELETE CASCADE,
    pack_id TEXT NOT NULL,
    pack_version TEXT NOT NULL,
    rule_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    pack_status TEXT NOT NULL,
    blocking BOOLEAN NOT NULL DEFAULT false,
    finding_hash TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT compliance_findings_severity_check CHECK (severity IN (
        'info', 'low', 'medium', 'high', 'critical'
    )),
    CONSTRAINT compliance_findings_pack_status_check CHECK (pack_status IN ('illustrative', 'approved')),
    CONSTRAINT compliance_findings_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS compliance_findings_task_idx ON compliance_findings (task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS compliance_findings_severity_idx ON compliance_findings (severity, pack_status, blocking);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'agent_artifacts_kind_check'
    ) THEN
        ALTER TABLE agent_artifacts DROP CONSTRAINT agent_artifacts_kind_check;
    END IF;
END $$;

ALTER TABLE agent_artifacts
    ADD CONSTRAINT agent_artifacts_kind_check CHECK (kind IN (
        'plan', 'analysis', 'diff', 'test_result', 'scan_result', 'review', 'compliance_findings'
    ));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'guardrail_events_type_check'
    ) THEN
        ALTER TABLE guardrail_events DROP CONSTRAINT guardrail_events_type_check;
    END IF;
END $$;

ALTER TABLE guardrail_events
    ADD CONSTRAINT guardrail_events_type_check CHECK (guardrail_type IN (
        'plan_approval', 'sandbox', 'command_policy', 'test_gate', 'secret_scan', 'artifact_path',
        'compliance_review'
    ));
