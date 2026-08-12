-- Orchestration agent conversation feature: conversations, messages, events.
-- Apply manually. CREATE TABLE IF NOT EXISTS keeps repeated runs safe; the
-- marker still records that this feature's migration was applied.
CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL,
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `title` VARCHAR(120) NOT NULL COMMENT '会话标题，取自首条用户输入摘要',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ready' COMMENT '会话状态：ready（空闲可对话）/running（一轮执行中）/failed',
  `error_message` VARCHAR(1000) NULL COMMENT '最近一轮失败原因',
  `cancel_requested` TINYINT NOT NULL DEFAULT 0 COMMENT '用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮执行开始时复位',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_conversation_user_created` (`user_id`, `created_at`),
  CONSTRAINT `fk_agent_conversation_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话会话';

CREATE TABLE IF NOT EXISTS `agent_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `role` VARCHAR(16) NOT NULL COMMENT '消息角色：user/assistant/tool',
  `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/tool_call/tool_result/answer',
  `tool_name` VARCHAR(64) NULL COMMENT '工具名，仅 kind=tool_call/tool_result 时非空',
  `content` LONGTEXT NOT NULL COMMENT '消息正文；tool 记录为 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_message_conversation_created` (`conversation_id`, `created_at`),
  CONSTRAINT `fk_agent_message_conversation`
    FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话消息';

CREATE TABLE IF NOT EXISTS `agent_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件游标ID，供断线续传使用',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `event_type` VARCHAR(24) NOT NULL COMMENT '事件类型：thought/tool_start/tool_progress/tool_end/tool_error/answer/complete/error',
  `data_content` LONGTEXT NOT NULL COMMENT 'SSE 事件数据，完整对象使用 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_event_conversation_id` (`conversation_id`, `id`),
  CONSTRAINT `fk_agent_event_conversation`
    FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体会话可续传事件日志';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_agent_conversation');