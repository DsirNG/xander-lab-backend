-- 智能体交付卡：kind 与 event_type 的取值注释补齐。
-- Marker: 20260902_agent_artifact_card
--
-- 背景：模型经常只在回复里写一句"已完成 XX 示例"，用户翻遍回复找不到任何代码。
-- 新增 emit_artifact 工具后，代码作为一张交付卡下发：agent_message 多了一种 kind
-- artifact（文件树 + 完整源码，正文是给前端渲染的 JSON）；agent_event 多了一种
-- event_type artifact（本轮把卡片推给前端，不是终态事件）。
-- 两列都是 VARCHAR(24) 且无枚举约束，content/data_content 都是 LONGTEXT，
-- 新取值不需要改类型，只需把注释里的取值表补齐，否则线上库的字典与代码不一致。
-- 手工执行；只改注释，重复执行安全。

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

-- 1. 消息类型：在答题卡之后补上 artifact。
-- 注意：重述整条取值表时，**早于本迁移落地的取值必须原样保留**。plan（任务计划卡片）
-- 是 20260901 引入的，它的正文是 JSON 数组；漏掉它线上字典就少一种真实取值，
-- 而 kind 无枚举约束，谁也不会因此报错——只会让运维按注释排查时对不上代码。
ALTER TABLE `agent_message`
  MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/plan（任务计划卡片，正文为JSON数组）/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）/artifact（下发的代码交付卡，正文为JSON）';

-- 2. 事件类型：在答题卡之后补上 artifact；它不是终态事件，续传时和 quiz 一样只做回放。
ALTER TABLE `agent_event`
  MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL COMMENT '事件类型：thought/plan/reflection/tool_start/tool_progress/tool_delta/tool_end/tool_error/quiz/artifact/answer_delta/answer/complete/error';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260902_agent_artifact_card');
