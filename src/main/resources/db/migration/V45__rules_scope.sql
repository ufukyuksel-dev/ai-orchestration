-- Carry rule capabilities forward for existing active keys without rotating
-- credentials. The grants mirror the closest existing memory capability.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'rules.read')
WHERE revoked_at IS NULL
  AND scopes @> ARRAY['memory.read']::text[]
  AND NOT scopes @> ARRAY['rules.read']::text[];

UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'rules.write')
WHERE revoked_at IS NULL
  AND scopes @> ARRAY['memory.write']::text[]
  AND NOT scopes @> ARRAY['rules.write']::text[];

UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'rules.confirm')
WHERE revoked_at IS NULL
  AND (
      scopes @> ARRAY['memory.human_confirmed_approve']::text[]
      OR scopes @> ARRAY['memory.admin']::text[]
  )
  AND NOT scopes @> ARRAY['rules.confirm']::text[];

-- In the supported local-first profile this is an explicit effect-awareness
-- scope, not an additional access wall. Existing confirm-capable local/admin
-- keys therefore carry it alongside rules.confirm.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'rules.global.confirm')
WHERE revoked_at IS NULL
  AND (
      scopes @> ARRAY['memory.human_confirmed_approve']::text[]
      OR scopes @> ARRAY['memory.admin']::text[]
  )
  AND NOT scopes @> ARRAY['rules.global.confirm']::text[];
