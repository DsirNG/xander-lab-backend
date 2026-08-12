-- Agent conversation cancel-request column. Apply manually once.
ALTER TABLE `agent_conversation`
  ADD COLUMN `cancel_requested` TINYINT NOT NULL DEFAULT 0
    COMMENT '用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮执行开始时复位'
    AFTER `error_message`;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_agent_cancel_request');
