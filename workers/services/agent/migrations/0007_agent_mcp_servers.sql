CREATE TABLE agent_mcp_server (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_user_id INTEGER NOT NULL,
  server_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  endpoint_url TEXT NOT NULL,
  headers_encrypted TEXT,
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  tools_json TEXT NOT NULL DEFAULT '[]',
  last_status TEXT CHECK (last_status IS NULL OR last_status IN ('OK', 'FAILED')),
  last_error TEXT,
  last_connected_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (owner_user_id, server_key)
);

CREATE INDEX idx_agent_mcp_server_owner_key ON agent_mcp_server (owner_user_id, server_key);
CREATE INDEX idx_agent_mcp_server_enabled_owner ON agent_mcp_server (enabled, owner_user_id);
