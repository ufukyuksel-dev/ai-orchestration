ALTER TABLE memory_items DROP CONSTRAINT memory_items_type_check;

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_type_check CHECK (
        memory_type IN ('rule', 'preference', 'correction', 'decision', 'anti_pattern', 'discovery')
    );
