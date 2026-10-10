-- Core-owned personal recitation materials, audio attempts, and quiz history.
-- Long audio files remain in Alibaba OSS; D1 stores only their URL and task metadata.
CREATE TABLE IF NOT EXISTS recitation_material (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  normalized_content TEXT NOT NULL,
  knowledge_type TEXT NOT NULL DEFAULT 'RECITATION',
  test_mode TEXT NOT NULL DEFAULT 'AUDIO_RECITATION',
  mastery_score REAL NOT NULL DEFAULT 0,
  mastery_level TEXT NOT NULL DEFAULT 'NEW',
  review_count INTEGER NOT NULL DEFAULT 0,
  last_review_at TEXT,
  next_review_at TEXT,
  archived_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_recitation_material_user_review
  ON recitation_material(user_id, next_review_at, mastery_level);
CREATE INDEX IF NOT EXISTS idx_recitation_material_user_created
  ON recitation_material(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_recitation_material_user_archived
  ON recitation_material(user_id, archived_at, id);

CREATE TABLE IF NOT EXISTS recitation_attempt (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  material_id INTEGER NOT NULL,
  client_request_id TEXT NOT NULL,
  -- OSS object key for Worker uploads; imported legacy HTTPS URLs remain readable.
  audio_url TEXT,
  status TEXT NOT NULL,
  provider_task_id TEXT,
  provider_id INTEGER,
  model TEXT,
  transcript TEXT,
  result_json TEXT,
  score REAL,
  error_message TEXT,
  run_token TEXT,
  poll_attempts INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE(user_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_recitation_attempt_user_created
  ON recitation_attempt(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_recitation_attempt_material_created
  ON recitation_attempt(material_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_recitation_attempt_status_updated
  ON recitation_attempt(status, updated_at, id);

CREATE TABLE IF NOT EXISTS knowledge_quiz_session (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  material_id INTEGER NOT NULL,
  conversation_id INTEGER,
  status TEXT NOT NULL,
  attempt_id INTEGER,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  graded_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_knowledge_quiz_session_user_created
  ON knowledge_quiz_session(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_quiz_session_status_expires
  ON knowledge_quiz_session(status, expires_at, id);

CREATE TABLE IF NOT EXISTS knowledge_quiz_attempt (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  material_id INTEGER NOT NULL,
  quiz_session_id INTEGER NOT NULL UNIQUE,
  conversation_id INTEGER,
  test_mode TEXT NOT NULL,
  question_count INTEGER NOT NULL,
  correct_count INTEGER NOT NULL,
  score REAL NOT NULL,
  verdict TEXT,
  items_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_quiz_material_created
  ON knowledge_quiz_attempt(material_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_quiz_user_created
  ON knowledge_quiz_attempt(user_id, created_at DESC, id DESC);
