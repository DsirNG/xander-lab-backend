-- V9 §30 / §32 — Refresh Session Source of Truth.
--
-- Owner: identity (logical) / dinqor-core-host (current deployment owner).
-- MySQL is the SOT because of transactions, durability, per-user bulk revoke and
-- auditability — NOT because "Redis is single-instance and MySQL is not"
-- (MySQL is single-instance today too; see V9 §30).
--
-- One row per login session. Rotation mutates `current_token_id` /
-- `current_token_hash` / `generation` in place inside a transaction, so the
-- session row is the only place that knows which refresh token is current.
--
-- NOTE on `generation`: it is the rotation counter, used both for the §40.3
-- "keep the newest 48 generations per sid" bound and for detecting a token that
-- is neither current nor in history.

CREATE TABLE IF NOT EXISTS `auth_refresh_session` (
  `sid` VARCHAR(64) NOT NULL COMMENT '会话标识；位于 refresh token 明文前缀 rt_<sid>.<tokenId>.<secret>，因此不是秘密',
  `account_id` BIGINT NOT NULL COMMENT '所属账号ID，关联 sys_user.id',
  `family_id` VARCHAR(64) NOT NULL COMMENT '会话族标识；Reuse Detection 命中时按此撤销整族',
  `current_token_id` VARCHAR(64) NOT NULL COMMENT '当前有效 refresh token 的 tokenId',
  `current_token_hash` VARCHAR(128) NOT NULL COMMENT '当前 refresh token secret 的哈希；明文永不落库',
  `generation` INT NOT NULL DEFAULT 0 COMMENT '轮换代次；每次成功轮换 +1，用于每 sid 48 代条数上界',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE / REVOKED / EXPIRED',
  `issued_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次签发时间',
  `last_rotated_at` DATETIME(3) NULL COMMENT '最近一次成功轮换时间',
  `expires_at` DATETIME(3) NOT NULL COMMENT '会话绝对过期时间；轮换不延长该时间',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销时间',
  `revoked_reason` VARCHAR(64) NULL COMMENT '撤销原因：LOGOUT / LOGOUT_ALL / ADMIN_DISABLE / REUSE_DETECTED / PASSWORD_CHANGED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`sid`),
  UNIQUE KEY `uk_refresh_current_token` (`current_token_id`),
  KEY `idx_refresh_account_status` (`account_id`, `status`),
  KEY `idx_refresh_family` (`family_id`),
  KEY `idx_refresh_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='刷新会话权威表（Refresh Session SOT）';
