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
        'plan', 'plan_json', 'plan_rule_report', 'analysis', 'diff', 'test_result',
        'scan_result', 'review', 'compliance_findings', 'documentation', 'codegen_forensics'
    ));
