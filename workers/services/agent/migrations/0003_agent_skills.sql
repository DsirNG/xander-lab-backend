-- 用户私有技能仅保存说明和工具名称；执行权限始终由 Agent 工具注册表决定。
CREATE TABLE agent_skill (
  -- 技能版本主键。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 技能所属用户 ID；仅作为 Core 用户标识引用，不跨 D1 建立外键。
  user_id INTEGER NOT NULL,
  -- 用户命名的稳定 kebab-case 技能标识。
  skill_key TEXT NOT NULL,
  -- 同一用户和技能标识下递增的不可变版本号。
  version INTEGER NOT NULL,
  -- 面向用户和模型目录展示的技能名称。
  name TEXT NOT NULL,
  -- 提供给模型选择技能的摘要。
  description TEXT NOT NULL,
  -- 模型经受控技能读取流程显式获取的说明正文。
  instructions TEXT NOT NULL,
  -- 技能声明的工具名称 JSON 数组；不授予执行权限。
  tool_names_json TEXT NOT NULL DEFAULT '[]',
  -- 技能生命周期状态：ACTIVE 可读取，ARCHIVED 仅保留历史记录。
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  -- 技能版本创建 UTC 时间。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 技能版本最后更新时间。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (user_id, skill_key, version)
);

CREATE INDEX idx_agent_skill_user_status_key
  ON agent_skill (user_id, status, skill_key, version DESC);
