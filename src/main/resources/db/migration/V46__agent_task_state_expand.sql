-- Expand-only release for the agent task V2 state model.
-- Exact status <-> open-wait parity is intentionally deferred to V47 after
-- all readers are upgraded and legacy awaiting_approval rows are reconciled.

ALTER TABLE agent_tasks
    DROP CONSTRAINT agent_tasks_status_check,
    ADD CONSTRAINT agent_tasks_status_check CHECK (status IN (
        'pending', 'in_progress', 'awaiting_approval', 'awaiting_input', 'blocked',
        'partially_applied', 'completed', 'rejected', 'failed'
    )),
    DROP CONSTRAINT agent_tasks_type_check,
    ADD CONSTRAINT agent_tasks_type_check CHECK (task_type IN (
        'analyze_code', 'write_code', 'scan_codebase', 'general_question', 'ambiguous',
        'composite_change'
    )),
    ADD CONSTRAINT agent_tasks_composite_role_check
        CHECK (task_type <> 'composite_change' OR assigned_role = 'orchestrator');

CREATE TABLE agent_task_waits (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    wait_kind TEXT NOT NULL,
    related_compilation_id UUID,
    related_artifact_id UUID REFERENCES agent_artifacts(id) ON DELETE SET NULL,
    decision_contract_hash TEXT,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolution_id UUID,
    CONSTRAINT agent_task_waits_kind_check CHECK (wait_kind IN (
        'plan_approval', 'diff_approval', 'composite_change_approval',
        'composite_prepare_failed', 'composite_apply_blocked',
        'business_decision', 'rule_exception', 'cross_project_coordination',
        'cross_project_recovery', 'cross_project_debt', 'plan_revision_exhausted',
        'plan_inconclusive', 'rule_service_unavailable', 'plan_stale',
        'legacy_wait_reconciliation'
    )),
    CONSTRAINT agent_task_waits_decision_hash_check
        CHECK (decision_contract_hash IS NULL OR decision_contract_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT agent_task_waits_resolution_pair_check
        CHECK ((resolved_at IS NULL) = (resolution_id IS NULL)),
    CONSTRAINT agent_task_waits_resolution_time_check
        CHECK (resolved_at IS NULL OR resolved_at >= opened_at)
);

CREATE UNIQUE INDEX agent_task_waits_open_task_uq
    ON agent_task_waits (task_id)
    WHERE resolved_at IS NULL;

CREATE UNIQUE INDEX agent_task_waits_resolution_uq
    ON agent_task_waits (resolution_id)
    WHERE resolution_id IS NOT NULL;

CREATE INDEX agent_task_waits_task_history_idx
    ON agent_task_waits (task_id, opened_at DESC);

CREATE INDEX agent_task_waits_compilation_idx
    ON agent_task_waits (related_compilation_id)
    WHERE related_compilation_id IS NOT NULL;

ALTER TABLE workflow_analytics_counters
    DROP CONSTRAINT workflow_analytics_task_type_check,
    ADD CONSTRAINT workflow_analytics_task_type_check CHECK (task_type IN (
        'analyze_code', 'write_code', 'scan_codebase', 'general_question', 'ambiguous',
        'composite_change'
    )),
    DROP CONSTRAINT workflow_analytics_target_agent_check,
    ADD CONSTRAINT workflow_analytics_target_agent_check CHECK (target_agent IN (
        'analyst', 'software', 'scanner', 'direct_answer', 'human_clarification', 'orchestrator'
    )),
    DROP CONSTRAINT workflow_analytics_outcome_check,
    ADD CONSTRAINT workflow_analytics_outcome_check CHECK (outcome IN (
        'delegated', 'awaiting_approval', 'completed', 'blocked', 'failed', 'other',
        'composite_prepare_failed', 'composite_apply_blocked', 'partial_apply',
        'coordination_deferred', 'recovery_completed', 'compensated_abort'
    ));
