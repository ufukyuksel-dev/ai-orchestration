-- One local-user handoff, deliberately separate from semantic memory/history.
CREATE TABLE last_job (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    content text NOT NULL CHECK (length(btrim(content)) > 0 AND octet_length(content) <= 262144),
    updated_at timestamptz NOT NULL DEFAULT now()
);
