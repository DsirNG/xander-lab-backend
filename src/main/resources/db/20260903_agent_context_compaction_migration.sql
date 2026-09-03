-- 智能体上下文压缩：agent_message.kind 补上 compaction。
-- Marker: 20260903_agent_context_compaction
--
-- 背景：早先"模型记得什么"只由一道条数窗口决定（最近 30 条），第 31 条一到，
-- 前面的消息就无声无息地没了——用户几十条之前定下的目标、给过的纠正、已经查到的事实
-- 全部消失，智能体于是重复提问、重复调工具，甚至推翻自己答应过的事。
-- 压缩换一种丢法：先让模型把较早那一段读成一份结论式检查点，再用检查点替换那一段。
--
-- 检查点就是一条普通的 agent_message，role=user、kind=compaction，正文是纯文本
-- （前言 + <compacted-summary> 包裹的分节摘要）。它落库后成为对话记录的地板：
-- 装配上下文时只取最近一个 compaction 及其之后的消息，更早的不再进入模型输入。
-- 因此它必须持久化——只活在内存里的话，下一步重新读库就把刚换掉的那段整段读回来。
--
-- kind 是 VARCHAR(24) 且无枚举约束，content 是 LONGTEXT，新取值不需要改类型，
-- 只需把注释里的取值表补齐，否则线上库的字典与代码不一致。
-- agent_event 不需要改：压缩不推任何事件，前端也不渲染它（未知 kind 一律 return null）。
-- 手工执行；只改注释，重复执行安全。

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

-- 消息类型：在交付卡之后补上 compaction。
ALTER TABLE `agent_message`
  MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）/artifact（下发的代码交付卡，正文为JSON）/compaction（上下文压缩检查点，正文为摘要文本，是对话记录的地板：它之前的消息不再进入模型上下文，前端不渲染）';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260903_agent_context_compaction');
