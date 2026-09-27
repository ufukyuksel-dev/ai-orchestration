-- Harden the V42 rule authority without rewriting the already published
-- migration. This file is deliberately upgrade-safe for databases that have
-- already applied V42.

ALTER TABLE rule_definitions
    ADD CONSTRAINT rule_definitions_current_version_positive_check
        CHECK (current_version > 0),
    ADD CONSTRAINT rule_definitions_project_key_nonblank_check
        CHECK (project_key IS NULL OR btrim(project_key) <> '');

-- V42 made versions immutable before all approval and detector evidence was
-- modeled. Temporarily remove only that trigger while legacy rows are
-- backfilled, then restore it before this migration completes.
DROP TRIGGER rule_versions_immutable ON rule_versions;

ALTER TABLE rule_versions
    ADD COLUMN detector_contract_hash TEXT,
    ADD COLUMN origin_memory_id UUID,
    ADD COLUMN approval_content_hash TEXT,
    ADD COLUMN confirmation_card_hash TEXT,
    ADD COLUMN human_raw_text_hash TEXT,
    ADD COLUMN workflow_contract_version TEXT;

-- A V42 row predates cryptographically bound approval receipts. Preserve it
-- as explicitly legacy evidence rather than inventing human input.
UPDATE rule_versions
SET detector_contract_hash = CASE
        WHEN detector_type IS NULL THEN NULL
        ELSE repeat('0', 64)
    END,
    approval_content_hash = repeat('0', 64),
    confirmation_card_hash = repeat('0', 64),
    human_raw_text_hash = repeat('0', 64),
    workflow_contract_version = 'rules-v42-legacy';

-- V42 wrote the promoted rule identity/version into memory metadata. Recover
-- every historical version from that exact evidence instead of copying the
-- definition's current origin onto old versions.
UPDATE rule_versions version
SET origin_memory_id = (
    SELECT memory.id
    FROM memory_items memory
    WHERE memory.memory_type = 'rule'
      AND memory.metadata ->> 'promotedRuleId' = version.rule_id::text
      AND memory.metadata ->> 'promotedRuleVersion' = version.version::text
    ORDER BY memory.id
    LIMIT 1
)
WHERE version.origin_content_hash IS NOT NULL
  AND version.origin_provenance <> 'direct_human_policy';

-- Some early V42 rows may predate the metadata write but still retain an exact
-- current-origin FK on the definition. That evidence is defensible only for
-- the current version; it must never be projected onto history.
UPDATE rule_versions version
SET origin_memory_id = definition.origin_memory_id
FROM rule_definitions definition
WHERE definition.id = version.rule_id
  AND definition.current_version = version.version
  AND version.origin_content_hash IS NOT NULL
  AND version.origin_memory_id IS NULL
  AND definition.origin_memory_id IS NOT NULL
  AND version.origin_provenance <> 'direct_human_policy';

UPDATE rule_versions
SET origin_memory_id = NULL,
    origin_content_hash = NULL
WHERE origin_memory_id IS NULL OR origin_provenance = 'direct_human_policy';

