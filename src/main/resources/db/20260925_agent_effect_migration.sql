-- B6：逻辑副作用身份（Effect Identity）。
--
-- 依赖 agent_harness_schema.sql（agent_run / agent_tool_invocation / agent_conversation / sys_user）。
--
-- 解决的问题：`invocation_id` 每次调用都是新 UUID，而它被三个写工具当下游幂等键
-- （blog_agent_task / blog_content_plan / email_reminder 的 (user_id, client_request_id)）。
-- 于是"模型看到 cancelled 后重新发起同一个调用"会带一个新键，下游去重不命中，**真会执行第二次**。
--
-- `effect_id` 是**业务幂等身份**：跨多次 invocation 保持不变，是下游 client_request_id 的取值来源。
-- `invocation_id` 继续表示"这一次物理调用"，两者是不同层次的事实，不要合并。

CREATE TABLE IF NOT EXISTS `agent_effect` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '逻辑副作用主键',
  `effect_id` CHAR(36) NOT NULL COMMENT '业务幂等身份(UUID)，Runtime 铸造；跨多次 invocation 保持不变，是下游 client_request_id 的取值来源。与 invocation_id 不同层：那个标识"这一次物理调用"，这个标识"这一个业务动作"',
  `user_id` BIGINT NOT NULL COMMENT '归属用户ID，与下游唯一键 (user_id, client_request_id) 对齐；冗余保存以支持按用户审计与授权过滤',
  `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID，关联 agent_conversation.id',
  `run_id` BIGINT NOT NULL COMMENT '首次提出该副作用的执行记录ID，关联 agent_run.id；后续复用它的 invocation 属于同一个 Run（同 run_version、同 intent_epoch）',
  `intent_epoch` CHAR(36) NOT NULL COMMENT '所属用户意图周期标识，与 agent_run.intent_epoch 同源。采纳判定**只在本周期内进行**：跨周期的一律是新动作，否则用户真的要发第二条提醒时会被当成重试吞掉',
  `tool_name` VARCHAR(64) NOT NULL COMMENT '产生该副作用的工具名；采纳要求工具名相同',
  `args_fingerprint` CHAR(64) NOT NULL COMMENT '工具名与**归一化后**参数的SHA-256十六进制。它**参与采纳判定**（同周期+同工具+同指纹+创建于更早的步，四条同时满足才可采纳）与**漂移告警**，但**不是身份本身**——身份是随机的 effect_id。刻意不用参数哈希当身份：那会让"同义改写"和"合法改内容"两种情况都判错',
  `args_json` LONGTEXT NULL COMMENT '首次提出时的参数原文，用于漂移时的差异审计与人工排查；不保存凭据',
  `created_step_no` INT NOT NULL COMMENT '首次提出该副作用所在的模型步序号。采纳规则要求"创建于更早的 step"：同一批次里的两次相同调用是**两个有意的动作**（用户要发两条提醒），不该互相采纳',
  `external_id` VARCHAR(191) NULL COMMENT '远端返回的稳定业务标识；拿到它就等于拿到了对账句柄。长度 191 是为了在 utf8mb4 下可建索引',
  `status` VARCHAR(16) NOT NULL COMMENT '副作用生命周期：OPEN未确认完成(可被采纳)/SETTLED已有确认的invocation(不再采纳，否则会吞掉用户真的要做的第二次)/ABANDONED已放弃(如审批被拒)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `settled_at` DATETIME NULL COMMENT '进入SETTLED或ABANDONED的时间；OPEN期间为空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_effect_id` (`effect_id`),
  KEY `idx_agent_effect_adoption` (`intent_epoch`, `tool_name`, `status`),
  KEY `idx_agent_effect_run` (`run_id`),
  KEY `idx_agent_effect_user` (`user_id`),
  KEY `idx_agent_effect_conversation` (`conversation_id`),
  CONSTRAINT `fk_agent_effect_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_effect_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_effect_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体逻辑副作用身份：一次业务动作可能对应多次物理调用';

-- invocation 指向它所属的逻辑副作用。只读工具为 NULL（不建 Effect）。
--
-- 可空是刻意的：存量行没有 Effect，回填只能靠猜"哪几条 invocation 是同一个动作"，
-- 而那个猜测一旦错了就会把两个真实动作合并成一个——比留空危险得多。读侧必须容忍 NULL。
ALTER TABLE `agent_tool_invocation`
  ADD COLUMN `effect_id` CHAR(36) NULL
    COMMENT '所属逻辑副作用，关联 agent_effect.effect_id；只读工具为空。它是下游 client_request_id 的取值来源，与 invocation_id 不同层' AFTER `step_id`,
  ADD KEY `idx_agent_tool_invocation_effect` (`effect_id`);

-- 本批**不加** effect_state / effect_dispatched_at / effect_confirmed_at：
-- 那三列回答的是"这个副作用走到哪一步了"（动态、按 invocation），属于 B7；
-- 本表回答的是"这是不是同一个业务动作"（静态、按意图周期）。两者混在一批里会让
-- "要不要建 Effect"和"Effect 走到哪一步"这两个问题在代码里也糊在一起——那正是
-- 被废弃的 AgentTool.effectCommitted() 犯的错。
--
-- → 那三列已由 **20260925_agent_effect_state_migration.sql（B7）** 补上。这里保留原文
--   而不是改写，是为了留下"当时为什么把它们分开"这个判断本身。

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260925_agent_effect');
