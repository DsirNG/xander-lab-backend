-- Adds the Agent invocation-backed idempotency key for durable blog task creation.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `client_request_id` VARCHAR(64) NULL COMMENT '创建命令幂等标识，在同一用户内唯一' AFTER `user_id`,
  ADD UNIQUE KEY `uk_blog_agent_task_user_request` (`user_id`, `client_request_id`);
