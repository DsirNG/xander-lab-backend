-- Run once for databases where the blog agent and media schemas already exist.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `illustration_status` VARCHAR(16) NULL AFTER `review`,
  ADD COLUMN `illustration_error` VARCHAR(1000) NULL AFTER `illustration_status`;

ALTER TABLE `blog_media_asset`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'user_upload' AFTER `height`,
  ADD COLUMN `agent_task_id` BIGINT NULL AFTER `source_type`,
  ADD COLUMN `generation_meta` TEXT NULL AFTER `agent_task_id`,
  ADD KEY `idx_blog_media_agent_task` (`agent_task_id`);
