CREATE TABLE IF NOT EXISTS `blog_agent_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `role` VARCHAR(16) NOT NULL,
  `kind` VARCHAR(24) NOT NULL,
  `stage` VARCHAR(24) NULL,
  `content` LONGTEXT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_message_task_created` (`task_id`, `created_at`),
  CONSTRAINT `fk_blog_agent_message_task`
    FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
