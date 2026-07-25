-- Run once for databases where blog_agent_schema.sql was already applied.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `published_post_id` BIGINT NULL COMMENT '已发布文章ID，关联 blog_post.id' AFTER `review`;
