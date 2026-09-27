UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'context.graph.read')
WHERE scopes @> ARRAY['memory.read', 'codebase.read']::text[]
  AND NOT scopes @> ARRAY['context.graph.read']::text[];
