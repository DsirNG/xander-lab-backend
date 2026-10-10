CREATE TABLE IF NOT EXISTS blog_media_asset (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  url TEXT NOT NULL CHECK (length(url) <= 1024),
  original_name TEXT NOT NULL CHECK (length(original_name) <= 255),
  stored_name TEXT CHECK (stored_name IS NULL OR length(stored_name) <= 255),
  size INTEGER NOT NULL CHECK (size > 0),
  content_type TEXT NOT NULL CHECK (length(content_type) <= 100),
  width INTEGER,
  height INTEGER,
  source_type TEXT NOT NULL DEFAULT 'user_upload' CHECK (source_type IN ('user_upload', 'agent_generated')),
  agent_task_id INTEGER,
  generation_meta TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_blog_media_user_created
  ON blog_media_asset(user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_blog_media_user_type
  ON blog_media_asset(user_id, content_type);

CREATE INDEX IF NOT EXISTS idx_blog_media_agent_task
  ON blog_media_asset(user_id, agent_task_id, id);
