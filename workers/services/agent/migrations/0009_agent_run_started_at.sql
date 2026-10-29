-- 保存当前或最近一轮执行的 UTC 开始时间，刷新后可恢复准确计时。
ALTER TABLE agent_conversation
  ADD COLUMN run_started_at TEXT;

UPDATE agent_conversation
SET run_started_at = updated_at
WHERE status = 'running' AND run_started_at IS NULL;
