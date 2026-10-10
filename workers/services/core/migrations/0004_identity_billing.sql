-- Registration-created users keep a retryable marker until their welcome grant
-- is applied in the same D1 transaction as the billing ledger write.
ALTER TABLE sys_user
  ADD COLUMN registration_grant_pending INTEGER NOT NULL DEFAULT 0
  CHECK (registration_grant_pending IN (0, 1));

-- Core-owned point accounts use milli-points (1 point = 1,000 milli-points).
CREATE TABLE IF NOT EXISTS sys_user_points (
  user_id INTEGER PRIMARY KEY,
  balance INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- The unique business key makes retries safe across Worker invocations.
CREATE TABLE IF NOT EXISTS points_ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  biz_type TEXT NOT NULL,
  biz_key TEXT NOT NULL,
  biz_ref TEXT,
  amount INTEGER NOT NULL CHECK (amount > 0),
  balance_after INTEGER NOT NULL,
  unit_price TEXT,
  model TEXT,
  input_tokens INTEGER,
  output_tokens INTEGER,
  cached_tokens INTEGER,
  remark TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (biz_type, biz_key)
);

CREATE INDEX IF NOT EXISTS idx_points_ledger_user_id_id
  ON points_ledger (user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_points_ledger_user_created
  ON points_ledger (user_id, created_at);

-- Model-specific rates override Worker defaults when enabled.
CREATE TABLE IF NOT EXISTS billing_model_price (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  model TEXT NOT NULL UNIQUE,
  input_milli_per_1k_token INTEGER NOT NULL CHECK (input_milli_per_1k_token >= 0),
  output_milli_per_1k_token INTEGER NOT NULL CHECK (output_milli_per_1k_token >= 0),
  cached_milli_per_1k_token INTEGER NOT NULL DEFAULT 0 CHECK (cached_milli_per_1k_token >= 0),
  cached_write_milli_per_1k_token INTEGER NOT NULL DEFAULT 0 CHECK (cached_write_milli_per_1k_token >= 0),
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  remark TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Ledger insertion and balance movement are one SQLite transaction; duplicate
-- idempotency keys do not run the trigger a second time.
CREATE TRIGGER IF NOT EXISTS points_ledger_apply_balance
AFTER INSERT ON points_ledger
BEGIN
  UPDATE sys_user_points
  SET balance = NEW.balance_after,
      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
  WHERE user_id = NEW.user_id;
END;
