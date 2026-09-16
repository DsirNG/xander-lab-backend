-- One-time correction for email reminder rows written while Connector/J used
-- GMT+8 against a MySQL server whose session time zone was UTC.
--
-- Run this before starting a build that forces JDBC sessions to UTC. The
-- migration marker makes repeated execution safe.
CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL,
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;

SET @email_reminder_utc_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260724_email_reminder_utc'
);

UPDATE `email_reminder_task`
SET `scheduled_at` = `scheduled_at` - INTERVAL 8 HOUR,
    `claimed_at` = IFNULL(`claimed_at` - INTERVAL 8 HOUR, NULL),
    `sent_at` = IFNULL(`sent_at` - INTERVAL 8 HOUR, NULL),
    `deleted_at` = IFNULL(`deleted_at` - INTERVAL 8 HOUR, NULL),
    `created_at` = `created_at` - INTERVAL 8 HOUR,
    `updated_at` = `updated_at` - INTERVAL 8 HOUR
WHERE @email_reminder_utc_migrated = 0;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260724_email_reminder_utc');

COMMIT;
