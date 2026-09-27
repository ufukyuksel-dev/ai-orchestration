-- Rule authority core: logical identity (rule_definitions) + immutable content
-- versions (rule_versions) + queryable target bindings. Statistics are derived
-- from append-only rule_evaluation_events; no mutable counters.

CREATE TABLE rule_definitions (
    id UUID PRIMARY KEY,
    origin_memory_id UUID REFERENCES memory_items(id) ON DELETE SET NULL,
    project_key TEXT,
    current_version INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_definitions_status_check CHECK (status IN ('active', 'deprecated'))
);

CREATE UNIQUE INDEX rule_definitions_origin_memory_uq
    ON rule_definitions (origin_memory_id) WHERE origin_memory_id IS NOT NULL;
CREATE INDEX rule_definitions_project_status_idx ON rule_definitions (project_key, status);

CREATE TABLE rule_versions (
    rule_id UUID NOT NULL REFERENCES rule_definitions(id),
    version INTEGER NOT NULL,
    statement TEXT NOT NULL,
    rationale TEXT,
    enforcement TEXT NOT NULL,
    applies_all BOOLEAN NOT NULL DEFAULT false,
    detector_type TEXT,
    detector_config JSONB,
    content_hash TEXT NOT NULL,
    origin_content_hash TEXT,
    origin_provenance TEXT NOT NULL,
    approved_by TEXT NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    human_turn_ref TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version),
    CONSTRAINT rule_versions_enforcement_check CHECK (enforcement IN ('advisory', 'context', 'gate')),
    CONSTRAINT rule_versions_provenance_check CHECK (origin_provenance IN
        ('human', 'direct_human_policy', 'review_mining', 'incident', 'curated')),
    CONSTRAINT rule_versions_gate_detector_check CHECK (enforcement <> 'gate' OR detector_type IS NOT NULL),
    CONSTRAINT rule_versions_gate_targeted_check CHECK (enforcement <> 'gate' OR applies_all = false)
);

-- current_version must always point at a real version row; promotion writes
-- definition + version in one transaction, so the check is deferred to commit.
ALTER TABLE rule_definitions
    ADD CONSTRAINT rule_definitions_current_version_fk
    FOREIGN KEY (id, current_version) REFERENCES rule_versions (rule_id, version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION reject_rule_version_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'rule authority rows are immutable (append-only)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rule_versions_immutable
    BEFORE UPDATE OR DELETE ON rule_versions
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE TABLE rule_target_bindings (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    binding_kind TEXT NOT NULL,
    target_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_target_bindings_kind_check CHECK (binding_kind IN ('file', 'symbol', 'capsule', 'path_glob')),
    CONSTRAINT rule_target_bindings_unique UNIQUE (rule_id, rule_version, binding_kind, target_key),
    CONSTRAINT rule_target_bindings_glob_len_check CHECK (binding_kind <> 'path_glob' OR length(target_key) <= 256)
);

CREATE INDEX rule_target_bindings_lookup_idx ON rule_target_bindings (binding_kind, target_key);

CREATE TRIGGER rule_target_bindings_immutable
    BEFORE UPDATE OR DELETE ON rule_target_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

-- Orphan state is derived; binding rows stay immutable.
CREATE TABLE rule_binding_orphans (
    binding_id UUID NOT NULL REFERENCES rule_target_bindings(id),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    baseline_scan_ref TEXT,
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (binding_id, detected_at)
);

CREATE UNIQUE INDEX rule_binding_orphans_open_uq
    ON rule_binding_orphans (binding_id) WHERE resolved_at IS NULL;

CREATE TABLE rules_change_seq (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    seq BIGINT NOT NULL DEFAULT 0
);

INSERT INTO rules_change_seq (singleton, seq) VALUES (true, 0);

CREATE TABLE rule_evaluation_events (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    task_id UUID,
    context_trace_id UUID,
    phase TEXT NOT NULL,
    result TEXT NOT NULL,
    detail JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_evaluation_events_phase_check CHECK (phase IN
        ('snapshot', 'injection', 'gate', 'final_recheck')),
    CONSTRAINT rule_evaluation_events_result_check CHECK (result IN
        ('selected', 'injected', 'dropped_budget', 'matched', 'would_block', 'blocked', 'passed',
         'overridden', 'reapproval_required', 'inconclusive'))
);

CREATE INDEX rule_evaluation_events_rule_idx ON rule_evaluation_events (rule_id, created_at DESC);
CREATE INDEX rule_evaluation_events_trace_idx ON rule_evaluation_events (context_trace_id)
    WHERE context_trace_id IS NOT NULL;
