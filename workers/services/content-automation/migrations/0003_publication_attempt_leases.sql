-- Fencing token and lease prevent overlapping retries from overwriting a newer attempt.
ALTER TABLE blog_publish_record ADD COLUMN attempt_token TEXT;
ALTER TABLE blog_publish_record ADD COLUMN lease_until TEXT;

CREATE INDEX IF NOT EXISTS idx_blog_publish_pending_lease
  ON blog_publish_record(status, lease_until);
