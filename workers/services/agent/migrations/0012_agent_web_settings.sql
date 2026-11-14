-- 平台联网搜索配置；仅管理员可维护，密钥使用 AES-GCM 加密存储。
CREATE TABLE IF NOT EXISTS agent_web_settings (
  -- 单例配置标识，固定为 1。
  id INTEGER PRIMARY KEY CHECK (id = 1),
  -- 是否启用联网搜索与网页读取：0 关闭，1 启用。
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  -- Tavily API Key 的版本化 AES-GCM 密文；NULL 表示未配置。
  encrypted_api_key TEXT,
  -- 最后修改配置的管理员用户标识，仅作审计引用，不建立跨服务外键。
  updated_by INTEGER NOT NULL,
  -- 最后修改时间，UTC ISO 8601。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
