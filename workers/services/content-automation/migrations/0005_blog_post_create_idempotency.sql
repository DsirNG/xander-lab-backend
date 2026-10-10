-- 发布幂等键：同一用户使用相同键重试时复用同一篇文章。
ALTER TABLE blog_post ADD COLUMN idempotency_key TEXT;
-- 请求摘要：识别同一个幂等键被用于不同文章内容的冲突。
ALTER TABLE blog_post ADD COLUMN request_hash TEXT;

CREATE UNIQUE INDEX idx_blog_post_user_idempotency
  ON blog_post(user_id, idempotency_key);
