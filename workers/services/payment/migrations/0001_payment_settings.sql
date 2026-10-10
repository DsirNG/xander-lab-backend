-- Payment-owned WeChat virtual-payment catalog and encrypted application key.
-- User IDs are opaque Core references; this database has no cross-service FK.
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

CREATE TABLE IF NOT EXISTS virtual_pay_order (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  out_trade_no TEXT NOT NULL UNIQUE,
  wx_order_id TEXT UNIQUE,
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  openid TEXT NOT NULL,
  product_id TEXT NOT NULL,
  tier TEXT NOT NULL CHECK (tier IN ('PLUS', 'PRO', 'ULTRA')),
  goods_price INTEGER NOT NULL CHECK (goods_price > 0),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  duration_days INTEGER NOT NULL CHECK (duration_days > 0),
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'REFUNDED', 'FAILED')),
  paid_at TEXT,
  delivered_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_virtual_pay_order_user_created
  ON virtual_pay_order(user_id, created_at DESC);
