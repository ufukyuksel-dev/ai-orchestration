-- Public-release slim-down: drop tables whose features were removed (knowledge, ACL, agent tasks and
-- IDE extensions, plans.check, context feedback, auto-curation, analytics, marketplace, transcripts,
-- architecture jobs, IDE bridge) and rule tables that no code reads or writes.
-- Kept rule scopes are global/project only; the project_set/relation machinery goes away.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM rule_scope_assignments WHERE scope_type IN ('project_set', 'relation')) THEN
        RAISE EXCEPTION 'V65: project_set/relation rule scopes exist; migrate them before slim-down';
    END IF;
END $$;

-- Rule scopes: detach from policy sets and relation endpoints.
DROP TRIGGER IF EXISTS rule_scope_assignments_relation_complete ON rule_scope_assignments;
DROP FUNCTION IF EXISTS require_complete_rule_relation_scope();
ALTER TABLE rule_scope_assignments DROP CONSTRAINT IF EXISTS rule_scope_assignments_policy_set_fk;
ALTER TABLE rule_scope_assignments DROP CONSTRAINT IF EXISTS rule_scope_assignments_type_check;
ALTER TABLE rule_scope_assignments
    ADD CONSTRAINT rule_scope_assignments_type_check CHECK (scope_type IN ('global', 'project'));

DROP TABLE IF EXISTS rule_relation_endpoints;
DROP FUNCTION IF EXISTS require_relation_endpoint_scope();
DROP TABLE IF EXISTS rule_policy_set_projects;
DROP TABLE IF EXISTS rule_policy_sets;
DROP TABLE IF EXISTS rule_binding_orphans;

-- Rule gate/evaluation audit tied to agent tasks and context traces.
DROP TABLE IF EXISTS rule_gate_override_consumptions;
DROP TABLE IF EXISTS rule_gate_overrides;
DROP TABLE IF EXISTS rule_evaluation_events;
DROP TABLE IF EXISTS rule_snapshots;
DROP TABLE IF EXISTS learning_context_events;
DROP TABLE IF EXISTS context_traces;

-- plans.check persistence.
DROP TABLE IF EXISTS plan_rule_checks;
DROP TABLE IF EXISTS plan_compilation_projects;
DROP TABLE IF EXISTS plan_compilations;

-- Agent tasks (IDE extensions, analyst/orchestrator) and their satellites.
DROP TABLE IF EXISTS compliance_findings;
DROP TABLE IF EXISTS compliance_packs;
DROP TABLE IF EXISTS guardrail_events;
DROP TABLE IF EXISTS agent_task_waits;
DROP TABLE IF EXISTS agent_artifacts;
DROP TABLE IF EXISTS agent_events;
DROP TABLE IF EXISTS agent_tasks;

-- Knowledge module, its ACL and audit trail.
DROP TABLE IF EXISTS knowledge_chunks;
DROP TABLE IF EXISTS knowledge_ingest_jobs;
DROP TABLE IF EXISTS ingestion_runs;
DROP TABLE IF EXISTS audit_events;
DROP TABLE IF EXISTS user_groups;
DROP TABLE IF EXISTS users;

-- Features without clients.
DROP TABLE IF EXISTS ide_bridge_events;
DROP TABLE IF EXISTS ide_bridge_requests;
DROP TABLE IF EXISTS workflow_analytics_consents;
DROP TABLE IF EXISTS workflow_analytics_counters;
DROP TABLE IF EXISTS user_preferences;
DROP TABLE IF EXISTS mcp_approval_events;
DROP TABLE IF EXISTS mcp_manifests;
DROP TABLE IF EXISTS transcript_capture_queue;
DROP TABLE IF EXISTS architecture_jobs;
DROP TABLE IF EXISTS memory_curation_calibration_log;

-- Semantic anchors lived in Neo4j, which is no longer part of the stack.
DROP TABLE IF EXISTS semantic_anchors;
