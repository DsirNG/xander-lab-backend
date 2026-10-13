-- 账号绑定需要保存更多平台的加密会话；原有 CSDN/掘金记录原样保留。
ALTER TABLE platform_credential RENAME TO platform_credential_legacy;

-- 平台账号凭证表，凭证正文由 Worker 使用 AES-GCM 加密后保存。
CREATE TABLE platform_credential (
  -- DinQorAI 用户 ID，平台凭证始终按用户隔离。
  user_id INTEGER NOT NULL CHECK (user_id > 0),
  -- 外部平台标识；只有 CSDN 与掘金具备一键发文能力。
  platform TEXT NOT NULL CHECK (platform IN ('csdn', 'juejin', 'wechat_mp', 'weibo', 'zhihu', 'baijiahao', 'cnblogs', 'douyin', 'xiaohongshu', 'toutiao', '51cto', 'jianshu', 'segmentfault', 'oschina', 'bilibili', 'tencent_dev', 'aliyun_dev')),
  -- AES-GCM 加密后的平台凭证。
  encrypted_credential TEXT NOT NULL,
  -- 平台返回或扩展识别的账号显示名。
  account_name TEXT,
  -- 凭证状态：有效或已过期。
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED')),
  -- 首次授权时间。
  authorized_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 最近一次确认凭证有效的时间。
  last_validated_at TEXT,
  -- 凭证记录更新时间。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  PRIMARY KEY (user_id, platform)
);

INSERT INTO platform_credential (
  user_id, platform, encrypted_credential, account_name, status,
  authorized_at, last_validated_at, updated_at
)
SELECT user_id, platform, encrypted_credential, account_name, status,
       authorized_at, last_validated_at, updated_at
  FROM platform_credential_legacy;

DROP TABLE platform_credential_legacy;

CREATE INDEX idx_platform_credential_status
  ON platform_credential(platform, status, user_id);
