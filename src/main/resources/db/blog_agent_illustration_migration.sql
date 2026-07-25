-- Run once for databases where the blog agent and media schemas already exist.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `illustration_status` VARCHAR(16) NULL COMMENT '插图生成状态' AFTER `review`,
  ADD COLUMN `illustration_error` VARCHAR(1000) NULL COMMENT '插图生成失败原因' AFTER `illustration_status`;

ALTER TABLE `blog_media_asset`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'user_upload' COMMENT '来源类型：user_upload/agent_generate 等' AFTER `height`,
  ADD COLUMN `agent_task_id` BIGINT NULL COMMENT '关联 blog_agent_task.id（AI 生成时）' AFTER `source_type`,
  ADD COLUMN `generation_meta` TEXT NULL COMMENT '生成元数据 JSON' AFTER `agent_task_id`,
  ADD KEY `idx_blog_media_agent_task` (`agent_task_id`);
