-- Scheduled content plans: multi-time daily triggers and a daily topic queue.
-- Apply manually. Existing databases receive the new columns via ALTER TABLE;
-- fresh installs already include them in blog_content_plan_schema.sql.

ALTER TABLE `blog_content_plan`
  ADD COLUMN `trigger_times_json` VARCHAR(500) NULL COMMENT 'JSON 数组：每日多个触发时刻 HH:mm（多时间段模式）' AFTER `trigger_time`,
  ADD COLUMN `topics_json` TEXT NULL COMMENT 'JSON 数组：多天主题队列，依次使用，最后一个后回到第一个' AFTER `trigger_times_json`,
  ADD COLUMN `next_topic_index` INT NULL DEFAULT 0 COMMENT '主题队列下一个待用下标（0 起，循环使用）' AFTER `topics_json`;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_blog_content_plan_multi_time');
