-- Phase D3: persisted Plan V2 rule-compilation reports.

CREATE TABLE plan_compilations (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id),
    plan_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    canonical_plan JSONB NOT NULL,
    plan_hash TEXT NOT NULL,
    compiler_version TEXT NOT NULL,
    aggregate_status TEXT NOT NULL,
    report_json JSONB NOT NULL,
    report_hash TEXT NOT NULL,
    rendered_markdown TEXT NOT NULL,
    display_hash TEXT NOT NULL,
    coverage_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plan_compilations_revision_check CHECK (revision > 0),
    CONSTRAINT plan_compilations_schema_check CHECK (schema_version = 2),
    CONSTRAINT plan_compilations_plan_json_check CHECK (jsonb_typeof(canonical_plan) = 'object'),
    CONSTRAINT plan_compilations_report_json_check CHECK (jsonb_typeof(report_json) = 'object'),
    CONSTRAINT plan_compilations_status_check CHECK (aggregate_status IN (
        'ready', 'ready_with_advisories', 'revision_required', 'inconclusive'
    )),
    CONSTRAINT plan_compilations_hash_check CHECK (
        plan_hash ~ '^[0-9a-f]{64}$'
        AND report_hash ~ '^[0-9a-f]{64}$'
        AND display_hash ~ '^[0-9a-f]{64}$'
        AND coverage_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX plan_compilations_task_idx ON plan_compilations (task_id, created_at DESC);
CREATE INDEX plan_compilations_plan_idx ON plan_compilations (plan_id, revision, created_at DESC);

CREATE TABLE plan_compilation_projects (
    id UUID PRIMARY KEY,
    compilation_id UUID NOT NULL REFERENCES plan_compilations(id),
    project_ref TEXT NOT NULL,
    project_key TEXT NOT NULL,
    repository_fingerprint TEXT NOT NULL,
    head_commit TEXT NOT NULL,
    dirty_state_hash TEXT NOT NULL,
    scanner_revision TEXT,
    scanner_evidence_status TEXT NOT NULL,
    observed_global_seq BIGINT NOT NULL,
    observed_project_seq BIGINT NOT NULL,
    project_status TEXT NOT NULL,
    target_ids JSONB NOT NULL,
    candidate_rule_count INTEGER NOT NULL,
    applicable_rule_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plan_compilation_projects_project_ref_check CHECK (btrim(project_ref) <> ''),
    CONSTRAINT plan_compilation_projects_project_key_check CHECK (btrim(project_key) <> ''),
    CONSTRAINT plan_compilation_projects_scanner_status_check
        CHECK (scanner_evidence_status IN ('present', 'missing', 'stale')),
    CONSTRAINT plan_compilation_projects_seq_check
        CHECK (observed_global_seq >= 0 AND observed_project_seq >= 0),
    CONSTRAINT plan_compilation_projects_status_check CHECK (project_status IN (
        'ready', 'ready_with_advisories', 'revision_required', 'inconclusive'
    )),
    CONSTRAINT plan_compilation_projects_targets_check CHECK (jsonb_typeof(target_ids) = 'array'),
    CONSTRAINT plan_compilation_projects_count_check CHECK (
        candidate_rule_count >= 0
        AND applicable_rule_count >= 0
        AND applicable_rule_count <= candidate_rule_count
    ),
    CONSTRAINT plan_compilation_projects_ref_uq UNIQUE (compilation_id, project_ref),
    CONSTRAINT plan_compilation_projects_key_uq UNIQUE (compilation_id, project_key)
);

CREATE INDEX plan_compilation_projects_project_idx
    ON plan_compilation_projects (project_key, created_at DESC);

CREATE TABLE plan_rule_checks (
    id UUID NOT NULL,
    compilation_id UUID NOT NULL REFERENCES plan_compilations(id),
    project_key TEXT NOT NULL,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    check_definition_id UUID REFERENCES rule_check_definitions(id),
    rule_content_hash TEXT NOT NULL,
    statement TEXT NOT NULL,
    checker_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    result TEXT NOT NULL,
    result_code TEXT NOT NULL,
    message TEXT NOT NULL,
    target_ids JSONB NOT NULL,
    evidence_trust JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plan_rule_checks_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions(rule_id, version),
    CONSTRAINT plan_rule_checks_pk PRIMARY KEY (compilation_id, id),
    CONSTRAINT plan_rule_checks_severity_check CHECK (severity IN ('advisory', 'required', 'gate')),
    CONSTRAINT plan_rule_checks_result_check CHECK (result IN (
        'not_applicable', 'compatible', 'conflict', 'incomplete_evidence',
        'inconclusive', 'unavailable', 'superseded', 'excepted'
    )),
    CONSTRAINT plan_rule_checks_targets_check CHECK (jsonb_typeof(target_ids) = 'array'),
    CONSTRAINT plan_rule_checks_trust_check CHECK (jsonb_typeof(evidence_trust) = 'array')
);

CREATE INDEX plan_rule_checks_compilation_idx ON plan_rule_checks (compilation_id, project_key);
CREATE INDEX plan_rule_checks_rule_idx ON plan_rule_checks (rule_id, rule_version);
