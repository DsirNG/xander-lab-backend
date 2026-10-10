-- Juejin category and tag projections are shared provider metadata, not user content.
CREATE TABLE IF NOT EXISTS juejin_category (
  category_id TEXT PRIMARY KEY,
  category_name TEXT NOT NULL,
  rank INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS juejin_tag (
  tag_id TEXT PRIMARY KEY,
  tag_name TEXT NOT NULL,
  category_id TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_juejin_tag_name ON juejin_tag(tag_name);

CREATE TABLE IF NOT EXISTS juejin_catalog_sync (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  synced_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
