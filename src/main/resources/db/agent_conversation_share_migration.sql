-- Add share_token column to agent_conversation for public sharing.
-- Marker: 20260815_agent_conversation_share
--
-- 背景：支持将智能体对话生成公开分享链接，无需登录即可查看。
-- share_token 为 UUID，启用分享时生成，禁用时置 NULL。
-- Apply manually against the live database after the app code has been deployed.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

ALTER TABLE `agent_conversation`
  ADD COLUMN `share_token` VARCHAR(64) NULL DEFAULT NULL COMMENT '分享令牌，启用分享时生成 UUID，禁用时置 NULL' AFTER `cancel_requested`,
  ADD UNIQUE KEY `uk_share_token` (`share_token`);

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260815_agent_conversation_share');

COMMIT;
