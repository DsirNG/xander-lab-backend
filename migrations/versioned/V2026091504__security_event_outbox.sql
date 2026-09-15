-- V9 §26 / §27 — transactional outbox for account security events.
--
-- Owner: identity (logical) / dinqor-core-host (current deployment owner).
--
-- The admin-ban transaction (§26) must atomically:
--   1. persist the user status in MySQL
--   2. revoke every Refresh Session of the account
--   3. bump account_security_state.security_version
--   4. write an AccountDisabled security event       ← this table
--   5. (after commit) push the accountId into every host's local deny cache
--   6. (after commit) close the account's established SSE / WS on every host
--
-- Steps 5 and 6 cannot be part of the transaction (they touch other processes),
-- so the event is written inside the transaction and published after commit.
-- That is what makes the ban survive a crash between commit and broadcast.
--
-- Propagation (V9 §27):
--   fast path     internal event channel / Redis PubSub when available
--   recovery path every instance periodically pulls account_security_state by
--                 change_seq, so a lost fast-path event still converges
--   recommended initial reconcile interval = 1–2 seconds
--
-- Targets (V9 §52) — dinqor-gateway, dinqor-core-host, dinqor-agent,
-- dinqor-content-automation, dinqor-studio (after Wave 3). Each target uses
-- execution_scope = per_instance / singleton_strategy = none: a global lock
-- would leave N-1 instances with a permanently stale cache while the
-- "did not run twice" acceptance check still passes (V9 §69).

CREATE TABLE IF NOT EXISTS `security_event_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型，当前为 ACCOUNT',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合标识，即 accountId 的字符串形式',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型：AccountDisabled / AccountEnabled',
  `payload` TEXT NULL COMMENT '事件负载 JSON：accountId、securityVersion、changeSeq、reason、occurredAt',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING / PUBLISHED / FAILED',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
  `last_error` VARCHAR(500) NULL COMMENT '最近一次投递失败原因',
  `next_attempt_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可投递时间；失败后指数退避',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `published_at` DATETIME(3) NULL COMMENT '成功投递时间；与 created_at 之差即 admin_ban_propagation_latency',
  PRIMARY KEY (`id`),
  KEY `idx_outbox_status_next_attempt` (`status`, `next_attempt_at`),
  KEY `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`),
  KEY `idx_outbox_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安全事件事务性发件箱';
