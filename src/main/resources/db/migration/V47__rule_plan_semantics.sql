-- Plan-time rule semantics and effective reach sequences.
--
-- Earlier design drafts reserved V47 for the agent-task wait parity trigger.
-- V46 has already shipped and Flyway out-of-order migration is disabled, so
-- release numbers follow actual publication order. The wait reconciliation +
-- enforce migration remains a separate Phase C2 release using the next free
-- version number after the Phase D migrations.
--
-- Domain enums use upper-case Java/JSON names. As in V42/V43, database wire
-- values are canonical lower-case strings.

-- A rolling deployment can leave a V46 writer alive while Flyway applies this
-- migration. Hold both authority snapshots stable until the backfill, deferred
-- scope fence and effective-sequence bootstrap are installed. The locks are
-- transaction-scoped on PostgreSQL and release when the migration commits.
SELECT seq FROM rules_change_seq WHERE singleton = true FOR UPDATE;
LOCK TABLE rule_versions IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE rule_selector_groups (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    group_key TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_selector_groups_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_selector_groups_key_check
        CHECK (btrim(group_key) <> '' AND length(group_key) <= 128),
    CONSTRAINT rule_selector_groups_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 255),
    CONSTRAINT rule_selector_groups_key_uq
        UNIQUE (rule_id, rule_version, group_key),
    CONSTRAINT rule_selector_groups_ordinal_uq
        UNIQUE (rule_id, rule_version, ordinal),
    CONSTRAINT rule_selector_groups_identity_uq
        UNIQUE (id, rule_id, rule_version)
);

CREATE TRIGGER rule_selector_groups_immutable
    BEFORE UPDATE OR DELETE ON rule_selector_groups
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE OR REPLACE FUNCTION rule_selector_values_valid(
    candidate_values JSONB,
    candidate_operator TEXT
) RETURNS BOOLEAN AS $$
DECLARE
    item JSONB;
    item_text TEXT;
    item_count INTEGER;
BEGIN
    IF jsonb_typeof(candidate_values) <> 'array' THEN
        RETURN false;
    END IF;

    item_count := jsonb_array_length(candidate_values);
    IF candidate_operator = 'present' THEN
        RETURN item_count = 0;
    END IF;
    IF item_count < 1 OR item_count > 32 THEN
        RETURN false;
    END IF;
    IF candidate_operator = 'equals' AND item_count <> 1 THEN
        RETURN false;
    END IF;

    FOR item IN SELECT value FROM jsonb_array_elements(candidate_values)
    LOOP
        IF jsonb_typeof(item) <> 'string' THEN
            RETURN false;
        END IF;
        item_text := item #>> '{}';
        IF btrim(item_text) = '' OR length(item_text) > 256 THEN
            RETURN false;
        END IF;
    END LOOP;

    RETURN item_count = (
        SELECT count(DISTINCT value)
        FROM jsonb_array_elements(candidate_values)
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE TABLE rule_selector_predicates (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES rule_selector_groups (id),
    polarity TEXT NOT NULL,
    field TEXT NOT NULL,
    operator TEXT NOT NULL,
    values JSONB NOT NULL DEFAULT '[]'::jsonb,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_selector_predicates_polarity_check
        CHECK (polarity IN ('include', 'exclude')),
    CONSTRAINT rule_selector_predicates_field_check
        CHECK (field IN (
            'path', 'symbol', 'role', 'annotation', 'source_set', 'generated',
            'artifact_kind', 'language', 'operation', 'capsule', 'capsule_layer', 'intent_kind'
        )),
    CONSTRAINT rule_selector_predicates_operator_check
        CHECK (operator IN ('equals', 'in', 'glob', 'present')),
    CONSTRAINT rule_selector_predicates_values_array_check
        CHECK (jsonb_typeof(values) = 'array'),
    CONSTRAINT rule_selector_predicates_values_contract_check
        CHECK (rule_selector_values_valid(values, operator)),
    CONSTRAINT rule_selector_predicates_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 255),
    CONSTRAINT rule_selector_predicates_ordinal_uq UNIQUE (group_id, ordinal),
    CONSTRAINT rule_selector_predicates_content_uq
        UNIQUE (group_id, polarity, field, operator, values)
);

