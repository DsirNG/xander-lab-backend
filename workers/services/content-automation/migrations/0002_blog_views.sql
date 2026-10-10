-- 博客阅读记录：保留每次成功计数的审计与统计数据。
CREATE TABLE IF NOT EXISTS blog_post_view (
  -- 阅读记录主键。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 被阅读文章 ID，文章永久删除时级联删除阅读记录。
  post_id INTEGER NOT NULL REFERENCES blog_post(id) ON DELETE CASCADE,
  -- 可信登录用户 ID；网关身份投影完成前匿名阅读保存 NULL。
  user_id INTEGER,
  -- Cloudflare 边缘解析得到的客户端 IP；缺失时保存 NULL。
  ip_address TEXT,
  -- 浏览器 User-Agent，最多保留 500 个字符。
  user_agent TEXT,
  -- 此次计数的幂等事件 ID，供 D1 原子批次关联计数更新。
  event_id TEXT NOT NULL UNIQUE,
  -- 阅读发生时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL
);

-- 阅读去重记录：按文章和匿名访客摘要唯一，24 小时过期。
CREATE TABLE IF NOT EXISTS blog_view_dedup (
  -- 文章与访客的 SHA-256 组合键，不保存原始访客标识。
  dedupe_key TEXT PRIMARY KEY NOT NULL,
  -- 本次尝试的唯一事件 ID；重复请求不得增加阅读次数。
  event_id TEXT NOT NULL UNIQUE,
  -- 去重窗口截止时间，使用 UTC ISO-8601 文本。
  expires_at TEXT NOT NULL,
  -- 去重记录创建时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_blog_post_view_post_created
  ON blog_post_view(post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_blog_post_view_user_created
  ON blog_post_view(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_blog_view_dedup_expiry
  ON blog_view_dedup(expires_at);
