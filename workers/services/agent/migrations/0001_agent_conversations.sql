-- 会话和消息归 Agent 所有；用户 ID 是 Core 的不透明引用。
CREATE TABLE agent_conversation (
  -- 会话主键。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 会话所属用户 ID；只保存 Core 的用户标识，不建立跨 D1 外键。
  user_id INTEGER NOT NULL,
  -- 会话标题，由首条用户输入截取生成。
  title TEXT NOT NULL,
  -- 会话状态：ready、running、awaiting_approval 或 failed。
  status TEXT NOT NULL DEFAULT 'ready',
  -- 最近一轮执行失败原因。
  error_message TEXT,
  -- 当前执行代次，用于隔离旧执行器的迟到写入。
  run_version INTEGER NOT NULL DEFAULT 0,
  -- 用户取消请求标记：0 未请求，1 请求停止当前执行。
  cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK (cancel_requested IN (0, 1)),
  -- 智能体维护的会话级任务计划 JSON。
  plan_json TEXT,
  -- 公开分享令牌；禁用分享时置空。
  share_token TEXT UNIQUE,
  -- 置顶标记：0 普通会话，1 置顶会话。
  is_pinned INTEGER NOT NULL DEFAULT 0 CHECK (is_pinned IN (0, 1)),
  -- 用户最近一次打开会话的 UTC 时间。
  last_read_at TEXT,
  -- 最近一轮执行到达终态的 UTC 时间，用于计算未读提醒。
  last_completed_at TEXT,
  -- 会话创建 UTC 时间，ISO-8601 格式。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 会话更新时间，列表排序使用该值。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_conversation_user_created
  ON agent_conversation (user_id, created_at);
CREATE INDEX idx_agent_conversation_user_pinned_updated
  ON agent_conversation (user_id, is_pinned DESC, updated_at DESC);

-- 消息归 Agent 所有；只随本服务的所属会话级联删除。
CREATE TABLE agent_message (
  -- 消息主键。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 所属 Agent 会话 ID。
  conversation_id INTEGER NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
  -- 创建消息的执行代次；历史或非执行消息可以为空。
  run_version INTEGER,
  -- 消息角色：user、assistant 或 tool。
  role TEXT NOT NULL,
  -- 消息类型：message、thought、reflection、tool_call、tool_result、answer、plan、quiz、quiz_answer、artifact 或 compaction。
  kind TEXT NOT NULL,
  -- 工具调用或结果对应的工具名称。
  tool_name TEXT,
  -- 配对同一轮工具调用与结果的稳定标识。
  invocation_id TEXT,
  -- 消息正文；工具记录和结构卡片使用 JSON 文本。
  content TEXT NOT NULL,
  -- 消息附件快照 JSON；二进制内容保存在 OSS。
  attachments_json TEXT,
  -- 消息创建 UTC 时间，ISO-8601 格式。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_message_conversation_created
  ON agent_message (conversation_id, created_at, id);
CREATE INDEX idx_agent_message_conversation_run_kind
  ON agent_message (conversation_id, run_version, kind);
