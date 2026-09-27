ALTER TABLE memory_items DROP CONSTRAINT IF EXISTS memory_items_scope_check;
ALTER TABLE memory_items DROP CONSTRAINT IF EXISTS memory_items_project_scope_key_check;

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_scope_check CHECK (scope IN ('global', 'project', 'episodic', 'user'));

ALTER TABLE memory_items
    ADD CONSTRAINT memory_items_project_scope_key_check CHECK (
        scope NOT IN ('project', 'user') OR project_key IS NOT NULL
    );

CREATE TABLE IF NOT EXISTS user_preferences (
    user_id TEXT PRIMARY KEY,
    opted_in BOOLEAN NOT NULL DEFAULT false,
    preference_memory_id UUID REFERENCES memory_items(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    purged_at TIMESTAMPTZ,
    CONSTRAINT user_preferences_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS user_preferences_opted_in_idx ON user_preferences (opted_in, updated_at DESC);
