-- Add subscription tier column to sys_user.
-- Marker: 20260815_sys_user_tier
--
-- 背景：用户需要订阅等级字段，用于区分 FREE, PLUS, PRO, VIP 等不同订阅层级。
-- 线上历史数据默认为 FREE。
-- Apply manually against the live database after the app code has been deployed.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

ALTER TABLE `sys_user`
  ADD COLUMN `tier` VARCHAR(20) NOT NULL DEFAULT 'FREE' COMMENT '订阅等级: FREE, PLUS, PRO, VIP' AFTER `role`;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260815_sys_user_tier');

COMMIT;
