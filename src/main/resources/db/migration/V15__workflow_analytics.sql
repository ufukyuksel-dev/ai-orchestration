CREATE TABLE IF NOT EXISTS workflow_analytics_consents (
    user_id TEXT PRIMARY KEY,
    opted_in BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    purged_at TIMESTAMPTZ,
    CONSTRAINT workflow_analytics_consents_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE IF NOT EXISTS workflow_analytics_counters (
    user_id_hash TEXT NOT NULL,
    hour_bucket TIMESTAMPTZ NOT NULL,
    task_type TEXT NOT NULL,
    target_agent TEXT NOT NULL,
    outcome TEXT NOT NULL,
    event_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id_hash, hour_bucket, task_type, target_agent, outcome),
    CONSTRAINT workflow_analytics_counter_non_negative_check CHECK (event_count >= 0),
    CONSTRAINT workflow_analytics_task_type_check CHECK (task_type IN (
        'analyze_code', 'write_code', 'scan_codebase', 'general_question', 'ambiguous'
    )),
    CONSTRAINT workflow_analytics_target_agent_check CHECK (target_agent IN (
        'analyst', 'software', 'scanner', 'direct_answer', 'human_clarification'
    )),
    CONSTRAINT workflow_analytics_outcome_check CHECK (outcome IN (
        'delegated', 'awaiting_approval', 'completed', 'blocked', 'failed', 'other'
    ))
);

CREATE INDEX IF NOT EXISTS workflow_analytics_summary_idx
    ON workflow_analytics_counters (hour_bucket DESC, task_type, target_agent, outcome);

CREATE INDEX IF NOT EXISTS workflow_analytics_consent_idx
    ON workflow_analytics_consents (opted_in, updated_at DESC);
