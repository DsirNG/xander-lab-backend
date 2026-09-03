-- Orchestration agent conversation feature: conversations, messages, events.
-- Apply manually. CREATE TABLE IF NOT EXISTS keeps repeated runs safe; the
-- marker still records that this feature's migration was applied.
CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移应用时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

CREATE TABLE IF NOT EXISTS `agent_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `title` VARCHAR(120) NOT NULL COMMENT '会话标题，取自首条用户输入摘要',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ready' COMMENT '会话状态：ready空闲可对话/running一轮执行中/awaiting_approval等待用户审批/failed',
  `error_message` VARCHAR(1000) NULL COMMENT '最近一轮失败原因',
  `run_version` BIGINT NOT NULL DEFAULT 0 COMMENT '当前执行代次；每次成功领取新一轮后递增，用于拒绝旧工作线程迟到写入',
  `cancel_requested` TINYINT NOT NULL DEFAULT 0 COMMENT '用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮执行开始时复位',
  `plan_json` LONGTEXT NULL COMMENT '智能体自主维护的任务计划JSON，元素含标题、状态与备注',
  `is_pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '置顶标记：0=普通会话，1=置顶会话；列表先按置顶分组再按更新时间倒序',
  `last_read_at` DATETIME NULL COMMENT '用户最近一次打开该会话的时间；早于 last_completed_at 表示有未读的生成结果',
  `last_completed_at` DATETIME NULL COMMENT '最近一轮执行到达终态（成功/失败/中断收口）的时间；与 last_read_at 比较得出未读提醒',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_conversation_user_created` (`user_id`, `created_at`),
  KEY `idx_agent_conversation_user_pinned_updated` (`user_id`, `is_pinned`, `updated_at`),
  CONSTRAINT `fk_agent_conversation_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话会话';

CREATE TABLE IF NOT EXISTS `agent_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `role` VARCHAR(16) NOT NULL COMMENT '消息角色：user/assistant/tool',
  `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）/artifact（下发的代码交付卡，正文为JSON）/compaction（上下文压缩检查点，正文为摘要文本，是对话记录的地板：它之前的消息不再进入模型上下文，前端不渲染）',
  `tool_name` VARCHAR(64) NULL COMMENT '工具名，仅 kind=tool_call/tool_result 时非空',
  `content` LONGTEXT NOT NULL COMMENT '消息正文；tool 记录为 JSON',
  `attachments_json` LONGTEXT NULL COMMENT '用户消息附件快照JSON，包含URL、原始文件名、MIME类型和字节数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_message_conversation_created` (`conversation_id`, `created_at`),
  CONSTRAINT `fk_agent_message_conversation`
    FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话消息';

CREATE TABLE IF NOT EXISTS `agent_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件游标ID，供断线续传使用',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `run_version` BIGINT NOT NULL COMMENT '事件所属执行代次；断线续传固定在该代次内回放',
  `event_type` VARCHAR(24) NOT NULL COMMENT '事件类型：thought/plan/reflection/tool_start/tool_progress/tool_delta/tool_end/tool_error/quiz/artifact/answer_delta/answer/complete/error/approval_required/approval_approved/approval_rejected/approval_executed',
  `data_content` LONGTEXT NOT NULL COMMENT 'SSE 事件数据，完整对象使用 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_event_conversation_run_id` (`conversation_id`, `run_version`, `id`),
  CONSTRAINT `fk_agent_event_conversation`
    FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体会话可续传事件日志';

CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记忆主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `memory_key` VARCHAR(120) NOT NULL COMMENT '记忆稳定标识，同一用户内唯一，便于更新而非重复写入',
  `kind` VARCHAR(24) NOT NULL DEFAULT 'FACT' COMMENT '记忆类型：PROFILE用户画像/PREFERENCE偏好/FACT长期事实/PROJECT在办事项',
  `content` VARCHAR(2000) NOT NULL COMMENT '记忆正文，只描述一件长期有效的事',
  `source_conversation_id` BIGINT NULL COMMENT '首次写入该记忆的会话ID，便于追溯来源',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '被召回次数，用于优先注入高频记忆',
  `last_used_at` DATETIME NULL COMMENT '最近一次被召回的时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_user_key` (`user_id`, `memory_key`),
  KEY `idx_agent_memory_user_updated` (`user_id`, `updated_at`),
  CONSTRAINT `fk_agent_memory_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体跨会话长期记忆';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_agent_conversation');
