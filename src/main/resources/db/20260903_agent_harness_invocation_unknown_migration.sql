-- Existing journal upgrades: document UNKNOWN as the durable reconciliation state.
-- The status column is VARCHAR, so this migration changes metadata only and remains safe for old rows.
ALTER TABLE `agent_tool_invocation`
  MODIFY COLUMN `status` VARCHAR(24) NOT NULL
  COMMENT '调用状态：RUNNING执行中/SUCCEEDED已持久化结果/FAILED执行失败/CANCELLED用户取消/UNKNOWN进程中断后需对账';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260903_agent_harness_invocation_unknown');
