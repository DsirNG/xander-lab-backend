-- 工具消息带上这次调用的持久化身份，供前端把 tool_call 与 tool_result 精确配对。
--
-- 为什么必须单开一列而不是塞进 content：content 会被原样渲染进提示词
-- （AgentRequestAssembler 的「【工具结果】」），把 UUID 写进去等于每一条工具结果都
-- 多烧几个 token，还会让模型以为这个字段需要它自己维护。身份是给人看的数据，
-- 不该混进给模型看的观察里。
--
-- 为什么现在才需要：一步只跑一个工具时，工具名就是天然的身份，按名字配对够用。
-- 支持并行批次之后，同一步里出现两个同名工具是合法且常见的（一次查两个主题的知识点），
-- 按名字配对会把第二个的入参折进第一张卡。列上身份之后配对不再有歧义。
--
-- 可空：历史消息没有这个字段，非工具消息（message/answer/thought/plan/quiz）也永远为空。
-- 前端读不到时回退到按工具名配对，所以旧数据的行为与改动前逐字节一致。
SET @agent_message_invocation_id_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_message'
    AND COLUMN_NAME = 'invocation_id'
);
SET @agent_message_invocation_id_sql = IF(
  @agent_message_invocation_id_exists = 0,
  'ALTER TABLE `agent_message` ADD COLUMN `invocation_id` VARCHAR(64) NULL COMMENT ''这次工具调用的持久化身份，关联agent_tool_invocation.invocation_id；仅kind=tool_call/tool_result时非空，用于把一次调用的入参与结果配对'' AFTER `tool_name`',
  'SELECT 1'
);
PREPARE stmt_agent_message_invocation_id FROM @agent_message_invocation_id_sql;
EXECUTE stmt_agent_message_invocation_id;
DEALLOCATE PREPARE stmt_agent_message_invocation_id;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260914_agent_message_invocation_id');
