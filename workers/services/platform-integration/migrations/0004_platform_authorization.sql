-- Platform Integration stores browser authorization leases and a bounded FIFO queue in its D1.
CREATE TABLE IF NOT EXISTS platform_authorization_slot (
  platform TEXT PRIMARY KEY CHECK (platform IN ('csdn', 'juejin')),
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  encrypted_session_id TEXT,
  starting_at TEXT,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (platform, user_id)
);

CREATE TABLE IF NOT EXISTS platform_authorization_queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  platform TEXT NOT NULL CHECK (platform IN ('csdn', 'juejin')),
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (platform, user_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_authorization_queue_order
  ON platform_authorization_queue(platform, id, expires_at);
