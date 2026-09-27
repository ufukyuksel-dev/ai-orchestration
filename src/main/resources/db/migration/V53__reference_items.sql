CREATE TABLE reference_items (
    id UUID PRIMARY KEY,
    relative_path TEXT NOT NULL UNIQUE,
    parent_path TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('file', 'directory')),
    content_hash TEXT,
    status TEXT NOT NULL CHECK (status IN ('current', 'unverified', 'stale', 'missing')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (octet_length(relative_path) BETWEEN 1 AND 1024),
    CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'),
    CHECK (kind <> 'directory' OR content_hash IS NULL)
);
CREATE INDEX reference_items_parent_path_idx ON reference_items(parent_path, relative_path);
