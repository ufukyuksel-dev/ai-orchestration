ALTER TABLE rule_versions DROP CONSTRAINT rule_versions_enforcement_check;
ALTER TABLE rule_versions ADD CONSTRAINT rule_versions_enforcement_check
    CHECK (enforcement IN ('advisory', 'context', 'gate', 'instruction'));
