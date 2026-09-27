ALTER TABLE memory_items DROP CONSTRAINT IF EXISTS memory_items_source_type_check;

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_source_type_check CHECK (source_type IN (
        'manual',
        'correction_signal',
        'approval_signal',
        'session_summary',
        'promotion',
        'scanned'
    ));
