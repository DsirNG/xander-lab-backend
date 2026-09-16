-- 智能体答题卡：kind 与 event_type 的取值注释补齐。
-- Marker: 20260831_agent_quiz_card
--
-- 背景：测验从"模型把题目写在回复正文里"改成下发一张结构化答题卡。
-- agent_message 多了两种 kind：quiz（下发的答题卡，正文是给前端渲染的 JSON）、
-- quiz_answer（用户在卡片上提交的逐题作答，入口已翻译成纯文本）；
-- agent_event 多了一种 event_type：quiz（本轮把卡片推给前端，不是终态事件）。
-- 两列都是 VARCHAR(24) 且无枚举约束，新取值不需要改类型，只需把注释里的取值表补齐，
-- 否则线上库的字典与代码不一致。手工执行；只改注释，重复执行安全。

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

-- 1. 消息类型：补上 quiz / quiz_answer。
ALTER TABLE `agent_message`
  MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）';

-- 2. 事件类型：在工具事件之后补上 quiz；它不是终态事件，续传时和 thought 一样只做回放。
ALTER TABLE `agent_event`
  MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL COMMENT '事件类型：thought/plan/reflection/tool_start/tool_progress/tool_delta/tool_end/tool_error/quiz/answer_delta/answer/complete/error';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260831_agent_quiz_card');
