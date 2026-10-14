ALTER TABLE blog_content_plan ADD COLUMN plan_series_id INTEGER;
ALTER TABLE blog_content_plan ADD COLUMN series_day INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS uk_blog_plan_series_day
  ON blog_content_plan(plan_series_id, series_day)
  WHERE plan_series_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS blog_plan_series (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  client_request_id TEXT,
  title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  topic TEXT NOT NULL CHECK (length(topic) BETWEEN 1 AND 500),
  topics_json TEXT NOT NULL CHECK (json_valid(topics_json)),
  audience TEXT NOT NULL DEFAULT '',
  tone TEXT NOT NULL DEFAULT '',
  ai_option TEXT NOT NULL DEFAULT 'DEEP',
  start_date TEXT NOT NULL,
  trigger_time TEXT NOT NULL,
  timezone TEXT NOT NULL,
  platforms_json TEXT NOT NULL DEFAULT '["local"]' CHECK (json_valid(platforms_json)),
  publish_mode TEXT NOT NULL DEFAULT 'REVIEW' CHECK (publish_mode IN ('DRAFT', 'REVIEW', 'AUTO')),
  status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVATING', 'ACTIVE', 'PAUSED', 'CANCELLED', 'FINISHED', 'FAILED')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_blog_plan_series_owner
  ON blog_plan_series(user_id, updated_at DESC, id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_blog_plan_series_request
  ON blog_plan_series(user_id, client_request_id)
  WHERE client_request_id IS NOT NULL;
