-- Run once for existing xander_lab databases before enabling the blog media library.
CREATE TABLE IF NOT EXISTS `blog_media_asset` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `url` VARCHAR(1024) NOT NULL COMMENT '媒体访问地址',
  `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `stored_name` VARCHAR(255) DEFAULT NULL COMMENT '存储文件名',
  `size` BIGINT NOT NULL COMMENT '文件大小（字节）',
  `content_type` VARCHAR(100) NOT NULL COMMENT 'MIME 类型',
  `width` INT DEFAULT NULL COMMENT '图片宽度（像素）',
  `height` INT DEFAULT NULL COMMENT '图片高度（像素）',
  `source_type` VARCHAR(32) NOT NULL DEFAULT 'user_upload' COMMENT '来源类型：user_upload/agent_generate 等',
  `agent_task_id` BIGINT DEFAULT NULL COMMENT '关联 blog_agent_task.id（AI 生成时）',
  `generation_meta` TEXT DEFAULT NULL COMMENT '生成元数据 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_media_user_created` (`user_id`, `created_at`),
  KEY `idx_blog_media_user_type` (`user_id`, `content_type`),
  KEY `idx_blog_media_agent_task` (`agent_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客编辑器媒体素材';
