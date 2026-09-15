-- DinQorAI microservice migration — GRANT script for `dinqor_agent` (V9 §63 / §66).
--
-- GENERATED FILE. Do not hand-edit.
--   source : docs/table-ownership.yaml
--   tool   : scripts/migration/gen_grants.py
--
-- Effective wave: wave2
--   Wave 0A: generate only, do not apply (V9 §6: 暂不强制拆进程)
--   Wave 1 : apply together with the first real split, in the SAME change window
--            that narrows core-host — otherwise ownership is a paper concept.
--
-- This account holds DML only. It must never receive DDL rights
-- (V9 §102 禁止项: 业务服务拿 DDL 权限).
--
-- Idempotent: safe to re-run.


-- Tables owned by this account (11):
--   agent_approval
--   agent_conversation
--   agent_event
--   agent_mcp_server
--   agent_memory
--   agent_message
--   agent_run
--   agent_skill
--   agent_step
--   agent_tool_invocation
--   shedlock

CREATE USER IF NOT EXISTS 'dinqor_agent'@'%' IDENTIFIED BY '<from deploy secret>';

GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_approval` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_conversation` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_event` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_mcp_server` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_memory` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_message` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_run` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_skill` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_step` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`agent_tool_invocation` TO 'dinqor_agent'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`shedlock` TO 'dinqor_agent'@'%';

FLUSH PRIVILEGES;
