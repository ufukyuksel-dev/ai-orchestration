CREATE TABLE memory_relations (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL CHECK (btrim(project_key) <> ''),
    source_memory_id UUID NOT NULL REFERENCES memory_items(id) ON DELETE CASCADE,
    target_memory_id UUID REFERENCES memory_items(id) ON DELETE CASCADE,
    reference_id UUID REFERENCES reference_items(id) ON DELETE CASCADE,
    relationship_type TEXT NOT NULL CHECK (relationship_type IN
        ('related_to', 'extends', 'depends_on', 'alternative_to', 'causes', 'references')),
    provenance TEXT NOT NULL CHECK (provenance IN ('explicit', 'judge')),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    explanation TEXT NOT NULL CHECK (char_length(explanation) BETWEEN 1 AND 2048),
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    target_hash TEXT CHECK (target_hash ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('active', 'stale')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((target_memory_id IS NULL) <> (reference_id IS NULL)),
    CHECK ((reference_id IS NOT NULL) = (relationship_type = 'references')),
    CHECK (target_memory_id IS NULL OR source_memory_id <> target_memory_id),
    CHECK (target_memory_id IS NULL OR target_hash IS NOT NULL),
    CHECK (relationship_type NOT IN ('related_to', 'alternative_to') OR source_memory_id < target_memory_id)
);

CREATE UNIQUE INDEX memory_relations_memory_identity_idx
    ON memory_relations(source_memory_id, target_memory_id, relationship_type)
    WHERE target_memory_id IS NOT NULL;
CREATE UNIQUE INDEX memory_relations_reference_identity_idx
    ON memory_relations(source_memory_id, reference_id, relationship_type)
    WHERE reference_id IS NOT NULL;
CREATE INDEX memory_relations_project_source_idx ON memory_relations(project_key, source_memory_id);
CREATE INDEX memory_relations_project_target_idx ON memory_relations(project_key, target_memory_id);
CREATE INDEX memory_relations_project_reference_idx ON memory_relations(project_key, reference_id);
