CREATE TABLE IF NOT EXISTS blog_content_plan (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  client_request_id TEXT,
  topic TEXT NOT NULL CHECK (length(topic) <= 500),
  audience TEXT NOT NULL DEFAULT '' CHECK (length(audience) <= 120),
  tone TEXT NOT NULL DEFAULT '' CHECK (length(tone) <= 60),
  schedule_type TEXT NOT NULL DEFAULT 'DAILY' CHECK (schedule_type IN ('DAILY', 'WEEKLY', 'ONCE')),
  scheduled_date TEXT,
  ai_option TEXT NOT NULL DEFAULT 'DEEP' CHECK (ai_option IN ('DEEP', 'PRACTICAL', 'NEWS', 'OPINION')),
  knowledge_material_id INTEGER,
  auto_publish INTEGER NOT NULL DEFAULT 1 CHECK (auto_publish IN (0, 1)),
  timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  trigger_time TEXT NOT NULL,
  trigger_times_json TEXT NOT NULL DEFAULT '[]',
  topics_json TEXT NOT NULL DEFAULT '[]',
  next_topic_index INTEGER NOT NULL DEFAULT 0,
  sync_csdn INTEGER NOT NULL DEFAULT 0 CHECK (sync_csdn IN (0, 1)),
  sync_juejin INTEGER NOT NULL DEFAULT 0 CHECK (sync_juejin IN (0, 1)),
  run_once INTEGER NOT NULL DEFAULT 0 CHECK (run_once IN (0, 1)),
  next_run_at TEXT,
  last_run_at TEXT,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RUNNING', 'PAUSED', 'CANCELLED', 'FINISHED', 'FAILED')),
  error_message TEXT,
  processing_token TEXT,
  claimed_at TEXT,
  deleted_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_blog_plan_user_request
  ON blog_content_plan(user_id, client_request_id);
CREATE INDEX IF NOT EXISTS idx_blog_plan_user_created
  ON blog_content_plan(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_blog_plan_due
  ON blog_content_plan(status, next_run_at, id);

CREATE TABLE IF NOT EXISTS blog_plan_run (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL,
  scheduled_at TEXT NOT NULL,
  trigger_type TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
  status TEXT NOT NULL DEFAULT 'GENERATING',
  stage_error TEXT,
  error_message TEXT,
  agent_task_id INTEGER,
  local_post_id INTEGER,
  review_pass INTEGER,
  review_reason TEXT,
  csdn_status TEXT,
  csdn_error_code TEXT,
  csdn_error_message TEXT,
  csdn_external_id TEXT,
  csdn_url TEXT,
  juejin_status TEXT,
  juejin_error_code TEXT,
  juejin_error_message TEXT,
  juejin_external_id TEXT,
  juejin_url TEXT,
  processing_token TEXT,
  claimed_at TEXT,
  started_at TEXT,
  finished_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE(plan_id, scheduled_at),
  FOREIGN KEY (plan_id) REFERENCES blog_content_plan(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_blog_plan_run_plan_updated
  ON blog_plan_run(plan_id, updated_at DESC, id DESC);
