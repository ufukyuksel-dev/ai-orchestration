ALTER TABLE code_scan_runs
    DROP CONSTRAINT IF EXISTS code_scan_runs_status_check;

ALTER TABLE code_scan_runs
    ADD CONSTRAINT code_scan_runs_status_check
        CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled'));
