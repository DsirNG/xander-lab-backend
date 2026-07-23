-- Run once for databases where blog_agent_schema.sql was already applied.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `published_post_id` BIGINT NULL AFTER `review`;
