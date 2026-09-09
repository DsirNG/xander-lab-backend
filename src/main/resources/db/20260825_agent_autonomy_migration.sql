-- 智能体自主性升级：会话级任务计划状态 + 跨会话长期记忆。
-- 手工执行；列检查与 CREATE TABLE IF NOT EXISTS 保证重复执行安全。

-- 1. agent_conversation 增加智能体自己维护的任务计划快照。
SET @plan_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_conversation'
    AND COLUMN_NAME = 'plan_json'
);
SET @plan_column_sql := IF(
  @plan_column_exists = 0,
  'ALTER TABLE `agent_conversation` ADD COLUMN `plan_json` LONGTEXT NULL COMMENT ''智能体自主维护的任务计划JSON，元素含标题、状态与备注'' AFTER `cancel_requested`',
  'SELECT 1'
);
PREPARE stmt_agent_plan_column FROM @plan_column_sql;
EXECUTE stmt_agent_plan_column;
DEALLOCATE PREPARE stmt_agent_plan_column;

-- 2. 事件类型注释补充计划与反思事件，便于运维直接读表理解链路。
SET @event_comment_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'agent_event'
     AND COLUMN_NAME = 'event_type') = 1,
  'ALTER TABLE `agent_event` MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL COMMENT ''事件类型：thought/plan/reflection/tool_start/tool_progress/tool_delta/tool_end/tool_error/answer_delta/answer/complete/error''',
  'SELECT 1'
);
PREPARE stmt_agent_event_type_autonomy FROM @event_comment_sql;
EXECUTE stmt_agent_event_type_autonomy;
DEALLOCATE PREPARE stmt_agent_event_type_autonomy;

-- 3. 消息类型注释补充自检消息；kind 是自由文本列，只需同步注释。
SET @message_kind_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'agent_message'
     AND COLUMN_NAME = 'kind') = 1,
  'ALTER TABLE `agent_message` MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT ''消息类型：message/thought/reflection/tool_call/tool_result/answer''',
  'SELECT 1'
);
PREPARE stmt_agent_message_kind_autonomy FROM @message_kind_sql;
EXECUTE stmt_agent_message_kind_autonomy;
DEALLOCATE PREPARE stmt_agent_message_kind_autonomy;

-- 4. 跨会话长期记忆：一条记忆只描述一件事，memory_key 让智能体更新而不是重复写入。
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记忆主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `memory_key` VARCHAR(120) NOT NULL COMMENT '记忆稳定标识，同一用户内唯一，便于更新而非重复写入',
  `kind` VARCHAR(24) NOT NULL DEFAULT 'FACT' COMMENT '记忆类型：PROFILE用户画像/PREFERENCE偏好/FACT长期事实/PROJECT在办事项',
  `content` VARCHAR(2000) NOT NULL COMMENT '记忆正文，只描述一件长期有效的事',
  `media_asset_id` BIGINT NULL COMMENT '关联用户私有图片素材ID，记忆图片时使用',
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
VALUES ('20260825_agent_autonomy');
