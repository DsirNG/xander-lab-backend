-- V9 §33 / §37 / §38 / §39 / §40 — bounded refresh token history.
--
-- Owner: identity (logical) / dinqor-core-host (current deployment owner).
--
-- This table carries the FOUR-branch Reuse Detection decision and the two
-- idempotent-replay mechanisms. Getting the windows wrong is a security bug in
-- both directions, so they are spelled out explicitly.
--
-- ---------------------------------------------------------------------------
-- Three INDEPENDENT windows (V9 §33 — 不得互相推导)
-- ---------------------------------------------------------------------------
--   grace_expires_at                 幂等返回窗口（秒级，默认 25s）
--                                    决定「重放是否算 theft」
--   retained_until                   Reuse 判定窗口（小时级，默认 24h）
--                                    决定「还能不能识别为 theft」
--   successor_ciphertext_expires_at  密文物理清理窗口（秒级）
--                                    决定「密文何时可被清空」
--
-- Keeping only the grace window would make a genuine theft that happens 25
-- seconds later undetectable (V9 §40.5).
--
-- ---------------------------------------------------------------------------
-- retained_until 写入规则（V9 §40.2 — 必须显式实现）
-- ---------------------------------------------------------------------------
--   写入时机：RT(n) 轮换成功、写 history 行时
--   写入值：  retained_until = now() + retention      # 即 now() + 24h
--   不得留空、不得依赖后续任务回填
--   ⇒ 列定义 NOT NULL，且没有默认值，强制写入方显式提供。
--
-- ---------------------------------------------------------------------------
-- 三个清理依赖的索引（V9 §33「完整索引（不得省略）」）
-- ---------------------------------------------------------------------------
--   idx_refresh_history_retained_until        → AuthRefreshHistoryCleanup   (§42)
--   idx_refresh_history_ciphertext_expires    → AuthRefreshCiphertextCleanup(§43)
--   idx_refresh_history_sid_generation        → 每 sid 48 代条数上界        (§40.3)

CREATE TABLE IF NOT EXISTS `auth_refresh_token_history` (
  `token_id` VARCHAR(64) NOT NULL COMMENT '该次轮换中被消费掉的 refresh token 的 tokenId',
  `sid` VARCHAR(64) NOT NULL COMMENT '所属刷新会话，关联 auth_refresh_session.sid',
  `token_hash` VARCHAR(128) NOT NULL COMMENT '被消费 token secret 的哈希；用于判定「hash 匹配」分支',
  `generation` INT NOT NULL COMMENT '被消费 token 的轮换代次',
  `used_at` DATETIME(3) NOT NULL COMMENT '该 token 被成功消费的时间',
  `successor_token_id` VARCHAR(64) NULL COMMENT '轮换产生的后继 tokenId；grace 内重放时返回的始终是它',
  `grace_expires_at` DATETIME(3) NOT NULL COMMENT '幂等返回窗口截止时间；now <= 该值 → 返回同一个 successor，不判 theft',
  `retained_until` DATETIME(3) NOT NULL COMMENT 'Reuse 判定窗口截止时间（写入时 now()+24h）；超过后该行仍无效但不再触发 family theft alarm',
  `reuse_detected_at` DATETIME(3) NULL COMMENT '判定为真实 Reuse 的时间；非空表示该行已被识别为盗用',
  `successor_token_ciphertext` TEXT NULL COMMENT 'successor refresh token 的密文（AES/GCM）。grace 窗口内必须保持可恢复，物理清空由 §43 任务负责，业务代码以时间判断为准',
  `successor_ciphertext_expires_at` DATETIME(3) NULL COMMENT '密文逻辑过期时间；now > 该值后禁止解密/返回，与物理清空解耦',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`token_id`),
  KEY `idx_refresh_history_sid_generation` (`sid`, `generation`),
  KEY `idx_refresh_history_retained_until` (`retained_until`),
  KEY `idx_refresh_history_ciphertext_expires` (`successor_ciphertext_expires_at`),
  KEY `idx_refresh_history_sid_used` (`sid`, `used_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='刷新令牌轮换历史（有界 Replay History）';
