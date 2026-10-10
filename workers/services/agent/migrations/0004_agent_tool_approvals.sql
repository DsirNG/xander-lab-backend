-- Agent owns tool invocation journals and approval decisions; user IDs remain opaque Core references.
CREATE TABLE agent_tool_invocation (
  -- Durable invocation primary key.
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- Agent-owned conversation receiving the tool result.
  conversation_id INTEGER NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
  -- Conversation execution generation that requested the tool.
  run_version INTEGER NOT NULL,
  -- Stable idempotency identity passed to owning service adapters.
  invocation_id TEXT NOT NULL UNIQUE,
  -- Registered tool name; model-supplied names are never executed directly.
  tool_name TEXT NOT NULL,
  -- Validated arguments retained for approval and recovery.
  arguments_json TEXT NOT NULL,
  -- RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/UNKNOWN/CANCELLED lifecycle state.
  status TEXT NOT NULL CHECK (status IN ('RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED')),
  -- Structured tool result retained for replay and reconciliation.
  result_json TEXT,
  -- Safe normalized failure text; credentials and provider payloads are excluded.
  error_message TEXT,
  -- First time a side effect was allowed to start, in UTC.
  started_at TEXT,
  -- Terminal time for a settled invocation, in UTC.
  completed_at TEXT,
  -- Invocation creation time, in UTC.
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- Invocation update time, in UTC.
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_tool_invocation_conversation_run
  ON agent_tool_invocation (conversation_id, run_version, id);
CREATE INDEX idx_agent_tool_invocation_status_updated
  ON agent_tool_invocation (status, updated_at);

-- Approval snapshots bind the user's decision to one immutable action fingerprint.
CREATE TABLE agent_approval (
  -- Approval primary key returned by the public Agent API.
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- Opaque Core user ID owning the conversation and requested action.
  user_id INTEGER NOT NULL,
  -- Agent-owned conversation paused for this approval.
  conversation_id INTEGER NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
  -- Conversation execution generation associated with this approval.
  run_version INTEGER NOT NULL,
  -- Durable invocation identity; one approval is allowed per invocation.
  invocation_id TEXT NOT NULL UNIQUE REFERENCES agent_tool_invocation(invocation_id),
  -- Registered tool requested by the model.
  tool_name TEXT NOT NULL,
  -- SHA-256 of the tool name and canonical validated arguments.
  request_fingerprint TEXT NOT NULL,
  -- User-visible action summary without credentials or a raw model prompt.
  summary_json TEXT NOT NULL,
  -- PENDING/APPROVED/REJECTED decision state.
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  -- Optional short reason supplied by the owner when deciding.
  decision_reason TEXT,
  -- Unique decision fence so duplicate requests cannot append a second transition event.
  decision_token TEXT,
  -- Time the owner made a decision, in UTC.
  decided_at TEXT,
  -- Approval creation time, in UTC.
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- Approval update time, in UTC.
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_approval_owner_conversation
  ON agent_approval (user_id, conversation_id, id DESC);
CREATE INDEX idx_agent_approval_pending
  ON agent_approval (status, created_at);
