ALTER TABLE research_context_targets
    ADD COLUMN locator_kind TEXT NOT NULL DEFAULT 'file';

ALTER TABLE research_context_targets
    ADD CONSTRAINT research_context_targets_locator_kind_check
    CHECK (locator_kind IN ('symbol', 'file', 'directory'));

ALTER TABLE research_context_targets
    DROP CONSTRAINT research_context_targets_context_id_relative_path_key;

CREATE UNIQUE INDEX research_context_targets_context_path_symbol_uq
    ON research_context_targets (context_id, relative_path, COALESCE(symbol_ref, ''));

ALTER TABLE memory_navigation_anchors
    ADD COLUMN locator_kind TEXT NOT NULL DEFAULT 'file';

ALTER TABLE memory_navigation_anchors
    ADD CONSTRAINT memory_navigation_anchors_locator_kind_check
    CHECK (locator_kind IN ('symbol', 'file', 'directory'));
