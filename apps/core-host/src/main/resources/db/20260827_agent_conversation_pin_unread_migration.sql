-- 智能体会话列表：置顶分组 + “已生成”未读提醒。
-- Marker: 20260827_agent_conversation_pin_unread
--
-- 背景：会话列表需要区分“置顶/普通”两组，各组内按更新时间倒序；
-- 同时用户离开某个会话后，该会话本轮生成完成需要在列表上给出蓝点提醒，
-- 打开会话即视为已读。未读状态由 last_completed_at 与 last_read_at 比较得出，
-- 不额外存布尔值，避免多端阅读时状态漂移。
-- 手工执行；列/索引存在性检查保证重复执行安全。

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

-- 1. 置顶标记：列表排序的第一优先级。
SET @pinned_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_conversation'
    AND COLUMN_NAME = 'is_pinned'
);
SET @pinned_column_sql := IF(
  @pinned_column_exists = 0,
  'ALTER TABLE `agent_conversation` ADD COLUMN `is_pinned` TINYINT NOT NULL DEFAULT 0 COMMENT ''置顶标记：0=普通会话，1=置顶会话；列表先按置顶分组再按更新时间倒序'' AFTER `plan_json`',
  'SELECT 1'
);
PREPARE stmt_agent_conversation_pinned FROM @pinned_column_sql;
EXECUTE stmt_agent_conversation_pinned;
DEALLOCATE PREPARE stmt_agent_conversation_pinned;

-- 2. 最近阅读时间：只在用户真正打开会话时写入，写入时显式保留 updated_at 以免打乱排序。
SET @read_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_conversation'
    AND COLUMN_NAME = 'last_read_at'
);
SET @read_column_sql := IF(
  @read_column_exists = 0,
  'ALTER TABLE `agent_conversation` ADD COLUMN `last_read_at` DATETIME NULL COMMENT ''用户最近一次打开该会话的时间；早于 last_completed_at 表示有未读的生成结果'' AFTER `is_pinned`',
  'SELECT 1'
);
PREPARE stmt_agent_conversation_last_read FROM @read_column_sql;
EXECUTE stmt_agent_conversation_last_read;
DEALLOCATE PREPARE stmt_agent_conversation_last_read;

-- 3. 最近一轮到达终态的时间：成功收口、失败与中断收口都写入，是未读提醒的事实来源。
SET @completed_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_conversation'
    AND COLUMN_NAME = 'last_completed_at'
);
SET @completed_column_sql := IF(
  @completed_column_exists = 0,
  'ALTER TABLE `agent_conversation` ADD COLUMN `last_completed_at` DATETIME NULL COMMENT ''最近一轮执行到达终态（成功/失败/中断收口）的时间；与 last_read_at 比较得出未读提醒'' AFTER `last_read_at`',
  'SELECT 1'
);
PREPARE stmt_agent_conversation_last_completed FROM @completed_column_sql;
EXECUTE stmt_agent_conversation_last_completed;
DEALLOCATE PREPARE stmt_agent_conversation_last_completed;

-- 4. 列表查询按 (归属用户, 置顶, 更新时间) 取前 50 条，补一条覆盖该排序的索引。
SET @pinned_index_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_conversation'
    AND INDEX_NAME = 'idx_agent_conversation_user_pinned_updated'
);
SET @pinned_index_sql := IF(
  @pinned_index_exists = 0,
  'ALTER TABLE `agent_conversation` ADD KEY `idx_agent_conversation_user_pinned_updated` (`user_id`, `is_pinned`, `updated_at`)',
  'SELECT 1'
);
PREPARE stmt_agent_conversation_pinned_index FROM @pinned_index_sql;
EXECUTE stmt_agent_conversation_pinned_index;
DEALLOCATE PREPARE stmt_agent_conversation_pinned_index;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260827_agent_conversation_pin_unread');
