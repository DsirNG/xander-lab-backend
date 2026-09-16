CREATE TABLE IF NOT EXISTS `blog_agent_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  `role` VARCHAR(16) NOT NULL COMMENT '消息角色：user/assistant/system',
  `kind` VARCHAR(24) NOT NULL COMMENT '消息类型，如 text/status/review',
  `stage` VARCHAR(24) NULL COMMENT '所属阶段，如 analyze/draft/review',
  `content` LONGTEXT NOT NULL COMMENT '消息正文',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_message_task_created` (`task_id`, `created_at`),
  CONSTRAINT `fk_blog_agent_message_task`
    FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客写作 Agent 会话消息';
