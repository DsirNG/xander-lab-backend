CREATE TABLE IF NOT EXISTS email_reminder_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  client_request_id TEXT,
  recipient_email TEXT NOT NULL,
  subject TEXT NOT NULL,
  message TEXT NOT NULL,
  content_type TEXT NOT NULL DEFAULT 'PLAIN' CHECK (content_type IN ('PLAIN', 'HTML')),
  template_id TEXT NOT NULL DEFAULT 'none',
  frequency TEXT NOT NULL DEFAULT 'ONCE' CHECK (frequency IN ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY', 'CUSTOM')),
  timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  send_time TEXT,
  recurrence_day INTEGER,
  interval_days INTEGER,
  scheduled_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAUSED', 'SENDING', 'SENT', 'FAILED')),
  send_attempts INTEGER NOT NULL DEFAULT 0 CHECK (send_attempts >= 0),
  error_message TEXT,
  processing_token TEXT,
  claimed_at TEXT,
  sent_at TEXT,
  deleted_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (user_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_email_reminder_user_created
  ON email_reminder_task(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_email_reminder_due
  ON email_reminder_task(status, scheduled_at, id);
