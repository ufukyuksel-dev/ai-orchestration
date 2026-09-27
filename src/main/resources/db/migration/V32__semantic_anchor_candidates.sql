ALTER TABLE semantic_anchors DROP CONSTRAINT semantic_anchors_kind_check;

ALTER TABLE semantic_anchors
    ADD CONSTRAINT semantic_anchors_kind_check
    CHECK (kind IN ('capsule','memory','symbol','endpoint'));

ALTER TABLE semantic_anchors DROP CONSTRAINT semantic_anchors_source_kind_check;

ALTER TABLE semantic_anchors
    ADD CONSTRAINT semantic_anchors_source_kind_check
    CHECK (source_node_kind IN ('CodeCapsule','Memory','CodeSymbol','CodeReference'));
