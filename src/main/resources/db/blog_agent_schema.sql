-- Blog writing agent: durable task state, evidence, and versions.
CREATE TABLE IF NOT EXISTS `blog_agent_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `input` TEXT NOT NULL COMMENT '用户原始输入',
  `input_type` VARCHAR(16) NOT NULL DEFAULT 'topic' COMMENT '输入类型，如 topic/outline',
  `audience` VARCHAR(120) NOT NULL COMMENT '目标读者',
  `tone` VARCHAR(60) NOT NULL COMMENT '文风语气',
  `status` VARCHAR(16) NOT NULL DEFAULT 'created' COMMENT '任务状态：created/running/succeeded/failed 等',
  `stage` VARCHAR(16) NOT NULL DEFAULT 'analyze' COMMENT '当前阶段：analyze/draft/review 等',
  `title` VARCHAR(255) NULL COMMENT '生成文章标题',
  `summary` TEXT NULL COMMENT '生成文章摘要',
  `content` LONGTEXT NULL COMMENT '生成文章正文（Markdown）',
  `outline` TEXT NULL COMMENT '文章大纲',
  `content_boundary` TEXT NULL COMMENT '用户主线、直接扩展与范围边界',
  `knowledge_graph_json` LONGTEXT NULL COMMENT '按需生成的知识关系图谱',
  `category_id` VARCHAR(64) NULL COMMENT '拟发布分类ID，关联 blog_category.id',
  `tags_json` TEXT NULL COMMENT '拟发布标签 JSON 数组',
  `review` TEXT NULL COMMENT '审校意见/结果',
  `illustration_status` VARCHAR(16) NULL COMMENT '插图生成状态',
  `illustration_error` VARCHAR(1000) NULL COMMENT '插图生成失败原因',
  `published_post_id` BIGINT NULL COMMENT '已发布文章ID，关联 blog_post.id',
  `error_message` VARCHAR(1000) NULL COMMENT '任务失败原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_task_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客写作 Agent 任务主表';

CREATE TABLE IF NOT EXISTS `blog_agent_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  `title` VARCHAR(500) NOT NULL COMMENT '来源标题',
  `url` VARCHAR(2000) NOT NULL COMMENT '来源链接',
  `publisher` VARCHAR(255) NULL COMMENT '发布方/站点',
  `excerpt` TEXT NULL COMMENT '摘录摘要',
  `reliability` VARCHAR(64) NULL COMMENT '可信度说明',
  `retrieved_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抓取/收录时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_agent_source_task` (`task_id`),
  CONSTRAINT `fk_blog_agent_source_task` FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客写作 Agent 参考来源';

CREATE TABLE IF NOT EXISTS `blog_agent_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  `version_no` INT NOT NULL COMMENT '版本号（任务内递增）',
  `title` VARCHAR(255) NOT NULL COMMENT '该版本标题',
  `summary` TEXT NOT NULL COMMENT '该版本摘要',
  `content` LONGTEXT NOT NULL COMMENT '该版本正文（Markdown）',
  `change_note` VARCHAR(500) NULL COMMENT '版本变更说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_agent_version_task_no` (`task_id`, `version_no`),
  CONSTRAINT `fk_blog_agent_version_task` FOREIGN KEY (`task_id`) REFERENCES `blog_agent_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客写作 Agent 文章版本';

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
