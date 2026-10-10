-- Durable replay log for Agent run progress and terminal events.
CREATE TABLE agent_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
  run_version INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  data_content TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_event_conversation_run_id
  ON agent_event (conversation_id, run_version, id);
