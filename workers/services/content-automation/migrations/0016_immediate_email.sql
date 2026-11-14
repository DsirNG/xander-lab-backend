-- 邮件任务的发送方式：SCHEDULED 为定时提醒，IMMEDIATE 为审批后的即时邮件。
ALTER TABLE email_reminder_task ADD COLUMN delivery_mode TEXT NOT NULL DEFAULT 'SCHEDULED'
  CHECK (delivery_mode IN ('SCHEDULED', 'IMMEDIATE'));
