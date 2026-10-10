-- Core-owned SHA-256 fingerprints for access tokens explicitly logged out.
-- Raw bearer tokens are never persisted; rows expire with the token itself.
CREATE TABLE auth_token_revocation (
  -- SHA-256 over the exact bearer token bytes.
  token_hash TEXT PRIMARY KEY CHECK (length(token_hash) = 64),
  -- Unix epoch milliseconds when the access token naturally expires.
  expires_at INTEGER NOT NULL,
  -- Unix epoch milliseconds when logout first revoked this token.
  revoked_at INTEGER NOT NULL
);

CREATE INDEX idx_auth_token_revocation_expires
  ON auth_token_revocation (expires_at);
