CREATE TABLE blog_agent_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  client_request_id TEXT,
  input TEXT NOT NULL,
  input_type TEXT NOT NULL DEFAULT 'topic',
  audience TEXT NOT NULL DEFAULT '',
  tone TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'created',
  stage TEXT NOT NULL DEFAULT 'analyze',
  title TEXT,
  summary TEXT,
  content TEXT,
  outline TEXT,
  content_boundary TEXT NOT NULL DEFAULT '{}',
  knowledge_graph_json TEXT NOT NULL DEFAULT '{}',
  category_id TEXT NOT NULL DEFAULT 'career',
  tags_json TEXT NOT NULL DEFAULT '[]',
  review TEXT,
  illustration_status TEXT NOT NULL DEFAULT 'none',
  illustration_error TEXT,
  published_post_id INTEGER,
  error_message TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE(user_id, client_request_id)
);

CREATE INDEX idx_blog_agent_task_user_updated ON blog_agent_task(user_id, updated_at DESC);

CREATE TABLE blog_agent_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES blog_agent_task(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  kind TEXT NOT NULL,
  stage TEXT,
  content TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_blog_agent_message_task_id ON blog_agent_message(task_id, id);

CREATE TABLE blog_agent_source (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES blog_agent_task(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  url TEXT NOT NULL,
  publisher TEXT,
  excerpt TEXT,
  reliability TEXT,
  retrieved_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_blog_agent_source_task ON blog_agent_source(task_id, id);

CREATE TABLE blog_agent_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES blog_agent_task(id) ON DELETE CASCADE,
  version_no INTEGER NOT NULL,
  title TEXT NOT NULL,
  summary TEXT NOT NULL,
  content TEXT NOT NULL,
  change_note TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE(task_id, version_no)
);

CREATE TABLE blog_agent_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES blog_agent_task(id) ON DELETE CASCADE,
  event_type TEXT NOT NULL,
  data_content TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_blog_agent_event_task_id ON blog_agent_event(task_id, id);
