-- Loopback refusals under local trust are audited as denied_localhost (McpAuthenticationFilter). The
-- original constraint did not admit that value, so those security-relevant rows failed to insert.
ALTER TABLE mcp_access_log DROP CONSTRAINT mcp_access_log_decision_check;
ALTER TABLE mcp_access_log ADD CONSTRAINT mcp_access_log_decision_check
    CHECK (decision IN ('success', 'denied_auth', 'denied_scope', 'denied_localhost', 'error'));
