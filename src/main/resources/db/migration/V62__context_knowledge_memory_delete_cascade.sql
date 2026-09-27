ALTER TABLE research_context_knowledge
    DROP CONSTRAINT research_context_knowledge_memory_id_fkey;

ALTER TABLE research_context_knowledge
    ADD CONSTRAINT research_context_knowledge_memory_id_fkey
    FOREIGN KEY (memory_id) REFERENCES memory_items(id) ON DELETE CASCADE;