ALTER TABLE rule_versions
    ALTER COLUMN approval_content_hash SET NOT NULL,
    ALTER COLUMN confirmation_card_hash SET NOT NULL,
    ALTER COLUMN human_raw_text_hash SET NOT NULL,
    ALTER COLUMN workflow_contract_version SET NOT NULL,
    DROP CONSTRAINT rule_versions_gate_detector_check,
    ADD CONSTRAINT rule_versions_version_positive_check CHECK (version > 0),
    ADD CONSTRAINT rule_versions_statement_nonblank_check CHECK (btrim(statement) <> ''),
    ADD CONSTRAINT rule_versions_gate_detector_check
        CHECK ((detector_type IS NULL OR btrim(detector_type) <> '')
            AND (enforcement <> 'gate' OR detector_type IS NOT NULL)),
    ADD CONSTRAINT rule_versions_detector_config_object_check
        CHECK (detector_config IS NULL OR jsonb_typeof(detector_config) = 'object'),
    ADD CONSTRAINT rule_versions_detector_contract_pair_check
        CHECK ((detector_type IS NULL) = (detector_contract_hash IS NULL)),
    ADD CONSTRAINT rule_versions_detector_contract_hash_nonblank_check
        CHECK (detector_contract_hash IS NULL OR detector_contract_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT rule_versions_content_hash_nonblank_check CHECK (btrim(content_hash) <> ''),
    ADD CONSTRAINT rule_versions_origin_evidence_pair_check
        CHECK ((origin_memory_id IS NULL) = (origin_content_hash IS NULL)),
    ADD CONSTRAINT rule_versions_origin_content_hash_nonblank_check
        CHECK (origin_content_hash IS NULL OR btrim(origin_content_hash) <> ''),
    ADD CONSTRAINT rule_versions_direct_origin_check
        CHECK (origin_provenance <> 'direct_human_policy' OR origin_memory_id IS NULL),
    ADD CONSTRAINT rule_versions_approved_by_nonblank_check CHECK (btrim(approved_by) <> ''),
    ADD CONSTRAINT rule_versions_human_turn_ref_nonblank_check CHECK (btrim(human_turn_ref) <> ''),
    ADD CONSTRAINT rule_versions_approval_content_hash_nonblank_check
        CHECK (btrim(approval_content_hash) <> ''),
    ADD CONSTRAINT rule_versions_confirmation_card_hash_nonblank_check
        CHECK (btrim(confirmation_card_hash) <> ''),
    ADD CONSTRAINT rule_versions_human_raw_text_hash_nonblank_check
        CHECK (btrim(human_raw_text_hash) <> ''),
    ADD CONSTRAINT rule_versions_workflow_contract_version_nonblank_check
        CHECK (btrim(workflow_contract_version) <> ''),
    ADD CONSTRAINT rule_versions_origin_tuple_uq
        UNIQUE (rule_id, version, origin_memory_id);

-- A version may keep its immutable origin UUID after an explicit hard delete,
-- while every newly written claim must reference a memory that exists at
-- insert time. A regular FK cannot express both requirements because its
-- delete action would either block evidence cleanup or mutate history.
CREATE OR REPLACE FUNCTION require_rule_version_origin_memory() RETURNS trigger AS $$
BEGIN
    IF NEW.origin_memory_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM memory_items WHERE id = NEW.origin_memory_id
    ) THEN
        RAISE EXCEPTION 'rule_versions_origin_memory_fk: origin memory does not exist';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rule_versions_origin_memory_exists
    BEFORE INSERT ON rule_versions
    FOR EACH ROW EXECUTE FUNCTION require_rule_version_origin_memory();

CREATE INDEX rule_versions_origin_memory_idx
    ON rule_versions (origin_memory_id) WHERE origin_memory_id IS NOT NULL;

CREATE TRIGGER rule_versions_immutable
    BEFORE UPDATE OR DELETE ON rule_versions
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

ALTER TABLE rule_target_bindings
    ADD CONSTRAINT rule_target_bindings_target_key_nonblank_check
        CHECK (btrim(target_key) <> '');

CREATE OR REPLACE FUNCTION require_gate_rule_target_binding() RETURNS trigger AS $$
BEGIN
    IF NEW.enforcement = 'gate' AND NOT EXISTS (
        SELECT 1
        FROM rule_target_bindings binding
        WHERE binding.rule_id = NEW.rule_id
          AND binding.rule_version = NEW.version
    ) THEN
        RAISE EXCEPTION 'gate rule version requires at least one target binding';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER rule_versions_gate_binding_required
    AFTER INSERT ON rule_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_gate_rule_target_binding();

ALTER TABLE rules_change_seq
    ADD CONSTRAINT rules_change_seq_nonnegative_check CHECK (seq >= 0);

