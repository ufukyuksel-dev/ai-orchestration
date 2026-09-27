ALTER TABLE memory_relations
    ADD COLUMN reference_section_key TEXT,
    ADD COLUMN reference_content_hash CHAR(64);

ALTER TABLE memory_relations
    ADD CONSTRAINT memory_relations_reference_section_pair_ck
        CHECK ((reference_section_key IS NULL) = (reference_content_hash IS NULL)),
    ADD CONSTRAINT memory_relations_reference_section_target_ck
        CHECK (reference_section_key IS NULL OR reference_id IS NOT NULL),
    ADD CONSTRAINT memory_relations_reference_section_key_ck
        CHECK (reference_section_key IS NULL OR
               (char_length(reference_section_key) BETWEEN 1 AND 1024
                AND reference_section_key !~ '[[:cntrl:]]')),
    ADD CONSTRAINT memory_relations_reference_content_hash_ck
        CHECK (reference_content_hash IS NULL OR reference_content_hash ~ '^[0-9a-f]{64}$');
