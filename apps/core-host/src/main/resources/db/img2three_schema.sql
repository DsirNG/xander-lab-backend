-- Image-to-procedural-Three.js durable tasks.
CREATE TABLE IF NOT EXISTS `img2three_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `status` VARCHAR(16) NOT NULL DEFAULT 'created' COMMENT 'created/running/ready/failed',
  `stage` VARCHAR(32) NOT NULL DEFAULT 'analyze' COMMENT 'analyze/spec/factory',
  `run_token` VARCHAR(36) NULL COMMENT '当前执行代次令牌，用于隔离超时重试与旧工作线程',
  `title` VARCHAR(255) NULL COMMENT '生成标题',
  `reference_media_url` VARCHAR(2000) NOT NULL COMMENT '参考图 URL',
  `reference_media_asset_id` BIGINT NULL COMMENT '关联 blog_media_asset.id',
  `analysis_json` LONGTEXT NULL COMMENT '视觉分析 JSON',
  `spec_json` LONGTEXT NULL COMMENT '受限 sceneSpec JSON（前端安全预览）',
  `factory_code` LONGTEXT NULL COMMENT '可下载的 TypeScript 工厂源码',
  `error_message` VARCHAR(1000) NULL COMMENT '失败原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_img2three_task_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图片转过程式 Three.js 任务';
