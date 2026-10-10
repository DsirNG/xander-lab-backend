-- Platform Integration owns upload metadata; file bytes stay in Alibaba OSS.
CREATE TABLE IF NOT EXISTS platform_file_asset (
  stored_name TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  original_name TEXT NOT NULL,
  object_key TEXT NOT NULL UNIQUE,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  content_type TEXT NOT NULL,
  extension TEXT NOT NULL,
  content_sha256 TEXT NOT NULL CHECK (length(content_sha256) = 64),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_platform_file_asset_owner
  ON platform_file_asset(user_id, created_at DESC);
