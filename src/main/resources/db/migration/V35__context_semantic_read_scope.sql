UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'context.semantic.read')
WHERE scopes @> ARRAY['memory.read', 'codebase.read']::text[]
  AND NOT scopes @> ARRAY['context.semantic.read']::text[];
