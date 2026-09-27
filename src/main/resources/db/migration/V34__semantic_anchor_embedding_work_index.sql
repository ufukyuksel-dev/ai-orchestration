CREATE INDEX IF NOT EXISTS semantic_anchors_embedding_work_idx
ON semantic_anchors (project_key, stale, anchor_id)
WHERE meaning IS NOT NULL;
