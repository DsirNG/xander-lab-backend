CREATE TABLE IF NOT EXISTS `blog_agent_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件游标ID，供断线续传使用',
  `task_id` BIGINT NOT NULL COMMENT '所属博客智能体任务ID，关联 blog_agent_task.id',
  `event_type` VARCHAR(24) NOT NULL COMMENT '事件类型：stage/delta/complete/error',
  `data_content` LONGTEXT NOT NULL COMMENT 'SSE 事件数据，完整对象使用 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_event_task_id` (`task_id`, `id`),
  CONSTRAINT `fk_blog_agent_event_task`
    FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客智能体可续传事件日志';
