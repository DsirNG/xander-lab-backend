-- B4：预算快照 + 活跃时长记账。
--
-- 依赖 agent_harness_schema.sql（agent_run 已存在）。
--
-- 列位置说明：计划 §2.3 的示例写的是 `AFTER intent_epoch`，但 intent_epoch 属于 B5，
-- 本批还没落地。列顺序不影响任何语义（代码按列名读写），这里改挂在 execution_snapshot_json 之后，
-- 等 B5 加 intent_epoch 时它会自然排在预算三列前面。

ALTER TABLE `agent_run`
  ADD COLUMN `budget_json` LONGTEXT NULL
    COMMENT '本轮冻结的资源上限快照（RunBudget 的 JSON）。claim 那一刻由配置折算写入，整个 Run 只读它；运行期改配置不得影响已开始的 Run，否则"这次为什么超预算"无法复现' AFTER `execution_snapshot_json`,
  ADD COLUMN `active_duration_ms` BIGINT NOT NULL DEFAULT 0
    COMMENT '累计活跃时长(毫秒)：只累加RUNNING期间，等待审批/暂停/中断期间不累加。按墙钟算会让"10:00发起、10:01转待审批、13:00批准"的Run在恢复瞬间就判超时，而它实际只跑了一分钟' AFTER `budget_json`,
  ADD COLUMN `last_running_at` DATETIME NULL
    COMMENT '最近一次进入RUNNING的时刻(UTC)；离开RUNNING或崩溃恢复时用它结算一段活跃时长并置NULL。非NULL表示"此刻正在RUNNING"' AFTER `active_duration_ms`;

-- 修正三处与实现不符的列注释。
--
-- 这三列的原注释都写着"预留…第一阶段为空"，但实现里 `AgentExecutionJournal.recordSkillOffer`
-- 在每一轮开始时就把它们写满了（见 `AgentSkillOffer.intentJson/skillsJson/executionSnapshotJson`）。
-- 注释说"为空"而实现写值，比没有注释更危险：后来者会照着注释判断"这列没数据，不用管"，
-- 于是一次排查方向从一开始就是错的。
ALTER TABLE `agent_run`
  MODIFY COLUMN `intent_json` LONGTEXT NULL
    COMMENT '当轮技能路由机制快照（JSON）：记录"怎么决定的"而非"决定了什么"，如 {"routing":"model-loaded-catalog","catalogSize":N}。服务端已不再按关键词猜意图，故不含置信度；不得保存原始用户提示词',
  MODIFY COLUMN `skills_json` LONGTEXT NULL
    COMMENT '当轮技能供给快照（JSON）：目录里提供了哪些技能(id/version/source)与当时的工具表。用于排查"模型当时能读到哪些技能"',
  MODIFY COLUMN `execution_snapshot_json` LONGTEXT NULL
    COMMENT '当轮技能供给规模摘要（JSON）：{"catalogSize":N,"toolCount":M}。早期注释称其为"模型路由与预算快照"，实际从未如此——预算快照见 budget_json';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260925_agent_run_budget');
