ALTER TABLE memory_items DROP CONSTRAINT IF EXISTS memory_items_source_type_check;

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_source_type_check CHECK (source_type IN (
        'manual',
        'correction_signal',
        'approval_signal',
        'session_summary',
        'promotion',
        'scanned',
        'auto_curated'
    ));

ALTER TABLE memory_events DROP CONSTRAINT IF EXISTS memory_events_type_check;

ALTER TABLE memory_events
    ADD CONSTRAINT memory_events_type_check CHECK (event_type IN (
        'created',
        'updated',
        'status_changed',
        'retrieved',
        'accepted',
        'rejected',
        'promoted',
        'archived',
        'conflict_detected',
        'auto_curated',
        'shadow_logged',
        'curation_skipped'
    ));

CREATE TABLE IF NOT EXISTS memory_curation_calibration_log (
    id UUID PRIMARY KEY,
    transcript_queue_id BIGINT NOT NULL,
    project_key TEXT NOT NULL,
    source TEXT NOT NULL,
    is_external_source BOOLEAN NOT NULL,
    candidate_count INTEGER NOT NULL,
    auto_approved_count INTEGER NOT NULL DEFAULT 0,
    pending_review_count INTEGER NOT NULL DEFAULT 0,
    discarded_count INTEGER NOT NULL DEFAULT 0,
    hypothetical_approved_count INTEGER NOT NULL DEFAULT 0,
    confidence_min DECIMAL(4,3),
    confidence_max DECIMAL(4,3),
    confidence_avg DECIMAL(4,3),
    pii_blocked_count INTEGER NOT NULL DEFAULT 0,
    policy_blocked_count INTEGER NOT NULL DEFAULT 0,
    dedupe_skipped_count INTEGER NOT NULL DEFAULT 0,
    shadow_mode BOOLEAN NOT NULL,
    llm_provider TEXT,
    llm_latency_ms INTEGER,
    error_class TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX memory_curation_calibration_project_created_idx
    ON memory_curation_calibration_log (project_key, created_at DESC);

CREATE INDEX memory_curation_calibration_source_idx
    ON memory_curation_calibration_log (is_external_source, source, created_at DESC);

ALTER TABLE transcript_capture_queue
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

CREATE INDEX transcript_queue_lock_idx
    ON transcript_capture_queue (locked_until)
    WHERE processed = false;
