-- Core-owned administrative configuration migrated from the Java core host.
-- Secrets are encrypted with AES-GCM before they enter D1 and are never returned.
CREATE TABLE IF NOT EXISTS model_provider (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE,
  vendor TEXT NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
  base_url TEXT NOT NULL,
  encrypted_api_key TEXT NOT NULL,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  priority INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_model_provider_priority
  ON model_provider(priority, id);

CREATE TABLE IF NOT EXISTS feature_model_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  feature_key TEXT NOT NULL UNIQUE,
  primary_provider_id INTEGER,
  primary_model TEXT,
  primary_api_style TEXT,
  fallback_provider_id INTEGER,
  fallback_model TEXT,
  fallback_api_style TEXT,
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS wechat_credential (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  app_id TEXT NOT NULL,
  encrypted_app_secret TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS virtual_pay_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  offer_id TEXT NOT NULL DEFAULT '',
  encrypted_app_key TEXT,
  plus_product_id TEXT NOT NULL DEFAULT '',
  plus_price_cents INTEGER NOT NULL DEFAULT 0,
  plus_duration_days INTEGER NOT NULL DEFAULT 30,
  pro_product_id TEXT NOT NULL DEFAULT '',
  pro_price_cents INTEGER NOT NULL DEFAULT 0,
  pro_duration_days INTEGER NOT NULL DEFAULT 30,
  ultra_product_id TEXT NOT NULL DEFAULT '',
  ultra_price_cents INTEGER NOT NULL DEFAULT 0,
  ultra_duration_days INTEGER NOT NULL DEFAULT 30,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
