-- Blog writing agent: durable task state, evidence, and versions.
CREATE TABLE IF NOT EXISTS `blog_agent_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `input` TEXT NOT NULL,
  `input_type` VARCHAR(16) NOT NULL DEFAULT 'topic',
  `audience` VARCHAR(120) NOT NULL,
  `tone` VARCHAR(60) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'created',
  `stage` VARCHAR(16) NOT NULL DEFAULT 'analyze',
  `title` VARCHAR(255) NULL,
  `summary` TEXT NULL,
  `content` LONGTEXT NULL,
  `outline` TEXT NULL,
  `category_id` VARCHAR(64) NULL,
  `tags_json` TEXT NULL,
  `review` TEXT NULL,
  `error_message` VARCHAR(1000) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_task_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `blog_agent_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `title` VARCHAR(500) NOT NULL,
  `url` VARCHAR(2000) NOT NULL,
  `publisher` VARCHAR(255) NULL,
  `excerpt` TEXT NULL,
  `reliability` VARCHAR(64) NULL,
  `retrieved_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_source_task` (`task_id`),
  CONSTRAINT `fk_blog_agent_source_task` FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `blog_agent_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `version_no` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `summary` TEXT NOT NULL,
  `content` LONGTEXT NOT NULL,
  `change_note` VARCHAR(500) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_agent_version_task_no` (`task_id`, `version_no`),
  CONSTRAINT `fk_blog_agent_version_task` FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
