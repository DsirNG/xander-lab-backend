-- B7：invocation 的副作用执行阶段（Effect State）。
--
-- 依赖 20260925_agent_effect_migration.sql（agent_effect 表 + agent_tool_invocation.effect_id）。
--
-- 解决的问题（§1.3 缺口 R2）：管道第 4 段对取消的处理是"把所有调用结算完再抛"，这在
-- "取消发生得足够早"时是对的。但工具内的取消检查点是**协作式**的，只能落在工具自己选的位置。
-- 如果写已提交、中断发生在提交之后的下一个检查点：
--
--   远端写入已提交   →  agent_tool_invocation.status = CANCELLED
--   对账器扫描范围   →  只有 UNKNOWN，**永远不会查证这一条**
--   模型看到的历史   →  {"cancelled": true}，可能重新发起
--   结果             →  **第二次写入**
--
-- 也就是说，"这一次调用到底越没越过副作用边界"必须是一个**持久事实**，而不是靠状态推断。
-- 三列各自回答一个问题：
--
--   effect_state        这次调用走到哪一步了（NONE/PREPARED/DISPATCHED/CONFIRMED）
--   effect_dispatched_at 什么时候把请求交出去的
--   effect_confirmed_at  什么时候拿到远端成功凭据的
--
-- 注意与 agent_effect 的分工：那张表回答"这是不是同一个业务动作"（**静态**、按意图周期、
-- 跨多次 invocation 不变）；这三列回答"这一次调用走到哪一步了"（**动态**、按 invocation）。
-- 两者混为一谈正是被废弃的 AgentTool.effectCommitted() 犯的错。

ALTER TABLE `agent_tool_invocation`
  ADD COLUMN `effect_state` VARCHAR(16) NOT NULL DEFAULT 'NONE'
    COMMENT '副作用执行阶段：NONE未进入副作用路径(只读工具，或迁移前的存量行——**判读须连effect_id一起看**)/PREPARED意图已落库但请求尚未发出/DISPATCHED请求已交给工具(可能已在远端生效)/CONFIRMED已拿到远端成功凭据' AFTER `status`,
  ADD COLUMN `effect_dispatched_at` DATETIME NULL
    COMMENT '标记为DISPATCHED的时间。**必须写在把请求交给工具之前**：崩在"标记"与"发送"之间只会产生一个假DISPATCHED（多一次无谓对账，安全方向），反过来则会漏掉一次真实写入（要命方向）' AFTER `effect_state`,
  ADD COLUMN `effect_confirmed_at` DATETIME NULL
    COMMENT '拿到远端成功凭据的时间；与 invocation 的SUCCEEDED同事务落库' AFTER `effect_dispatched_at`;

-- 存量行保持默认值 'NONE'。
--
-- 刻意**不回填**：存量 invocation 里既有"发出去了"也有"没发出去"，而区分它们所需的证据
-- （当时的 effect_state）从来没有被记录过。凭 status 猜会把已提交的写判成"没发出"，
-- 那正是这次要消灭的漏判。
--
-- 所以读侧必须**连 effect_id 一起看**，而不是只凭这个默认值：
--
--   没有 effect_id  →  这次调用压根没有远端写（只读工具 / B6 之前的存量行）→ 可安全终结
--   有 effect_id    →  可能已经发出去了（B6 与 B7 之间的部署窗口）→ 进对账
--
-- 保守方向是"当作可能已发出"（多查一次对账），不是"当作没发出"。
-- 实现见 AgentExecutionJournal#settleInFlightWork 的两条 UPDATE。

-- 本批**不加**索引：effect_state 只出现在"按 run_id + status 取在途调用"之后的过滤条件里，
-- 那个查询已经由 idx_agent_tool_invocation_run_created (run_id, created_at) 收窄到个位数行，
-- 再为它单独建索引是纯开销。将来若出现"按 effect_state 全表扫"的排障查询，再补。

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260925_agent_effect_state');
