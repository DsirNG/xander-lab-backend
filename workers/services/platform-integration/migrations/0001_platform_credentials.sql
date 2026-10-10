-- Platform Integration owns per-user provider credentials; secret payloads are AES-GCM encrypted.
CREATE TABLE IF NOT EXISTS platform_credential (
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  platform TEXT NOT NULL CHECK (platform IN ('csdn', 'juejin')),
  encrypted_credential TEXT NOT NULL,
  account_name TEXT,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED')),
  authorized_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  last_validated_at TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  PRIMARY KEY (user_id, platform)
);

CREATE INDEX IF NOT EXISTS idx_platform_credential_status
  ON platform_credential(platform, status, user_id);
