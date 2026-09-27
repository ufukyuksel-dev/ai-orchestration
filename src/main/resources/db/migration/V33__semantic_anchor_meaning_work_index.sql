CREATE INDEX IF NOT EXISTS semantic_anchors_meaning_work_idx
ON semantic_anchors (project_key, stale, kind, anchor_id);
