-- CSDN 发布冷却：每个用户同时只能持有一个 30 秒发布窗口。
CREATE TABLE IF NOT EXISTS csdn_publication_cooldown (
  -- 用户主键，冷却窗口由 Content Automation 的用户请求共享。
  user_id INTEGER PRIMARY KEY NOT NULL,
  -- 冷却截止时间，使用 UTC ISO-8601 文本。
  expires_at TEXT NOT NULL
);
