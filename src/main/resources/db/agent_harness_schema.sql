-- Agent Harness execution journal baseline. Apply after agent_conversation_migration.sql.
CREATE TABLE IF NOT EXISTS `agent_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '执行记录主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，冗余保存以支持按用户审计与授权过滤',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `run_version` BIGINT NOT NULL COMMENT '会话执行代次；同一会话内唯一，对应一次成功领取',
  `status` VARCHAR(24) NOT NULL COMMENT '运行状态：RUNNING执行中/COMPLETED正常收口/FAILED异常收口',
  `intent_json` LONGTEXT NULL COMMENT '预留的结构化意图判定快照；第一阶段为空，后续不得保存原始敏感提示词',
  `skills_json` LONGTEXT NULL COMMENT '预留的激活技能与版本快照；第一阶段为空',
  `execution_snapshot_json` LONGTEXT NULL COMMENT '预留的模型路由与预算快照；第一阶段为空',
  `failure_reason` VARCHAR(1000) NULL COMMENT '失败收口的安全摘要，不保存调用栈或凭据',
  `started_at` DATETIME NOT NULL COMMENT '运行领取成功后的开始时间',
  `completed_at` DATETIME NULL COMMENT '运行进入终态的时间；执行中为空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_conversation_version` (`conversation_id`, `run_version`),
  KEY `idx_agent_run_user_created` (`user_id`, `created_at`),
  KEY `idx_agent_run_status_updated` (`status`, `updated_at`),
  CONSTRAINT `fk_agent_run_conversation` FOREIGN KEY (`conversation_id`)
    REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_run_user` FOREIGN KEY (`user_id`)
    REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体单轮执行事实日志';

CREATE TABLE IF NOT EXISTS `agent_step` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '步骤记录主键',
  `run_id` BIGINT NOT NULL COMMENT '所属执行记录ID，关联 agent_run.id',
  `step_no` INT NOT NULL COMMENT '本轮模型决策顺序，从1开始递增',
  `status` VARCHAR(24) NOT NULL COMMENT '步骤状态：RUNNING模型调用前已落意图/COMPLETED已得到决策/FAILED调用失败/CANCELLED用户取消',
  `request_snapshot_json` LONGTEXT NULL COMMENT '请求元数据快照，只含上下文长度、工具集合哈希和附件计数等非原始内容',
  `decision_json` LONGTEXT NULL COMMENT '模型解析后的结构化决策，供审计下一步工具或最终回复',
  `model_name` VARCHAR(160) NULL COMMENT '实际响应模型名称，未返回时为空',
  `input_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '本步骤模型输入Token数，网关未返回时为0',
  `cached_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '本步骤命中缓存的输入Token数，网关未返回时为0',
  `output_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '本步骤模型输出Token数，网关未返回时为0',
  `failure_reason` VARCHAR(1000) NULL COMMENT '步骤失败或取消的安全摘要',
  `started_at` DATETIME NOT NULL COMMENT '远端模型调用前的落库时间',
  `completed_at` DATETIME NULL COMMENT '步骤完成、失败或取消的时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_step_run_number` (`run_id`, `step_no`),
  KEY `idx_agent_step_status_updated` (`status`, `updated_at`),
  CONSTRAINT `fk_agent_step_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体模型决策步骤事实日志';

CREATE TABLE IF NOT EXISTS `agent_tool_invocation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '工具调用记录主键',
  `run_id` BIGINT NOT NULL COMMENT '所属执行记录ID，关联 agent_run.id',
  `step_id` BIGINT NOT NULL COMMENT '触发该调用的模型步骤ID，关联 agent_step.id',
  `invocation_id` CHAR(36) NOT NULL COMMENT '工具调用稳定UUID；未来作为外部副作用的业务幂等键',
  `tool_name` VARCHAR(64) NOT NULL COMMENT '已请求的注册工具名称',
  `arguments_json` LONGTEXT NULL COMMENT '模型提交给工具的结构化参数，仅限私有审计与恢复使用',
  `status` VARCHAR(24) NOT NULL COMMENT '调用状态：RUNNING执行中/SUCCEEDED已持久化结果/FAILED执行失败/CANCELLED用户取消',
  `result_json` LONGTEXT NULL COMMENT '工具返回的标准化结构化结果',
  `error_message` VARCHAR(1000) NULL COMMENT '工具失败或取消的安全摘要，不保存堆栈',
  `started_at` DATETIME NOT NULL COMMENT '工具执行前意图持久化时间',
  `completed_at` DATETIME NULL COMMENT '工具结果、失败或取消的持久化时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool_invocation_id` (`invocation_id`),
  KEY `idx_agent_tool_invocation_run_created` (`run_id`, `created_at`),
  KEY `idx_agent_tool_invocation_status_updated` (`status`, `updated_at`),
  CONSTRAINT `fk_agent_tool_invocation_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_tool_invocation_step` FOREIGN KEY (`step_id`) REFERENCES `agent_step` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体工具调用事实日志';
