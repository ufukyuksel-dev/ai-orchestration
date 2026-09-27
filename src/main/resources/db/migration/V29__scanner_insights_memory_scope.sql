-- Scanner flow insight memory is a narrower write surface than scanner.scan.
-- Existing scanner-capable write/admin keys get the explicit scope; read-only keys do not.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'scanner.insights.write-memory')
WHERE scopes @> ARRAY['scanner.scan']::text[]
  AND (
      scopes @> ARRAY['memory.write']::text[]
      OR scopes @> ARRAY['memory.admin']::text[]
  )
  AND NOT scopes @> ARRAY['scanner.insights.write-memory']::text[];
