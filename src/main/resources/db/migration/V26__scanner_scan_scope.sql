-- scanner.scan writes code baseline and scanner memory facts, so grant it to existing
-- active keys that already have write/admin style repository-ingest capability.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'scanner.scan')
WHERE revoked_at IS NULL
  AND (
      scopes @> ARRAY['knowledge.ingest']::text[]
      OR scopes @> ARRAY['memory.admin']::text[]
  )
  AND NOT scopes @> ARRAY['scanner.scan']::text[];
