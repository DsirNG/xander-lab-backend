-- Scheduled content plans: 掘金 (Juejin) sync support.
-- Plans opt in per-plan with sync_juejin; runs record the best-effort 掘金
-- sync result on the run row (same pattern as the existing CSDN sync).
-- Apply manually. Existing databases receive the new columns via ALTER TABLE;
-- fresh installs already include them in blog_content_plan_schema.sql.

ALTER TABLE `blog_content_plan`
  ADD COLUMN `sync_juejin` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '是否同步发布到掘金（0 否 / 1 是）'
    AFTER `sync_csdn`;

ALTER TABLE `blog_plan_run`
  ADD COLUMN `juejin_status` VARCHAR(16) NULL
    COMMENT '掘金发布状态：PENDING/SUCCEEDED/FAILED/SKIPPED/UNSET'
    AFTER `csdn_url`,
  ADD COLUMN `juejin_error_code` VARCHAR(64) NULL
    COMMENT '掘金发布规范化错误码，如 AUTH_REQUIRED'
    AFTER `juejin_status`,
  ADD COLUMN `juejin_error_message` VARCHAR(1000) NULL
    COMMENT '掘金发布失败原因（脱敏）'
    AFTER `juejin_error_code`,
  ADD COLUMN `juejin_external_id` VARCHAR(128) NULL
    COMMENT '掘金文章 ID'
    AFTER `juejin_error_message`,
  ADD COLUMN `juejin_url` VARCHAR(1000) NULL
    COMMENT '掘金文章 URL'
    AFTER `juejin_external_id`;

ALTER TABLE `blog_notification`
  MODIFY COLUMN `type` VARCHAR(24) NOT NULL
    COMMENT '通知类型：REVIEW_REJECTED/CSDN_FAILED/JUEJIN_FAILED/RUN_FAILED/PLAN_STALLED';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260817_blog_plan_juejin_sync');