CREATE INDEX rule_selector_predicates_group_idx
    ON rule_selector_predicates (group_id, ordinal);

CREATE TRIGGER rule_selector_predicates_immutable
    BEFORE UPDATE OR DELETE ON rule_selector_predicates
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE OR REPLACE FUNCTION require_rule_selector_group_include() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM rule_selector_predicates predicate
        WHERE predicate.group_id = NEW.id
          AND predicate.polarity = 'include'
    ) THEN
        RAISE EXCEPTION 'selector group requires at least one include predicate';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER rule_selector_groups_nonempty
    AFTER INSERT ON rule_selector_groups
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_rule_selector_group_include();

CREATE TABLE rule_check_definitions (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    phase TEXT NOT NULL,
    checker_type TEXT NOT NULL,
    implementation_version TEXT NOT NULL,
    config_schema_version TEXT NOT NULL,
    severity TEXT NOT NULL,
    config JSONB NOT NULL,
    checker_contract_hash TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_check_definitions_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_check_definitions_phase_check
        CHECK (phase IN ('plan', 'diff', 'ast', 'post_test')),
    CONSTRAINT rule_check_definitions_checker_type_check
        CHECK (btrim(checker_type) <> '' AND length(checker_type) <= 128),
    CONSTRAINT rule_check_definitions_implementation_version_check
        CHECK (btrim(implementation_version) <> '' AND length(implementation_version) <= 128),
    CONSTRAINT rule_check_definitions_config_schema_version_check
        CHECK (btrim(config_schema_version) <> '' AND length(config_schema_version) <= 128),
    CONSTRAINT rule_check_definitions_severity_check
        CHECK (severity IN ('advisory', 'required', 'gate')),
    CONSTRAINT rule_check_definitions_config_object_check
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT rule_check_definitions_contract_hash_check
        CHECK (checker_contract_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rule_check_definitions_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 255),
    CONSTRAINT rule_check_definitions_ordinal_uq
        UNIQUE (rule_id, rule_version, ordinal)
);

CREATE INDEX rule_check_definitions_plan_idx
    ON rule_check_definitions (rule_id, rule_version, ordinal)
    WHERE phase = 'plan';

CREATE TRIGGER rule_check_definitions_immutable
    BEFORE UPDATE OR DELETE ON rule_check_definitions
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

-- Policy-set identity and immutable versions deliberately share one table.
-- A new membership list is a new (id, version) row; old reach never mutates.
CREATE TABLE rule_policy_sets (
    id UUID NOT NULL,
    version INTEGER NOT NULL,
    policy_set_key TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version),
    CONSTRAINT rule_policy_sets_version_check CHECK (version > 0),
    CONSTRAINT rule_policy_sets_key_check
        CHECK (btrim(policy_set_key) <> '' AND length(policy_set_key) <= 128),
    CONSTRAINT rule_policy_sets_content_hash_check
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rule_policy_sets_key_version_uq UNIQUE (policy_set_key, version)
);

CREATE TRIGGER rule_policy_sets_immutable
    BEFORE UPDATE OR DELETE ON rule_policy_sets
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE TABLE rule_policy_set_projects (
    policy_set_id UUID NOT NULL,
    policy_set_version INTEGER NOT NULL,
    project_key TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (policy_set_id, policy_set_version, project_key),
    CONSTRAINT rule_policy_set_projects_policy_set_fk
        FOREIGN KEY (policy_set_id, policy_set_version)
        REFERENCES rule_policy_sets (id, version),
    CONSTRAINT rule_policy_set_projects_project_key_check
        CHECK (btrim(project_key) <> '' AND length(project_key) <= 256),
    CONSTRAINT rule_policy_set_projects_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 999),
    CONSTRAINT rule_policy_set_projects_ordinal_uq
        UNIQUE (policy_set_id, policy_set_version, ordinal)
);

