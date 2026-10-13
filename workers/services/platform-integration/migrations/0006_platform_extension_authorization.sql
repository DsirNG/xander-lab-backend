-- 浏览器扩展授权任务按平台和 DinQorAI 用户隔离，任务令牌只保存 SHA-256 摘要。
CREATE TABLE IF NOT EXISTS platform_extension_authorization (
  -- 授权任务 UUID，作为前后端短期关联标识，不包含凭证。
  id TEXT PRIMARY KEY,
  -- DinQorAI 用户 ID，确保授权结果只能写入发起用户的凭证记录。
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  -- 外部平台标识，包含已支持发布的平台和仅支持账号绑定的平台。
  platform TEXT NOT NULL CHECK (platform IN ('csdn', 'juejin', 'wechat_mp', 'weibo', 'zhihu', 'baijiahao', 'cnblogs', 'douyin', 'xiaohongshu', 'toutiao', '51cto', 'jianshu', 'segmentfault', 'oschina', 'bilibili', 'tencent_dev', 'aliyun_dev')),
  -- 一次性提交令牌的 SHA-256 摘要，原始令牌仅返回给扩展一次。
  token_hash TEXT NOT NULL CHECK (length(token_hash) = 43),
  -- 任务生命周期：待提交、处理中、已完成、已取消或已过期。
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
  -- UTC 过期时间，超时后扩展不得再提交凭证。
  expires_at TEXT NOT NULL,
  -- 凭证成功保存的 UTC 时间。
  completed_at TEXT,
  -- 任务创建时间。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_platform_extension_authorization_expiry
  ON platform_extension_authorization(status, expires_at);
