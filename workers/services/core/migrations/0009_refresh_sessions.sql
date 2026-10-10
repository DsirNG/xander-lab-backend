CREATE TABLE IF NOT EXISTS auth_refresh_session (
  sid TEXT PRIMARY KEY,
  account_id INTEGER NOT NULL,
  family_id TEXT NOT NULL,
  current_token_id TEXT NOT NULL UNIQUE,
  current_token_hash TEXT NOT NULL,
  generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
  issued_at TEXT NOT NULL,
  last_rotated_at TEXT,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  revoked_reason TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_refresh_account_status
  ON auth_refresh_session(account_id, status);
CREATE INDEX IF NOT EXISTS idx_refresh_family
  ON auth_refresh_session(family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_expires
  ON auth_refresh_session(expires_at);

CREATE TABLE IF NOT EXISTS auth_refresh_token_history (
  token_id TEXT PRIMARY KEY,
  sid TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  generation INTEGER NOT NULL,
  used_at TEXT NOT NULL,
  successor_token_id TEXT,
  grace_expires_at TEXT NOT NULL,
  retained_until TEXT NOT NULL,
  reuse_detected_at TEXT,
  successor_token_ciphertext TEXT,
  successor_ciphertext_expires_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_refresh_history_sid_generation
  ON auth_refresh_token_history(sid, generation);
CREATE INDEX IF NOT EXISTS idx_refresh_history_retained_until
  ON auth_refresh_token_history(retained_until);
CREATE INDEX IF NOT EXISTS idx_refresh_history_ciphertext_expires
  ON auth_refresh_token_history(successor_ciphertext_expires_at);

CREATE TABLE IF NOT EXISTS auth_refresh_reuse_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL,
  sid TEXT NOT NULL,
  token_id TEXT NOT NULL UNIQUE,
  occurred_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_reuse_account_time
  ON auth_refresh_reuse_event(account_id, occurred_at DESC);
