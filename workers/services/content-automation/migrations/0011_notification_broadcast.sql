CREATE TABLE IF NOT EXISTS content_notification_stream (
  connection_id TEXT PRIMARY KEY,
  owner_id INTEGER NOT NULL,
  last_seen_at_ms INTEGER NOT NULL,
  lease_until_ms INTEGER NOT NULL,
  last_broadcast_id INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_content_notification_stream_lease
  ON content_notification_stream(lease_until_ms);

CREATE TABLE IF NOT EXISTS content_notification_broadcast (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL CHECK (length(type) <= 48),
  title TEXT NOT NULL CHECK (length(title) <= 200),
  message TEXT NOT NULL CHECK (length(message) <= 1000),
  created_at_ms INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_content_notification_broadcast_expiry
  ON content_notification_broadcast(expires_at_ms);
