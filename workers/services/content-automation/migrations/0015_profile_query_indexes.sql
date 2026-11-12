-- 个人文章跨状态分页排序，索引表达式须与实际 ORDER BY 保持一致。
CREATE INDEX IF NOT EXISTS idx_blog_post_owner_display_order
  ON blog_post(user_id, COALESCE(published_at, updated_at) DESC, id DESC);
-- 指定状态（草稿、发布或回收站）的个人文章分页。
CREATE INDEX IF NOT EXISTS idx_blog_post_owner_status_display_order
  ON blog_post(user_id, status, COALESCE(published_at, updated_at) DESC, id DESC);
