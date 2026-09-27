CREATE TABLE IF NOT EXISTS learning_context_events (
    id UUID PRIMARY KEY,
    project_key TEXT,
    task_id UUID REFERENCES agent_tasks(id) ON DELETE SET NULL,
    role TEXT NOT NULL,
    query_hash TEXT NOT NULL,
    provider TEXT,
    context_mode TEXT NOT NULL,
    memory_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    symbol_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    capsule_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    path_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    token_estimate INTEGER NOT NULL,
    graph_available BOOLEAN NOT NULL,
    semantic_available BOOLEAN NOT NULL,
    fallback_used BOOLEAN NOT NULL,
    outcome TEXT NOT NULL,
    outcome_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT learning_context_events_mode_check CHECK (context_mode IN (
        'memory_only', 'graph_context', 'graph_plus_semantic', 'graph_plus_distilled', 'fallback'
    )),
    CONSTRAINT learning_context_events_outcome_check CHECK (outcome IN (
        'retrieved', 'used_for_prompt', 'task_completed', 'task_failed',
        'human_approved', 'human_rejected', 'test_passed', 'test_failed'
    )),
    CONSTRAINT learning_context_events_memory_ids_array_check CHECK (jsonb_typeof(memory_ids) = 'array'),
    CONSTRAINT learning_context_events_symbol_ids_array_check CHECK (jsonb_typeof(symbol_ids) = 'array'),
    CONSTRAINT learning_context_events_capsule_keys_array_check CHECK (jsonb_typeof(capsule_keys) = 'array'),
    CONSTRAINT learning_context_events_path_ids_array_check CHECK (jsonb_typeof(path_ids) = 'array'),
    CONSTRAINT learning_context_events_token_estimate_check CHECK (token_estimate >= 0)
);

CREATE INDEX IF NOT EXISTS learning_context_events_project_query_idx
    ON learning_context_events (project_key, query_hash);
CREATE INDEX IF NOT EXISTS learning_context_events_project_created_idx
    ON learning_context_events (project_key, created_at DESC);
CREATE INDEX IF NOT EXISTS learning_context_events_task_idx
    ON learning_context_events (task_id)
    WHERE task_id IS NOT NULL;