-- A promoted memory remains provenance evidence. Lifecycle metadata and
-- retrieval status may change, but its identity/content fields may not.
CREATE OR REPLACE FUNCTION reject_linked_rule_memory_authority_mutation() RETURNS trigger AS $$
BEGIN
    IF ROW(
        NEW.vector_id, NEW.scope, NEW.project_key, NEW.memory_type,
        NEW.summary, NEW.text, NEW.tags, NEW.confidence,
        NEW.source_type, NEW.source_ref, NEW.owner, NEW.created_at, NEW.expires_at
    ) IS DISTINCT FROM ROW(
        OLD.vector_id, OLD.scope, OLD.project_key, OLD.memory_type,
        OLD.summary, OLD.text, OLD.tags, OLD.confidence,
        OLD.source_type, OLD.source_ref, OLD.owner, OLD.created_at, OLD.expires_at
    ) AND (
        EXISTS (SELECT 1 FROM rule_definitions WHERE origin_memory_id = OLD.id)
        OR EXISTS (SELECT 1 FROM rule_versions WHERE origin_memory_id = OLD.id)
    ) THEN
        RAISE EXCEPTION 'linked rule origin memory authority is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER memory_items_linked_rule_authority_immutable
    BEFORE UPDATE ON memory_items
    FOR EACH ROW EXECUTE FUNCTION reject_linked_rule_memory_authority_mutation();

-- Durable transactional outbox for refreshing promoted RULE vector payloads
-- and applying authority markers to both Neo4j retrieval surfaces. Each sink
-- records progress independently so an optional backend cannot cause already
-- completed external writes to repeat forever.
CREATE TABLE rule_memory_projection_work (
    memory_id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    vector_projected_at TIMESTAMPTZ,
    graph_marked_at TIMESTAMPTZ,
    semantic_marked_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT rule_memory_projection_work_memory_fk
        FOREIGN KEY (memory_id) REFERENCES memory_items (id) ON DELETE CASCADE,
    CONSTRAINT rule_memory_projection_work_rule_version_fk
        FOREIGN KEY (rule_id, rule_version, memory_id)
        REFERENCES rule_versions (rule_id, version, origin_memory_id),
    CONSTRAINT rule_memory_projection_work_attempt_nonnegative_check
        CHECK (attempt_count >= 0)
);

CREATE INDEX rule_memory_projection_work_available_idx
    ON rule_memory_projection_work (available_at, requested_at, memory_id);

-- Promotions committed before this outbox existed must receive the same
-- projection cleanup as new promotions. DISTINCT ON is defensive against
-- malformed legacy duplicates and deterministically keeps the newest version.
INSERT INTO rule_memory_projection_work (memory_id, rule_id, rule_version)
SELECT DISTINCT ON (origin_memory_id)
       origin_memory_id, rule_id, version
FROM rule_versions
WHERE origin_memory_id IS NOT NULL
ORDER BY origin_memory_id, version DESC;

CREATE OR REPLACE FUNCTION reject_rule_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'rule audit rows are immutable (append-only)';
END;
$$ LANGUAGE plpgsql;

ALTER TABLE rule_evaluation_events
    RENAME CONSTRAINT rule_evaluation_events_rule_id_rule_version_fkey
        TO rule_evaluation_events_rule_version_fk;

ALTER TABLE rule_evaluation_events
    ADD CONSTRAINT rule_evaluation_events_task_fk
        FOREIGN KEY (task_id) REFERENCES agent_tasks (id) NOT VALID,
    ADD CONSTRAINT rule_evaluation_events_detail_object_check
        CHECK (detail IS NULL OR jsonb_typeof(detail) = 'object');

CREATE TRIGGER rule_evaluation_events_immutable
    BEFORE UPDATE OR DELETE ON rule_evaluation_events
    FOR EACH ROW EXECUTE FUNCTION reject_rule_audit_mutation();

CREATE TABLE rule_lifecycle_events (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    action TEXT NOT NULL,
    actor TEXT NOT NULL,
    human_turn_ref TEXT NOT NULL,
    human_raw_text_hash TEXT NOT NULL,
    reason TEXT,
    approval_content_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_lifecycle_events_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_lifecycle_events_action_check CHECK (action IN ('promoted', 'deprecated')),
    CONSTRAINT rule_lifecycle_events_actor_nonblank_check CHECK (btrim(actor) <> ''),
    CONSTRAINT rule_lifecycle_events_human_turn_ref_nonblank_check CHECK (btrim(human_turn_ref) <> ''),
    CONSTRAINT rule_lifecycle_events_human_raw_text_hash_nonblank_check
        CHECK (btrim(human_raw_text_hash) <> ''),
    CONSTRAINT rule_lifecycle_events_reason_nonblank_check
        CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT rule_lifecycle_events_approval_content_hash_nonblank_check
        CHECK (btrim(approval_content_hash) <> ''),
    CONSTRAINT rule_lifecycle_events_approval_uq UNIQUE (action, approval_content_hash)
);

