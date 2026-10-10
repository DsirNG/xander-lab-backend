-- Agent owns long-term memory; user IDs are opaque Core references.
CREATE TABLE agent_memory (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  memory_key TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'FACT'
    CHECK (kind IN ('PROFILE', 'PREFERENCE', 'FACT', 'PROJECT')),
  content TEXT NOT NULL,
  source_conversation_id INTEGER,
  hit_count INTEGER NOT NULL DEFAULT 0,
  last_used_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE(user_id, memory_key)
);

CREATE INDEX idx_agent_memory_user_updated
  ON agent_memory(user_id, updated_at DESC, id DESC);
CREATE INDEX idx_agent_memory_user_hits
  ON agent_memory(user_id, hit_count ASC, updated_at ASC, id ASC);