CREATE INDEX rule_policy_set_projects_project_idx
    ON rule_policy_set_projects (project_key, policy_set_id, policy_set_version);

CREATE TRIGGER rule_policy_set_projects_immutable
    BEFORE UPDATE OR DELETE ON rule_policy_set_projects
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE TABLE rule_scope_assignments (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    scope_type TEXT NOT NULL,
    project_key TEXT,
    policy_set_id UUID,
    policy_set_version INTEGER,
    relation_type TEXT,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_scope_assignments_rule_version_fk
        FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    CONSTRAINT rule_scope_assignments_policy_set_fk
        FOREIGN KEY (policy_set_id, policy_set_version)
        REFERENCES rule_policy_sets (id, version),
    CONSTRAINT rule_scope_assignments_type_check
        CHECK (scope_type IN ('global', 'project', 'project_set', 'relation')),
    -- Historical V46 definition keys were nonblank but had no length cap. Keep
    -- their exact authority during upgrade; new authoring is bounded in Java.
    CONSTRAINT rule_scope_assignments_project_key_check
        CHECK (project_key IS NULL OR btrim(project_key) <> ''),
    CONSTRAINT rule_scope_assignments_relation_type_check
        CHECK (relation_type IS NULL OR (btrim(relation_type) <> '' AND length(relation_type) <= 128)),
    CONSTRAINT rule_scope_assignments_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 255),
    CONSTRAINT rule_scope_assignments_shape_check CHECK (
        (scope_type = 'global'
            AND project_key IS NULL AND policy_set_id IS NULL AND policy_set_version IS NULL
            AND relation_type IS NULL)
        OR (scope_type = 'project'
            AND project_key IS NOT NULL AND policy_set_id IS NULL AND policy_set_version IS NULL
            AND relation_type IS NULL)
        OR (scope_type = 'project_set'
            AND project_key IS NULL AND policy_set_id IS NOT NULL AND policy_set_version IS NOT NULL
            AND relation_type IS NULL)
        OR (scope_type = 'relation'
            AND project_key IS NULL AND policy_set_id IS NULL AND policy_set_version IS NULL
            AND relation_type IS NOT NULL)
    ),
    CONSTRAINT rule_scope_assignments_ordinal_uq UNIQUE (rule_id, rule_version, ordinal),
    CONSTRAINT rule_scope_assignments_identity_uq UNIQUE (id, rule_id, rule_version),
    CONSTRAINT rule_scope_assignments_relation_type_uq
        UNIQUE (rule_id, rule_version, relation_type)
);

CREATE UNIQUE INDEX rule_scope_assignments_global_uq
    ON rule_scope_assignments (rule_id, rule_version)
    WHERE scope_type = 'global';
CREATE UNIQUE INDEX rule_scope_assignments_project_uq
    ON rule_scope_assignments (rule_id, rule_version, project_key)
    WHERE scope_type = 'project';
CREATE UNIQUE INDEX rule_scope_assignments_project_set_uq
    ON rule_scope_assignments (rule_id, rule_version, policy_set_id, policy_set_version)
    WHERE scope_type = 'project_set';

CREATE INDEX rule_scope_assignments_project_idx
    ON rule_scope_assignments (project_key, rule_id, rule_version)
    WHERE scope_type = 'project';
CREATE INDEX rule_scope_assignments_policy_set_idx
    ON rule_scope_assignments (policy_set_id, policy_set_version)
    WHERE scope_type = 'project_set';

CREATE TRIGGER rule_scope_assignments_immutable
    BEFORE UPDATE OR DELETE ON rule_scope_assignments
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

-- Pre-V47 authority had exactly one nullable project key on the definition.
-- It therefore safely maps every historical version to GLOBAL or PROJECT.
INSERT INTO rule_scope_assignments (
    id, rule_id, rule_version, scope_type, project_key, ordinal, created_at
)
SELECT md5(version.rule_id::text || ':' || version.version::text || ':scope')::uuid,
       version.rule_id,
       version.version,
       CASE WHEN definition.project_key IS NULL THEN 'global' ELSE 'project' END,
       definition.project_key,
       0,
       version.created_at
