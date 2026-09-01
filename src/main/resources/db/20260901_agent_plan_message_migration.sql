-- 任务计划是会话时间线中的持久化消息，正文保存计划项 JSON 数组。
ALTER TABLE `agent_message`
  MODIFY COLUMN `kind` VARCHAR(24) NOT NULL COMMENT '消息类型：message/thought/reflection/tool_call/tool_result/answer/plan（任务计划卡片，正文为JSON数组）/quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）';
