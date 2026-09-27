CREATE TABLE memory_review_queue (
    id UUID PRIMARY KEY,
    candidate_memory_id UUID NOT NULL REFERENCES memory_items(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    status TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT memory_review_queue_reason_check CHECK (reason IN (
        'promotion_candidate',
        'conflict',
        'pollution_risk',
        'low_confidence'
    )),
    CONSTRAINT memory_review_queue_status_check CHECK (status IN (
        'open',
        'approved',
        'rejected',
        'needs_edit'
    )),
    CONSTRAINT memory_review_queue_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX memory_review_queue_status_created_at_idx
    ON memory_review_queue (status, created_at);

CREATE INDEX memory_review_queue_candidate_memory_id_idx
    ON memory_review_queue (candidate_memory_id);