FROM rule_versions version
JOIN rule_definitions definition ON definition.id = version.rule_id
ON CONFLICT (rule_id, rule_version, ordinal) DO NOTHING;

CREATE TABLE rule_relation_endpoints (
    id UUID PRIMARY KEY,
    scope_assignment_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    relation_role TEXT NOT NULL,
    project_key TEXT NOT NULL,
    selector_group_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_relation_endpoints_scope_fk
        FOREIGN KEY (scope_assignment_id, rule_id, rule_version)
        REFERENCES rule_scope_assignments (id, rule_id, rule_version),
    CONSTRAINT rule_relation_endpoints_selector_group_fk
        FOREIGN KEY (selector_group_id, rule_id, rule_version)
        REFERENCES rule_selector_groups (id, rule_id, rule_version),
    CONSTRAINT rule_relation_endpoints_role_check
        CHECK (relation_role IN ('producer', 'consumer')),
    CONSTRAINT rule_relation_endpoints_project_key_check
        CHECK (btrim(project_key) <> '' AND length(project_key) <= 256),
    CONSTRAINT rule_relation_endpoints_ordinal_check
        CHECK (ordinal BETWEEN 0 AND 255),
    CONSTRAINT rule_relation_endpoints_ordinal_uq
        UNIQUE (scope_assignment_id, ordinal),
    CONSTRAINT rule_relation_endpoints_identity_uq
        UNIQUE (scope_assignment_id, relation_role)
);

CREATE INDEX rule_relation_endpoints_project_idx
    ON rule_relation_endpoints (project_key, relation_role, rule_id, rule_version);

CREATE TRIGGER rule_relation_endpoints_immutable
    BEFORE UPDATE OR DELETE ON rule_relation_endpoints
    FOR EACH ROW EXECUTE FUNCTION reject_rule_version_mutation();

CREATE OR REPLACE FUNCTION require_relation_endpoint_scope() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM rule_scope_assignments assignment
        WHERE assignment.id = NEW.scope_assignment_id
          AND assignment.rule_id = NEW.rule_id
          AND assignment.rule_version = NEW.rule_version
          AND assignment.scope_type = 'relation'
    ) THEN
        RAISE EXCEPTION 'relation endpoint requires a relation scope assignment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rule_relation_endpoints_scope_guard
    BEFORE INSERT ON rule_relation_endpoints
    FOR EACH ROW EXECUTE FUNCTION require_relation_endpoint_scope();

CREATE OR REPLACE FUNCTION require_complete_rule_relation_scope() RETURNS trigger AS $$
DECLARE
    producer_project TEXT;
    consumer_project TEXT;
BEGIN
    IF NEW.scope_type = 'relation' THEN
        SELECT project_key INTO producer_project
        FROM rule_relation_endpoints
        WHERE scope_assignment_id = NEW.id AND relation_role = 'producer';
        SELECT project_key INTO consumer_project
        FROM rule_relation_endpoints
        WHERE scope_assignment_id = NEW.id AND relation_role = 'consumer';
        IF producer_project IS NULL OR consumer_project IS NULL THEN
            RAISE EXCEPTION 'relation scope requires exactly one producer and one consumer endpoint';
        END IF;
        IF producer_project = consumer_project THEN
            RAISE EXCEPTION 'relation producer and consumer projects must differ';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER rule_scope_assignments_relation_complete
    AFTER INSERT ON rule_scope_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_complete_rule_relation_scope();

CREATE OR REPLACE FUNCTION require_complete_rule_scope_set() RETURNS trigger AS $$
DECLARE
    target_rule_id UUID;
    target_rule_version INTEGER;
    assignment_count INTEGER;
    scope_kind_count INTEGER;
    scope_kind TEXT;
    definition_project_key TEXT;
    assignment_project_key TEXT;
