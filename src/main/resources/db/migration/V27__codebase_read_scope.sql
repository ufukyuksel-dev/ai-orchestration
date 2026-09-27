UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'codebase.read')
WHERE revoked_at IS NULL
  AND scopes @> ARRAY['memory.read']::text[]
  AND NOT scopes @> ARRAY['codebase.read']::text[];
