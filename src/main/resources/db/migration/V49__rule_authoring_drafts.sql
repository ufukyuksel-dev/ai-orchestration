-- Immutable direct-human rule drafts and their one-way promotion binding.

CREATE TABLE rule_authoring_drafts (
    id UUID PRIMARY KEY,
    project_key TEXT,
    candidate_json JSONB NOT NULL,
    candidate_hash TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    promoted_rule_id UUID,
    promoted_rule_version INTEGER,
    promoted_approval_hash TEXT,
    promoted_request_hash TEXT,
    promoted_at TIMESTAMPTZ,
    CONSTRAINT rule_authoring_drafts_project_key_check
        CHECK (project_key IS NULL OR (btrim(project_key) <> '' AND length(project_key) <= 256)),
    CONSTRAINT rule_authoring_drafts_candidate_object_check
        CHECK (jsonb_typeof(candidate_json) = 'object'),
    CONSTRAINT rule_authoring_drafts_candidate_hash_check
        CHECK (candidate_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rule_authoring_drafts_created_by_check
        CHECK (btrim(created_by) <> '' AND length(created_by) <= 256),
    CONSTRAINT rule_authoring_drafts_promotion_shape_check CHECK (
        (promoted_rule_id IS NULL
            AND promoted_rule_version IS NULL
            AND promoted_approval_hash IS NULL
            AND promoted_request_hash IS NULL
            AND promoted_at IS NULL)
        OR (promoted_rule_id IS NOT NULL
            AND promoted_rule_version > 0
            AND promoted_approval_hash ~ '^[0-9a-f]{64}$'
            AND promoted_request_hash ~ '^[0-9a-f]{64}$'
            AND promoted_at IS NOT NULL)
    ),
    CONSTRAINT rule_authoring_drafts_promoted_version_fk
        FOREIGN KEY (promoted_rule_id, promoted_rule_version)
        REFERENCES rule_versions (rule_id, version)
);

CREATE INDEX rule_authoring_drafts_project_created_idx
    ON rule_authoring_drafts (project_key, created_at DESC);

CREATE OR REPLACE FUNCTION guard_rule_authoring_draft_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'rule authoring drafts are immutable';
    END IF;

    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.project_key IS DISTINCT FROM NEW.project_key
       OR OLD.candidate_json IS DISTINCT FROM NEW.candidate_json
       OR OLD.candidate_hash IS DISTINCT FROM NEW.candidate_hash
       OR OLD.created_by IS DISTINCT FROM NEW.created_by
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'rule authoring draft content is immutable';
    END IF;

    IF OLD.promoted_rule_id IS NOT NULL
       OR NEW.promoted_rule_id IS NULL
       OR NEW.promoted_rule_version IS NULL
       OR NEW.promoted_approval_hash IS NULL
       OR NEW.promoted_request_hash IS NULL
       OR NEW.promoted_at IS NULL THEN
        RAISE EXCEPTION 'rule authoring draft promotion binding is one-way';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rule_authoring_drafts_guard
    BEFORE UPDATE OR DELETE ON rule_authoring_drafts
    FOR EACH ROW EXECUTE FUNCTION guard_rule_authoring_draft_mutation();
