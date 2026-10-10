-- Platform Integration owns dynamic MCP OAuth clients, user grants and hash-only bearer token state.
CREATE TABLE IF NOT EXISTS mcp_oauth_client (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL UNIQUE,
  client_name TEXT NOT NULL,
  redirect_uris TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'DISABLED')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS mcp_oauth_grant (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  client_id TEXT NOT NULL,
  scopes TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
  granted_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (user_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_grant_user_status
  ON mcp_oauth_grant(user_id, status, granted_at DESC);
CREATE INDEX IF NOT EXISTS idx_mcp_oauth_grant_client_status
  ON mcp_oauth_grant(client_id, status);

CREATE TABLE IF NOT EXISTS mcp_oauth_token (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  grant_id INTEGER NOT NULL,
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 43),
  token_type TEXT NOT NULL CHECK (token_type IN ('ACCESS', 'REFRESH')),
  token_family_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
  expires_at TEXT NOT NULL,
  used_at TEXT,
  revoked_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_token_grant_status
  ON mcp_oauth_token(grant_id, status);
CREATE INDEX IF NOT EXISTS idx_mcp_oauth_token_expiry
  ON mcp_oauth_token(expires_at);
CREATE INDEX IF NOT EXISTS idx_mcp_oauth_token_family
  ON mcp_oauth_token(token_family_id, status);

CREATE TABLE IF NOT EXISTS mcp_oauth_ephemeral_state (
  state_id TEXT PRIMARY KEY,
  state_type TEXT NOT NULL CHECK (state_type IN ('AUTHORIZATION_REQUEST', 'AUTHORIZATION_CODE')),
  payload TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_ephemeral_expiry
  ON mcp_oauth_ephemeral_state(expires_at);

CREATE TABLE IF NOT EXISTS mcp_oauth_rate_limit (
  bucket TEXT NOT NULL,
  discriminator_hash TEXT NOT NULL,
  request_count INTEGER NOT NULL CHECK (request_count > 0),
  window_started_at TEXT NOT NULL,
  window_expires_at TEXT NOT NULL,
  PRIMARY KEY (bucket, discriminator_hash)
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_rate_limit_expiry
  ON mcp_oauth_rate_limit(window_expires_at);
