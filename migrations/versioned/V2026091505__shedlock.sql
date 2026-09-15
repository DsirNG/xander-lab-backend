-- V9 §41 / §68 / §73 — MySQL-backed ShedLock.
--
-- Used ONLY by `execution_scope = global_once` tasks (V9 §68):
--   auth-refresh-history-cleanup
--   auth-refresh-ciphertext-cleanup
--   redis-health-monitor
--   knowledge-upload-session-reaper
--   recitation-worker
--   img2three-service
--   blog-content-plan-scheduler
--   email-reminder-scheduler
--   agent-tool-invocation-reconciler
--
-- MUST NOT be used by `execution_scope = per_instance` tasks (V9 §69):
--   security-denylist-reconciler-gateway
--   security-state-reconciler-core-host / -agent / -content-automation
--   csdn-authorization-service
--   juejin-authorization-service
--
-- Rationale (V9 §69): a global lock on a per-instance task makes N-1 instances
-- stop refreshing their local state, while the acceptance criterion
-- ("did not execute twice") still passes. It is a failure that cannot fail.
--
-- MySQL-backed on purpose: the auth maintenance jobs must not depend on Redis
-- (V9 §41), otherwise a Redis outage would also stop refresh-history cleanup.

CREATE TABLE IF NOT EXISTS `shedlock` (
  `name` VARCHAR(64) NOT NULL COMMENT '任务名，对应 scheduled-ownership.yaml 的 id',
  `lock_until` TIMESTAMP(3) NOT NULL COMMENT '锁到期时间；到期后其他实例可抢占',
  `locked_at` TIMESTAMP(3) NOT NULL COMMENT '加锁时间',
  `locked_by` VARCHAR(255) NOT NULL COMMENT '持锁实例标识（hostname + 实例ID）',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分布式任务互斥锁（仅 global_once 任务使用）';
