-- B5：用户意图周期（intent_epoch）+ 自动 resume 安全阀。
--
-- 依赖 agent_harness_schema.sql 与 20260925_agent_run_budget_migration.sql。

ALTER TABLE `agent_run`
  ADD COLUMN `intent_epoch` CHAR(36) NULL
    COMMENT '用户意图周期标识(UUID)：一次用户诉求一个周期。claim() 铸新的，resume() 复用旧的——这是"同一件事的重试"与"新的一件事"的分界。不参与 run_version 的递增逻辑，两者正交' AFTER `run_version`,
  ADD COLUMN `auto_resumed_at` DATETIME NULL
    COMMENT '最近一次被调度器自动 resume 的时间；非空表示"这个Run已经自动恢复过一次"。安全阀：再崩不再自动重试（防止坏工具导致无限循环），由运维或用户显式介入' AFTER `budget_json`;

-- 存量数据不回填 intent_epoch。
--
-- 老 Run 没有周期标识，凭猜测补一个会把"这次重试属于哪次诉求"写错，而那是幂等采纳的判据。
-- 新写入由 AgentExecutionJournal.startRun 铸值。**读侧必须容忍 NULL**：
-- AgentRunStaleReaper 对 intent_epoch 为空的 Run 只标记不自动 resume（见其类注释）。

-- 索引：resume 与自动 resume 都按 (conversation_id, run_version) 定位单行，
-- 已由 uk_agent_run_conversation_version 覆盖，本批不加新索引。
-- 自动 resume 的候选筛选复用 idx_agent_run_status_updated (status, updated_at)。

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260925_agent_intent_epoch');
