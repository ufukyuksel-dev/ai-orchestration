CREATE TABLE IF NOT EXISTS code_scan_runs (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    root_path TEXT NOT NULL,
    provider TEXT NOT NULL,
    semantic_model TEXT,
    prompt_version TEXT NOT NULL,
    data_egress BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    status TEXT NOT NULL,
    files_discovered INTEGER NOT NULL DEFAULT 0,
    files_scanned INTEGER NOT NULL DEFAULT 0,
    files_skipped INTEGER NOT NULL DEFAULT 0,
    files_rejected INTEGER NOT NULL DEFAULT 0,
    candidates_created INTEGER NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT code_scan_runs_status_check
        CHECK (status IN ('running', 'completed', 'failed'))
);

CREATE INDEX IF NOT EXISTS code_scan_runs_project_started_idx
    ON code_scan_runs (project_key, started_at DESC);

CREATE TABLE IF NOT EXISTS code_files (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    file_path TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    language TEXT NOT NULL,
    last_scan_run_id UUID REFERENCES code_scan_runs(id) ON DELETE SET NULL,
    last_scanned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (project_key, file_path)
);

CREATE INDEX IF NOT EXISTS code_files_project_path_idx
    ON code_files (project_key, file_path);

CREATE TABLE IF NOT EXISTS code_symbols (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    file_id UUID NOT NULL REFERENCES code_files(id) ON DELETE CASCADE,
    symbol_kind TEXT NOT NULL,
    name TEXT NOT NULL,
    fqn TEXT,
    signature TEXT NOT NULL DEFAULT '',
    role TEXT,
    start_line INTEGER,
    end_line INTEGER,
    content_hash TEXT NOT NULL,
    last_scan_run_id UUID REFERENCES code_scan_runs(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (project_key, file_id, symbol_kind, name, signature)
);

CREATE INDEX IF NOT EXISTS code_symbols_project_name_idx
    ON code_symbols (project_key, name);

CREATE INDEX IF NOT EXISTS code_symbols_project_kind_idx
    ON code_symbols (project_key, symbol_kind);

CREATE TABLE IF NOT EXISTS code_edges (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    source_symbol_id UUID NOT NULL REFERENCES code_symbols(id) ON DELETE CASCADE,
    target_symbol_id UUID REFERENCES code_symbols(id) ON DELETE CASCADE,
    target_ref TEXT NOT NULL DEFAULT '',
    edge_type TEXT NOT NULL,
    resolution TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_scan_run_id UUID REFERENCES code_scan_runs(id) ON DELETE SET NULL,
    UNIQUE (project_key, source_symbol_id, edge_type, target_ref)
);

CREATE INDEX IF NOT EXISTS code_edges_source_idx
    ON code_edges (source_symbol_id, edge_type);

CREATE INDEX IF NOT EXISTS code_edges_target_ref_idx
    ON code_edges (project_key, target_ref);

CREATE TABLE IF NOT EXISTS code_semantic_capsules (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    symbol_id UUID REFERENCES code_symbols(id) ON DELETE CASCADE,
    file_id UUID REFERENCES code_files(id) ON DELETE CASCADE,
    capsule_kind TEXT NOT NULL,
    summary TEXT NOT NULL,
    text TEXT NOT NULL,
    provider TEXT NOT NULL,
    semantic_model TEXT NOT NULL DEFAULT '',
    target_key TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    summarizer_input_hash TEXT NOT NULL,
    output_hash TEXT NOT NULL,
    data_egress BOOLEAN NOT NULL DEFAULT false,
    confidence DOUBLE PRECISION NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_scan_run_id UUID REFERENCES code_scan_runs(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_key, target_key, capsule_kind, provider, semantic_model, prompt_version, summarizer_input_hash)
);

CREATE INDEX IF NOT EXISTS code_semantic_capsules_project_updated_idx
    ON code_semantic_capsules (project_key, updated_at DESC);

CREATE TABLE IF NOT EXISTS code_diagnostics (
    id UUID PRIMARY KEY,
    project_key TEXT NOT NULL,
    scan_run_id UUID REFERENCES code_scan_runs(id) ON DELETE CASCADE,
    severity TEXT NOT NULL,
    code TEXT NOT NULL,
    message TEXT NOT NULL,
    file_path TEXT,
    symbol_id UUID REFERENCES code_symbols(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS code_diagnostics_project_created_idx
    ON code_diagnostics (project_key, created_at DESC);
