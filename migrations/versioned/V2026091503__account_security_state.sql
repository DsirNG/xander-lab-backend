-- V9 §26 / §27 / §28 / §48 — admin ban source of truth + per-instance cursor.
--
-- Owner: identity (logical) / dinqor-core-host (current deployment owner).
--
-- This table is the SOT for "is this account disabled right now". Every host
-- that serves traffic (Gateway, core-host, agent, content-automation, studio)
-- keeps a LOCAL copy of the disabled account set and must converge to this
-- table within the §28 SLO.
--
-- ---------------------------------------------------------------------------
-- Why `disabled` alone is not enough — the change cursor
-- ---------------------------------------------------------------------------
-- V9 §27 requires a *recovery path*: each instance periodically pulls an
-- incremental slice of this table so that a lost fast-path event still
-- converges. A cursor over `updated_at` would need an overlap window and is
-- sensitive to clock skew, and `security_reconcile_lag` (§87) would not be
-- measurable in a deterministic way.
--
-- Therefore `change_seq` is a **globally monotonic** value, allocated from the
-- single-row `security_state_sequence` table. The writer contract is:
--
--   START TRANSACTION;
--     UPDATE `security_state_sequence` SET `seq` = LAST_INSERT_ID(`seq` + 1);
--     INSERT INTO `account_security_state`
--       (`account_id`, `disabled`, `security_version`, `change_seq`)
--     VALUES (:accountId, :disabled, :newVersion, LAST_INSERT_ID())
--     ON DUPLICATE KEY UPDATE
--       `disabled`         = VALUES(`disabled`),
--       `security_version` = VALUES(`security_version`),
--       `change_seq`       = VALUES(`change_seq`);
--     INSERT INTO `security_event_outbox` (...) VALUES (...);
--   COMMIT;
--
-- Note the ODKU explicitly writes `change_seq` — an AUTO_INCREMENT column would
-- NOT advance on the update path, so the cursor would silently miss bans.
--
-- Reconcile query (per instance, every 1–2s, V9 §70):
--   SELECT `account_id`, `disabled`, `security_version`, `change_seq`
--     FROM `account_security_state`
--    WHERE `change_seq` > :lastSeenSeq
--    ORDER BY `change_seq`
--    LIMIT :batch;
--
-- `security_version` is a per-account counter used to invalidate any cached
-- decision that is not keyed purely on `accountId` (V9 §25: `sid` is currently
-- audit-only because `LocalSecurityStateCache` is indexed by `accountId`).

CREATE TABLE IF NOT EXISTS `security_state_sequence` (
  `id` TINYINT NOT NULL DEFAULT 1 COMMENT '恒为 1；单行计数器表',
  `seq` BIGINT NOT NULL DEFAULT 0 COMMENT '全局单调游标；每个实例记录自己最后见到的值',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近一次分配时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_security_state_sequence_single_row` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安全状态变更游标分配器（单行）';

INSERT INTO `security_state_sequence` (`id`, `seq`)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `account_security_state` (
  `account_id` BIGINT NOT NULL COMMENT '账号ID，关联 sys_user.id',
  `disabled` TINYINT NOT NULL DEFAULT 0 COMMENT '封禁标记：0=正常，1=已封禁；Gateway 与各宿主的本地 deny cache 以此为准',
  `security_version` BIGINT NOT NULL DEFAULT 0 COMMENT '账号安全版本号；每次封禁/解封递增，用于使非 accountId 维度的缓存失效',
  `change_seq` BIGINT NOT NULL COMMENT '全局单调变更游标；per_instance reconciler 按它增量拉取',
  `disabled_at` DATETIME(3) NULL COMMENT '最近一次封禁时间；用于 admin_ban_* 延迟指标的分母',
  `reason` VARCHAR(255) NULL COMMENT '封禁原因，来自管理员操作',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`account_id`),
  KEY `idx_security_state_change_seq` (`change_seq`),
  KEY `idx_security_state_disabled` (`disabled`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号安全状态（封禁 SOT）';
