ALTER TABLE memory_events DROP CONSTRAINT memory_events_type_check;

ALTER TABLE memory_events
    ADD CONSTRAINT memory_events_type_check CHECK (event_type IN (
        'created', 'updated', 'status_changed', 'retrieved', 'accepted', 'rejected',
        'promoted', 'archived', 'conflict_detected', 'auto_curated', 'shadow_logged',
        'curation_skipped', 'relation_judged'
    ));
