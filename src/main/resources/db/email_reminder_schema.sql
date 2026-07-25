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
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `client_request_id` VARCHAR(64) NULL COMMENT '客户端幂等请求标识',
  `recipient_email` VARCHAR(254) NOT NULL COMMENT '收件邮箱',
  `subject` VARCHAR(200) NOT NULL COMMENT '邮件主题',
  `message` TEXT NOT NULL COMMENT '邮件正文（纯文本或 HTML）',
  `content_type` VARCHAR(16) NOT NULL DEFAULT 'PLAIN' COMMENT '内容格式：PLAIN/HTML',
  `template_id` VARCHAR(32) NOT NULL DEFAULT 'classic' COMMENT '邮件版式模板：classic/minimal/card/notice',
  `frequency` VARCHAR(16) NOT NULL DEFAULT 'ONCE' COMMENT '发送频率：ONCE/DAILY/WEEKLY/MONTHLY/CUSTOM',
  `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区，如 Asia/Shanghai',
  `send_time` VARCHAR(5) NULL COMMENT '周期发送本地时刻 HH:mm',
  `recurrence_day` INT NULL COMMENT '周期日：周=ISO 1-7，月=1-31',
  `interval_days` INT NULL COMMENT '自定义间隔天数',
  `scheduled_at` TIMESTAMP(3) NOT NULL COMMENT '下次计划发送时间（绝对时刻）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/SENDING/SENT/FAILED/PAUSED 等',
  `send_attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已尝试发送次数',
  `error_message` VARCHAR(1000) NULL COMMENT '最近一次失败原因',
  `processing_token` CHAR(36) NULL COMMENT '发送认领令牌，防并发重复发送',
  `claimed_at` TIMESTAMP(3) NULL COMMENT '认领发送时间',
  `sent_at` TIMESTAMP(3) NULL COMMENT '最近成功发送时间',
  `deleted_at` TIMESTAMP(3) NULL COMMENT '软删除时间',
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email_reminder_user_request` (`user_id`, `client_request_id`),
  KEY `idx_email_reminder_user_created` (`user_id`, `created_at`),
  KEY `idx_email_reminder_due` (`status`, `scheduled_at`, `id`),
  CONSTRAINT `fk_email_reminder_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时邮件提醒任务';
