-- memory.pending is a project-scoped non-admin discovery tool for pending approval cards.
-- Existing human-confirm-capable write keys and admin keys should keep that ability after
-- the tool is split from memory.human_confirmed_approve.
UPDATE mcp_api_keys
SET scopes = array_append(scopes, 'memory.pending')
WHERE revoked_at IS NULL
  AND (
      scopes @> ARRAY['memory.human_confirmed_approve']::text[]
      OR scopes @> ARRAY['memory.admin']::text[]
  )
  AND NOT scopes @> ARRAY['memory.pending']::text[];
