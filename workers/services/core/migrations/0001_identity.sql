-- Core owns identity records. Other Workers keep only the user ID needed for
-- their own data ownership and never join against this database.
CREATE TABLE IF NOT EXISTS sys_user (
  -- 用户主键；跨服务引用时只保存该 ID，不建立跨 D1 外键。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 用户登录名；大小写不敏感且唯一。
  username TEXT NOT NULL COLLATE NOCASE UNIQUE,
  -- 密码散列；空值表示该账号当前没有密码登录凭据。
  password TEXT,
  -- 用户展示昵称。
  nickname TEXT,
  -- 用户登录和联系邮箱；大小写不敏感且唯一。
  email TEXT COLLATE NOCASE UNIQUE,
  -- 头像 URL，不存储图片字节。
  avatar TEXT,
  -- 微信小程序 openid；未绑定时为空。
  openid TEXT UNIQUE,
  -- 账户角色，例如 ADMIN 或 USER。
  role TEXT NOT NULL DEFAULT 'USER',
  -- 订阅等级及允许值。
  tier TEXT NOT NULL DEFAULT 'FREE'
    CHECK (tier IN ('FREE', 'PLUS', 'PRO', 'ULTRA')),
  -- 当前订阅权益到期时间；无到期时间时为空。
  tier_expires_at TEXT,
  -- 账户状态：1 启用、0 禁用。
  status INTEGER NOT NULL DEFAULT 1 CHECK (status IN (0, 1)),
  -- UTC 创建时间，ISO-8601 格式。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- UTC 更新时间，ISO-8601 格式。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
