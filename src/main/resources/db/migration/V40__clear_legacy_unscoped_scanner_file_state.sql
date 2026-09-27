-- V39 had no reliable source project for pre-existing scanner state rows, so it
-- assigned them to AI_ORCHESTRATION while introducing the composite key. Clear
-- only that legacy bucket once; rows written with an explicit external project
-- key after V39 remain intact and subsequent scans rebuild the local cache.
DELETE FROM scanner_file_state
WHERE project_key = 'AI_ORCHESTRATION';
