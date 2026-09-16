-- Adds durable approval state for high-impact agent tool invocations.
ALTER TABLE `agent_run`
  MODIFY COLUMN `status` VARCHAR(24) NOT NULL
  COMMENT '运行状态：RUNNING执行中/WAITING_APPROVAL等待用户审批/COMPLETED正常收口/FAILED异常收口';

ALTER TABLE `agent_tool_invocation`
  MODIFY COLUMN `status` VARCHAR(24) NOT NULL
  COMMENT '调用状态：RUNNING执行中/WAITING_APPROVAL等待用户审批/SUCCEEDED已持久化结果/FAILED执行失败/CANCELLED用户取消/UNKNOWN进程中断后需对账';

ALTER TABLE `agent_conversation`
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ready'
  COMMENT '会话状态：ready空闲可对话/running一轮执行中/awaiting_approval等待用户审批/failed';

ALTER TABLE `agent_event`
  MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL
  COMMENT '事件类型：thought/plan/reflection/tool_start/tool_progress/tool_delta/tool_end/tool_error/quiz/artifact/answer_delta/answer/complete/error/approval_required/approval_approved/approval_rejected/approval_executed';

CREATE TABLE IF NOT EXISTS `agent_approval` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审批记录主键',
  `user_id` BIGINT NOT NULL COMMENT '审批所属用户ID，关联 sys_user.id',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `run_id` BIGINT NOT NULL COMMENT '所属执行记录ID，关联 agent_run.id',
  `invocation_db_id` BIGINT NOT NULL COMMENT '关联的工具调用日志主键，关联 agent_tool_invocation.id',
  `invocation_id` CHAR(36) NOT NULL COMMENT '工具调用稳定UUID，批准后继续使用且不重新生成',
  `tool_name` VARCHAR(64) NOT NULL COMMENT '等待审批的注册工具名称',
  `request_fingerprint` CHAR(64) NOT NULL COMMENT '工具名称与参数的SHA-256指纹，变更操作必须重新审批',
  `summary_json` LONGTEXT NOT NULL COMMENT '展示给所属用户的工具操作摘要JSON，不含凭据或原始提示词',
  `status` VARCHAR(24) NOT NULL COMMENT '审批状态：PENDING待决定/APPROVED已批准/REJECTED已拒绝',
  `decision_reason` VARCHAR(500) NULL COMMENT '用户拒绝或批准时填写的可选说明',
  `decided_at` DATETIME NULL COMMENT '用户作出审批决定的时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批请求持久化时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '审批记录最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_approval_invocation` (`invocation_db_id`),
  KEY `idx_agent_approval_user_status` (`user_id`, `status`, `id`),
  KEY `idx_agent_approval_conversation` (`conversation_id`, `id`),
  CONSTRAINT `fk_agent_approval_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_approval_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_approval_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_approval_invocation` FOREIGN KEY (`invocation_db_id`) REFERENCES `agent_tool_invocation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体高风险工具审批记录';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260903_agent_harness_approval');
