CREATE TABLE IF NOT EXISTS `blog_publish_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布记录主键',
  `task_id` BIGINT NOT NULL COMMENT '博客 Agent 任务 ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户 ID',
  `platform` VARCHAR(32) NOT NULL COMMENT '发布平台：local/csdn/juejin/wechat 等',
  `external_id` VARCHAR(128) NULL COMMENT '平台文章 ID',
  `url` VARCHAR(1000) NULL COMMENT '平台文章 URL',
  `status` VARCHAR(16) NOT NULL COMMENT '状态：PENDING/SUCCEEDED/FAILED',
  `error_code` VARCHAR(64) NULL COMMENT '规范化错误码',
  `error_message` VARCHAR(1000) NULL COMMENT '安全的失败原因',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `published_at` DATETIME NULL COMMENT '成功发布时间（UTC）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_publish_task_platform` (`task_id`, `platform`),
  KEY `idx_blog_publish_user_created` (`user_id`, `created_at`),
  KEY `idx_blog_publish_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客多平台发布记录';
