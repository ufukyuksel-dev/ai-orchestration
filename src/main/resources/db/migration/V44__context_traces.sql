-- One context trace header can own many retrieval, injection, and terminal
-- outcome events. task_id remains nullable for resolver work outside a task.
CREATE TABLE context_traces (
    id UUID PRIMARY KEY,
    task_id UUID,
    project_key TEXT,
    role TEXT NOT NULL,
    query_hash TEXT NOT NULL,
    context_mode TEXT NOT NULL,
    provider TEXT,
    token_estimate INTEGER NOT NULL,
    graph_available BOOLEAN NOT NULL,
    semantic_available BOOLEAN NOT NULL,
    fallback_used BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT context_traces_task_fk
        FOREIGN KEY (task_id) REFERENCES agent_tasks (id) ON DELETE SET NULL,
    CONSTRAINT context_traces_project_key_nonblank_check
        CHECK (project_key IS NULL OR btrim(project_key) <> ''),
    CONSTRAINT context_traces_role_nonblank_check CHECK (btrim(role) <> ''),
    CONSTRAINT context_traces_query_hash_nonblank_check CHECK (btrim(query_hash) <> ''),
    CONSTRAINT context_traces_mode_check CHECK (context_mode IN (
        'memory_only', 'graph_context', 'graph_plus_semantic', 'graph_plus_distilled', 'fallback'
    )),
    CONSTRAINT context_traces_provider_nonblank_check
        CHECK (provider IS NULL OR btrim(provider) <> ''),
    CONSTRAINT context_traces_token_estimate_check CHECK (token_estimate >= 0)
);

CREATE INDEX context_traces_task_idx ON context_traces (task_id) WHERE task_id IS NOT NULL;

ALTER TABLE learning_context_events
    ADD COLUMN context_trace_id UUID;
ALTER TABLE learning_context_events
    ADD CONSTRAINT learning_context_events_context_trace_fk
    FOREIGN KEY (context_trace_id) REFERENCES context_traces (id);

-- V42 reserves this column so evaluation events can be emitted before the trace
-- header migration; V44 closes that relationship with a real foreign key.
ALTER TABLE rule_evaluation_events
    ADD CONSTRAINT rule_evaluation_events_trace_fk
    FOREIGN KEY (context_trace_id) REFERENCES context_traces (id) NOT VALID;

ALTER TABLE rule_snapshots
    ADD CONSTRAINT rule_snapshots_trace_fk
    FOREIGN KEY (context_trace_id) REFERENCES context_traces (id);

ALTER TABLE learning_context_events
    ADD COLUMN rule_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN outcome_subject TEXT,
    ADD COLUMN source_event_id TEXT,
    ADD COLUMN idempotency_key TEXT,
    ADD CONSTRAINT learning_context_events_rule_ids_array_check
        CHECK (jsonb_typeof(rule_ids) = 'array'),
    ADD CONSTRAINT learning_context_events_outcome_subject_nonblank_check
        CHECK (outcome_subject IS NULL OR btrim(outcome_subject) <> ''),
    ADD CONSTRAINT learning_context_events_source_event_id_nonblank_check
        CHECK (source_event_id IS NULL OR btrim(source_event_id) <> ''),
    ADD CONSTRAINT learning_context_events_idempotency_key_nonblank_check
        CHECK (idempotency_key IS NULL OR btrim(idempotency_key) <> '');

CREATE INDEX learning_context_events_trace_idx
    ON learning_context_events (context_trace_id) WHERE context_trace_id IS NOT NULL;

-- Idempotency is source-event scoped within one exact trace. Null keys remain
-- intentionally repeatable for non-idempotent observational events.
CREATE UNIQUE INDEX learning_context_events_idem_uq
    ON learning_context_events (context_trace_id, idempotency_key)
    WHERE context_trace_id IS NOT NULL AND idempotency_key IS NOT NULL;