CREATE INDEX rule_lifecycle_events_rule_idx
    ON rule_lifecycle_events (rule_id, created_at DESC);

CREATE TRIGGER rule_lifecycle_events_immutable
    BEFORE UPDATE OR DELETE ON rule_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION reject_rule_audit_mutation();

CREATE TABLE rule_gate_overrides (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    diff_sha TEXT NOT NULL,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    finding_hash TEXT NOT NULL,
    reason TEXT NOT NULL,
    approved_by TEXT NOT NULL,
    human_turn_ref TEXT NOT NULL,
    blocked_evaluation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_gate_overrides_task_fk FOREIGN KEY (task_id) REFERENCES agent_tasks (id),
    CONSTRAINT rule_gate_overrides_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_gate_overrides_blocked_evaluation_fk
        FOREIGN KEY (blocked_evaluation_id) REFERENCES rule_evaluation_events (id),
    CONSTRAINT rule_gate_overrides_diff_sha_nonblank_check CHECK (btrim(diff_sha) <> ''),
    CONSTRAINT rule_gate_overrides_finding_hash_nonblank_check CHECK (btrim(finding_hash) <> ''),
    CONSTRAINT rule_gate_overrides_reason_nonblank_check CHECK (btrim(reason) <> ''),
    CONSTRAINT rule_gate_overrides_approved_by_nonblank_check CHECK (btrim(approved_by) <> ''),
    CONSTRAINT rule_gate_overrides_human_turn_ref_nonblank_check CHECK (btrim(human_turn_ref) <> ''),
    CONSTRAINT rule_gate_overrides_unique
        UNIQUE (task_id, diff_sha, rule_id, rule_version, finding_hash),
    CONSTRAINT rule_gate_overrides_consumption_identity_uq UNIQUE (id, task_id, diff_sha)
);

CREATE TRIGGER rule_gate_overrides_immutable
    BEFORE UPDATE OR DELETE ON rule_gate_overrides
    FOR EACH ROW EXECUTE FUNCTION reject_rule_audit_mutation();

CREATE TABLE rule_gate_override_consumptions (
    override_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    diff_sha TEXT NOT NULL,
    evaluation_event_id UUID,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_gate_override_consumptions_override_fk
        FOREIGN KEY (override_id, task_id, diff_sha)
        REFERENCES rule_gate_overrides (id, task_id, diff_sha),
    CONSTRAINT rule_gate_override_consumptions_task_fk
        FOREIGN KEY (task_id) REFERENCES agent_tasks (id),
    CONSTRAINT rule_gate_override_consumptions_evaluation_event_fk
        FOREIGN KEY (evaluation_event_id) REFERENCES rule_evaluation_events (id),
    CONSTRAINT rule_gate_override_consumptions_diff_sha_nonblank_check CHECK (btrim(diff_sha) <> '')
);

CREATE TRIGGER rule_gate_override_consumptions_immutable
    BEFORE UPDATE OR DELETE ON rule_gate_override_consumptions
    FOR EACH ROW EXECUTE FUNCTION reject_rule_audit_mutation();

-- Task-time rule selection is persisted as self-contained, immutable evidence.
-- context_trace_id is linked logically here; V44 closes that relationship.
CREATE TABLE rule_snapshots (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    context_trace_id UUID NOT NULL,
    rules_change_seq BIGINT NOT NULL,
    rules JSONB NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_snapshots_task_fk FOREIGN KEY (task_id) REFERENCES agent_tasks (id),
    CONSTRAINT rule_snapshots_change_seq_nonnegative_check CHECK (rules_change_seq >= 0),
    CONSTRAINT rule_snapshots_rules_array_check CHECK (jsonb_typeof(rules) = 'array')
);

CREATE INDEX rule_snapshots_task_idx ON rule_snapshots (task_id, resolved_at DESC);
CREATE INDEX rule_snapshots_trace_idx ON rule_snapshots (context_trace_id);

CREATE TRIGGER rule_snapshots_immutable
    BEFORE UPDATE OR DELETE ON rule_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_rule_audit_mutation();
