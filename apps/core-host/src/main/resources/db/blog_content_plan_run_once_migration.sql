-- Scheduled content plans: one-shot mode created by "AI 生成计划" (topic + days).
-- One-shot plans fire exactly once at their fixed next_run_at, then auto-finish.
-- Apply manually. Existing databases receive the new column via ALTER TABLE;
-- fresh installs already include it in blog_content_plan_schema.sql.

ALTER TABLE `blog_content_plan`
  ADD COLUMN `run_once` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '一次性计划：到点只执行一次，执行完自动完结（0 每日循环 / 1 一次性）'
    AFTER `sync_csdn`,
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '计划状态：ACTIVE/RUNNING/PAUSED/CANCELLED/FINISHED（一次性计划执行完毕自动进入 FINISHED）';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_blog_content_plan_run_once');
