-- Grant memory.delete to existing MCP write/admin keys without rotating keys.
-- Read-only keys stay read-only because they do not carry memory.write.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'memory.delete')
WHERE revoked_at IS NULL
  AND scopes @> ARRAY['memory.write']::text[]
  AND NOT scopes @> ARRAY['memory.delete']::text[];
