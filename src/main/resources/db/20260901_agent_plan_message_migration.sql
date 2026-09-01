-- 任务计划是会话时间线中的持久化消息，正文保存计划项 JSON 数组。
ALTER TABLE `agent_message`
  MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/plan（任务计划卡片，正文为JSON数组）/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）';

-- 同一轮用户提问内多次更新 plan_tasks 时，更新同一张计划卡片而不是新增卡片。
SET @agent_message_run_version_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_message'
    AND COLUMN_NAME = 'run_version'
);
SET @agent_message_run_version_sql = IF(
  @agent_message_run_version_exists = 0,
  'ALTER TABLE `agent_message` ADD COLUMN `run_version` BIGINT NULL COMMENT ''创建该消息的会话执行代次；同一代次的计划更新同一张计划卡片'' AFTER `conversation_id`',
  'SELECT 1'
);
PREPARE stmt_agent_message_run_version FROM @agent_message_run_version_sql;
EXECUTE stmt_agent_message_run_version;
DEALLOCATE PREPARE stmt_agent_message_run_version;

SET @agent_message_plan_run_index_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_message'
    AND INDEX_NAME = 'idx_agent_message_conversation_run_kind'
);
SET @agent_message_plan_run_index_sql = IF(
  @agent_message_plan_run_index_exists = 0,
  'ALTER TABLE `agent_message` ADD KEY `idx_agent_message_conversation_run_kind` (`conversation_id`, `run_version`, `kind`)',
  'SELECT 1'
);
PREPARE stmt_agent_message_plan_run_index FROM @agent_message_plan_run_index_sql;
EXECUTE stmt_agent_message_plan_run_index;
DEALLOCATE PREPARE stmt_agent_message_plan_run_index;
