CREATE TABLE scanner_project_roots (
    project_key TEXT PRIMARY KEY,
    root_path TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Existing installations may predate root-derived project keys. Bind every
-- project to its most recently completed root; the scanner guard will remove
-- facts left behind by older, foreign roots on the next scan.
INSERT INTO scanner_project_roots (project_key, root_path, first_seen_at, last_seen_at)
SELECT DISTINCT ON (project_key)
       project_key,
       root_path,
       started_at,
       COALESCE(completed_at, started_at)
FROM code_scan_runs
WHERE status = 'completed'
ORDER BY project_key, COALESCE(completed_at, started_at) DESC, started_at DESC
ON CONFLICT (project_key) DO NOTHING;

CREATE INDEX scanner_project_roots_root_path_idx
    ON scanner_project_roots (root_path);
