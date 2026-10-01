-- 收口 Agent Runtime 的状态机语义（Level 4 / B3）。
--
-- 1) `agent_run.status` 是**权威**，扩到九态；
-- 2) `agent_conversation.status` 是它的**投影**，补三个新投影值；
-- 3) 顺带修掉一个真实的长度缺陷：会话状态列是 VARCHAR(16)，而列注释里承诺的
--    `awaiting_approval` 有 17 个字符 —— 在 MySQL 严格模式（8.x 默认
--    STRICT_TRANS_TABLES）下这条写入会直接报 1406 Data too long，
--    非严格模式下会被截成 `awaiting_approva`，于是之后所有
--    `WHERE status = 'awaiting_approval'` 的条件更新都命中 0 行，审批恢复静默失效。
--    所以这里一并放宽到 24，与 `agent_run.status` 对齐。

ALTER TABLE `agent_run`
  MODIFY COLUMN `status` VARCHAR(24) NOT NULL
  COMMENT '运行状态（权威，九态）：CREATED已落库未开始（尚无生产者）/RUNNING执行中/WAITING_APPROVAL有具体调用等待属主决定/PAUSED用户主动暂停（尚无生产者）/INTERRUPTED持有者进程消失未收口/BUDGET_EXHAUSTED预算耗尽可继续/COMPLETED正常收口/CANCELLED用户请求停止后收口/FAILED异常收口；终态只有 COMPLETED/CANCELLED/FAILED，terminal→不再迁移';

ALTER TABLE `agent_conversation`
  MODIFY COLUMN `status` VARCHAR(24) NOT NULL DEFAULT 'ready'
  COMMENT '会话状态（agent_run.status 的投影，供前端契约与 claim 前置条件使用）：ready可开始新一轮/running一轮执行中/awaiting_approval等待用户审批/paused用户主动暂停/interrupted持有者进程消失可继续/budget_exhausted预算耗尽可继续/failed执行失败；COMPLETED 与 CANCELLED 都投影为 ready';

-- 存量数据不回溯。
--
-- 以前 `recoverStale` 把"持有者进程消失"写成 FAILED，与业务失败在存量里本来就分不开；
-- 凭猜测改写会把审计数据弄错，所以保留原值，新写入由 `AgentRunLifecycle.recoverStale`
-- 落 INTERRUPTED。取消路径同理：存量里取消也走 COMPLETED，同样不可区分。

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260925_agent_run_status_vocabulary');
