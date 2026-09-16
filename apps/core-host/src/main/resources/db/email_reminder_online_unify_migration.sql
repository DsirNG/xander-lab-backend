-- ============================================================
-- 线上统一迁移：定时邮件（频率/时区 + 内容类型/模板）
-- 库：xander_lab
-- 可重复执行：按列存在性判断，已加过的列会自动跳过
-- 覆盖原分文件：
--   email_reminder_recurrence_migration.sql
--   email_reminder_content_template_migration.sql
-- 不含 UTC 纠偏（email_reminder_utc_migration.sql，仅历史数据需要时单独跑）
-- ============================================================

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL,
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;

-- ---------- content_type ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'content_type'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `content_type` VARCHAR(16) NOT NULL DEFAULT ''PLAIN''
     COMMENT ''内容格式：PLAIN/HTML'' AFTER `message`',
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- template_id ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'template_id'
  ),
  IF(
    EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'email_reminder_task'
        AND COLUMN_NAME = 'content_type'
    ),
    'ALTER TABLE `email_reminder_task`
       ADD COLUMN `template_id` VARCHAR(32) NOT NULL DEFAULT ''none''
       COMMENT ''邮件版式模板：none/classic/minimal/card/notice'' AFTER `content_type`',
    'ALTER TABLE `email_reminder_task`
       ADD COLUMN `template_id` VARCHAR(32) NOT NULL DEFAULT ''none''
       COMMENT ''邮件版式模板：none/classic/minimal/card/notice'' AFTER `message`'
  ),
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- frequency ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'frequency'
  ),
  IF(
    EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'email_reminder_task'
        AND COLUMN_NAME = 'template_id'
    ),
    'ALTER TABLE `email_reminder_task`
       ADD COLUMN `frequency` VARCHAR(16) NOT NULL DEFAULT ''ONCE''
       COMMENT ''发送频率：ONCE/DAILY/WEEKLY/MONTHLY/CUSTOM'' AFTER `template_id`',
    'ALTER TABLE `email_reminder_task`
       ADD COLUMN `frequency` VARCHAR(16) NOT NULL DEFAULT ''ONCE''
       COMMENT ''发送频率：ONCE/DAILY/WEEKLY/MONTHLY/CUSTOM'' AFTER `message`'
  ),
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- timezone ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'timezone'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `timezone` VARCHAR(64) NOT NULL DEFAULT ''Asia/Shanghai''
     COMMENT ''IANA 时区，如 Asia/Shanghai'' AFTER `frequency`',
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- send_time ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'send_time'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `send_time` VARCHAR(5) NULL
     COMMENT ''周期发送本地时刻 HH:mm'' AFTER `timezone`',
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- recurrence_day ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'recurrence_day'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `recurrence_day` INT NULL
     COMMENT ''周期日：周=ISO 1-7，月=1-31'' AFTER `send_time`',
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- interval_days ----------
SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'interval_days'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `interval_days` INT NULL
     COMMENT ''自定义间隔天数'' AFTER `recurrence_day`',
  'SELECT 1'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`) VALUES
  ('20260725_email_reminder_recurrence'),
  ('20260725_email_reminder_content_template'),
  ('20260725_email_reminder_online_unify');

COMMIT;

-- 执行后可核对：
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'content_type';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'template_id';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'frequency';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'timezone';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'send_time';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'recurrence_day';
-- SHOW COLUMNS FROM `email_reminder_task` LIKE 'interval_days';