BEGIN
    target_rule_id := NEW.rule_id;
    IF TG_TABLE_NAME = 'rule_versions' THEN
        target_rule_version := NEW.version;
    ELSE
        target_rule_version := NEW.rule_version;
    END IF;

    SELECT count(*), count(DISTINCT scope_type), min(scope_type)
      INTO assignment_count, scope_kind_count, scope_kind
    FROM rule_scope_assignments
    WHERE rule_id = target_rule_id AND rule_version = target_rule_version;

    IF assignment_count = 0 THEN
        RAISE EXCEPTION 'rule version requires at least one scope assignment';
    END IF;
    IF scope_kind_count <> 1 THEN
        RAISE EXCEPTION 'rule version scope assignments must use one scope type';
    END IF;
    IF scope_kind <> 'relation' AND assignment_count <> 1 THEN
        RAISE EXCEPTION 'non-relation rule version requires exactly one scope assignment';
    END IF;

    SELECT project_key INTO definition_project_key
    FROM rule_definitions
    WHERE id = target_rule_id;
    IF scope_kind = 'project' THEN
        SELECT project_key INTO assignment_project_key
        FROM rule_scope_assignments
        WHERE rule_id = target_rule_id AND rule_version = target_rule_version;
        IF definition_project_key IS DISTINCT FROM assignment_project_key THEN
            RAISE EXCEPTION 'project scope must match rule definition project';
        END IF;
    ELSIF definition_project_key IS NOT NULL THEN
        RAISE EXCEPTION 'non-project scope requires a global rule definition projection';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Installed only after the historical backfill above, so it fences all future
-- writers without requiring synthetic pre-V47 evidence.
CREATE CONSTRAINT TRIGGER rule_versions_scope_required
    AFTER INSERT ON rule_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_complete_rule_scope_set();

CREATE CONSTRAINT TRIGGER rule_scope_assignments_set_complete
    AFTER INSERT ON rule_scope_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_complete_rule_scope_set();

CREATE TABLE rules_global_effective_seq (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    seq BIGINT NOT NULL,
    CONSTRAINT rules_global_effective_seq_nonnegative_check CHECK (seq >= 0)
);

INSERT INTO rules_global_effective_seq (singleton, seq)
SELECT true, seq FROM rules_change_seq;

CREATE TABLE rules_project_effective_seq (
    project_key TEXT PRIMARY KEY,
    seq BIGINT NOT NULL,
    -- V41 accepted arbitrary registry keys, including legacy blank/long keys.
    -- Preserve them as conservative sequence partitions; new reads/writes use
    -- the stricter application-level project-key contract.
    CONSTRAINT rules_project_effective_seq_nonnegative_check CHECK (seq >= 0)
);

-- Bootstrap every project known either to the registry or to legacy rule
-- authority from the legacy total sequence. The key intentionally has no FK:
-- historical reach remains auditable after a project root is unregistered.
INSERT INTO rules_project_effective_seq (project_key, seq)
SELECT project.project_key, change.seq
FROM (
    SELECT project_key FROM scanner_project_roots
    UNION
    SELECT project_key FROM rule_definitions WHERE project_key IS NOT NULL
) project
CROSS JOIN rules_change_seq change
ORDER BY project.project_key;

-- V43 required an exact target binding for every gate rule. V47 broadens
-- target authority to immutable selector groups while retaining fail-closed
-- targeting: applies_all alone can still never make a gate rule valid.
CREATE OR REPLACE FUNCTION require_gate_rule_target_binding() RETURNS trigger AS $$
BEGIN
    IF NEW.enforcement = 'gate'
       AND NOT EXISTS (
           SELECT 1 FROM rule_target_bindings binding
           WHERE binding.rule_id = NEW.rule_id
             AND binding.rule_version = NEW.version
       )
       AND NOT EXISTS (
           SELECT 1 FROM rule_selector_groups selector_group
           WHERE selector_group.rule_id = NEW.rule_id
             AND selector_group.rule_version = NEW.version
       ) THEN
        RAISE EXCEPTION 'gate rule version requires at least one target binding or selector group';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
