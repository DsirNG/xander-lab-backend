-- Scheduled email reminders.
-- The application forces every JDBC session to Asia/Shanghai. TIMESTAMP keeps
-- the absolute instant while database queries display the selected local time.
-- API values always include an explicit ISO-8601 offset.
--
-- frequency:
--   ONCE     -> scheduled_at is the only fire time
--   DAILY    -> send_time (HH:mm) in timezone
--   WEEKLY   -> recurrence_day (ISO 1=Mon..7=Sun) + send_time
--   MONTHLY  -> recurrence_day (1-31) + send_time
--   CUSTOM   -> interval_days + send_time (every N days at that local time)
CREATE TABLE IF NOT EXISTS `email_reminder_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `client_request_id` VARCHAR(64) NULL,
  `recipient_email` VARCHAR(254) NOT NULL,
  `subject` VARCHAR(200) NOT NULL,
  `message` TEXT NOT NULL,
  `content_type` VARCHAR(16) NOT NULL DEFAULT 'PLAIN',
  `template_id` VARCHAR(32) NOT NULL DEFAULT 'classic',
  `frequency` VARCHAR(16) NOT NULL DEFAULT 'ONCE',
  `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  `send_time` VARCHAR(5) NULL,
  `recurrence_day` INT NULL,
  `interval_days` INT NULL,
  `scheduled_at` TIMESTAMP(3) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `send_attempts` INT UNSIGNED NOT NULL DEFAULT 0,
  `error_message` VARCHAR(1000) NULL,
  `processing_token` CHAR(36) NULL,
  `claimed_at` TIMESTAMP(3) NULL,
  `sent_at` TIMESTAMP(3) NULL,
  `deleted_at` TIMESTAMP(3) NULL,
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email_reminder_user_request` (`user_id`, `client_request_id`),
  KEY `idx_email_reminder_user_created` (`user_id`, `created_at`),
  KEY `idx_email_reminder_due` (`status`, `scheduled_at`, `id`),
  CONSTRAINT `fk_email_reminder_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
