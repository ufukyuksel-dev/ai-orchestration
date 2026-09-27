CREATE TABLE research_context_references (
    context_id UUID NOT NULL REFERENCES research_contexts(id) ON DELETE CASCADE,
    target_id TEXT NOT NULL,
    reference_id UUID NOT NULL REFERENCES reference_items(id) ON DELETE RESTRICT,
    relative_path TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    section_key TEXT NOT NULL,
    source_memory_id UUID NOT NULL REFERENCES memory_items(id) ON DELETE RESTRICT,
    PRIMARY KEY (context_id, target_id),
    UNIQUE (context_id, reference_id, section_key),
    CHECK (target_id ~ '^r[1-9][0-9]*$'),
    CHECK (char_length(section_key) BETWEEN 1 AND 1024),
    CHECK (section_key !~ '[[:cntrl:]]')
);

CREATE INDEX research_context_references_reference_idx
    ON research_context_references(reference_id, content_hash);
