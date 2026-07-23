-- Run once for existing xander_lab databases before enabling the blog media library.
CREATE TABLE IF NOT EXISTS `blog_media_asset` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `url` VARCHAR(1024) NOT NULL,
  `original_name` VARCHAR(255) NOT NULL,
  `stored_name` VARCHAR(255) DEFAULT NULL,
  `size` BIGINT NOT NULL,
  `content_type` VARCHAR(100) NOT NULL,
  `width` INT DEFAULT NULL,
  `height` INT DEFAULT NULL,
  `source_type` VARCHAR(32) NOT NULL DEFAULT 'user_upload',
  `agent_task_id` BIGINT DEFAULT NULL,
  `generation_meta` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog_media_user_created` (`user_id`, `created_at`),
  KEY `idx_blog_media_user_type` (`user_id`, `content_type`),
  KEY `idx_blog_media_agent_task` (`agent_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客编辑器媒体素材';
