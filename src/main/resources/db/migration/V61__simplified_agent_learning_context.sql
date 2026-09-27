ALTER TABLE research_contexts
    ADD COLUMN session_scope_hash CHAR(64) NOT NULL DEFAULT repeat('0', 64),
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN superseded_at TIMESTAMPTZ;

CREATE INDEX research_contexts_active_scope_idx
    ON research_contexts (principal_key, project_key, client_id, session_scope_hash, created_at DESC)
    WHERE active = TRUE;

CREATE TABLE research_context_knowledge (
    context_id UUID NOT NULL REFERENCES research_contexts(id) ON DELETE CASCADE,
    knowledge_ref TEXT NOT NULL,
    memory_id UUID NOT NULL REFERENCES memory_items(id) ON DELETE RESTRICT,
    learning_revision BIGINT NOT NULL,
    canonical_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (context_id, knowledge_ref),
    UNIQUE (context_id, memory_id),
    CHECK (knowledge_ref ~ '^k[1-9][0-9]*$'),
    CHECK (learning_revision > 0)
);

ALTER TABLE learning_operations
    ADD COLUMN session_scope_hash CHAR(64) NOT NULL DEFAULT repeat('0', 64),
    ADD COLUMN operation_key CHAR(64);

CREATE UNIQUE INDEX learning_operations_operation_key_idx
    ON learning_operations (operation_key) WHERE operation_key IS NOT NULL;

CREATE INDEX learning_operations_uncertain_scope_idx
    ON learning_operations (principal_key, project_key, session_scope_hash, updated_at DESC)
    WHERE operation_key IS NOT NULL;

ALTER TABLE memory_learning_profiles
    ADD COLUMN semantic_identity CHAR(64),
    ADD COLUMN content_revision CHAR(64),
    ADD COLUMN evidence_revision CHAR(64);

UPDATE memory_learning_profiles
SET semantic_identity = canonical_hash,
    content_revision = canonical_hash,
    evidence_revision = canonical_hash
WHERE semantic_identity IS NULL;

ALTER TABLE memory_learning_profiles
    ALTER COLUMN semantic_identity SET NOT NULL,
    ALTER COLUMN content_revision SET NOT NULL,
    ALTER COLUMN evidence_revision SET NOT NULL;

CREATE UNIQUE INDEX memory_learning_profiles_semantic_identity_idx
    ON memory_learning_profiles (project_key, semantic_identity);

ALTER TABLE memory_learning_profiles DROP CONSTRAINT memory_learning_profiles_usefulness_state_check;
ALTER TABLE memory_learning_profiles ADD CONSTRAINT memory_learning_profiles_usefulness_state_check
    CHECK (usefulness_state IN ('ACTIVE','STALE','ARCHIVED','STALE_EVIDENCE','SUPERSEDED','INVALID'));

ALTER TABLE research_observations DROP CONSTRAINT research_observations_operation_kind_check;
ALTER TABLE research_observations ADD CONSTRAINT research_observations_operation_kind_check
    CHECK (operation_kind IN ('source_read','source_register'));
