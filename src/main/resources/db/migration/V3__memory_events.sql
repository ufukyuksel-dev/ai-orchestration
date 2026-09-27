CREATE TABLE memory_events (
    id UUID PRIMARY KEY,
    memory_id UUID NOT NULL REFERENCES memory_items(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memory_events_type_check CHECK (event_type IN (
        'created',
        'status_changed',
        'retrieved',
        'accepted',
        'rejected',
        'promoted',
        'archived',
        'conflict_detected'
    )),
    CONSTRAINT memory_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX memory_events_memory_id_created_at_idx
    ON memory_events (memory_id, created_at DESC);

CREATE INDEX memory_events_type_created_at_idx
    ON memory_events (event_type, created_at DESC);
