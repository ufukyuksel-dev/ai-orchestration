CREATE TABLE semantic_anchors (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    anchor_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    source_node_kind TEXT NOT NULL,
    source_node_key TEXT NOT NULL,
    locator TEXT,
    name TEXT,
    meaning TEXT,
    meaning_hash TEXT,
    input_hash TEXT,
    provider TEXT,
    model TEXT,
    prompt_version TEXT,
    embedding_id UUID,
    salience DOUBLE PRECISION,
    stale BOOLEAN NOT NULL DEFAULT false,
    last_semantic_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_key, anchor_id),
    UNIQUE (project_key, source_node_kind, source_node_key),
    CONSTRAINT semantic_anchors_kind_check CHECK (kind IN ('capsule','memory')),
    CONSTRAINT semantic_anchors_source_kind_check CHECK (source_node_kind IN ('CodeCapsule','Memory'))
);

CREATE INDEX semantic_anchors_project_kind_idx ON semantic_anchors (project_key, kind);
CREATE INDEX semantic_anchors_source_idx ON semantic_anchors (project_key, source_node_kind, source_node_key);
CREATE INDEX semantic_anchors_project_stale_idx ON semantic_anchors (project_key, stale, anchor_id);
