-- One-shot plans end in a terminal state (FINISHED/FAILED) with no next run.
-- finishOneShotPlan / failOneShotPlan / recoverStaleOneShot clear next_run_at,
-- which the NOT NULL column forbids: every completed one-shot plan stayed stuck
-- in RUNNING forever (see plans 4/11). Apply manually.
-- Fresh installs already include NULL in blog_content_plan_schema.sql.

ALTER TABLE `blog_content_plan`
  MODIFY COLUMN `next_run_at` TIMESTAMP(3) NULL DEFAULT NULL
    COMMENT '下次计划触发时间（UTC 绝对时刻；一次性计划完结/失败后为空）',
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '计划状态：ACTIVE/RUNNING/PAUSED/CANCELLED/FINISHED/FAILED（一次性计划执行成功进入 FINISHED，失败进入 FAILED）';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260814_blog_content_plan_next_run_nullable');
