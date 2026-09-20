-- DinQorAI microservice migration — GRANT script for `dinqor_integration` (V9 §63 / §66).
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
-- Runtime-service accounts hold DML only and must never receive DDL rights
-- (V9 §102 禁止项: 业务服务拿 DDL 权限). The migration-runner account is the
-- controlled DDL exception and is emitted explicitly below.
--
-- Idempotent: safe to re-run.


-- Tables owned by this account (7):
--   csdn_platform_credential
--   juejin_category
--   juejin_platform_credential
--   juejin_tag
--   mcp_oauth_client
--   mcp_oauth_grant
--   mcp_oauth_token

CREATE USER IF NOT EXISTS 'dinqor_integration'@'%' IDENTIFIED BY '<from deploy secret>';

GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`csdn_platform_credential` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`juejin_category` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`juejin_platform_credential` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`juejin_tag` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`mcp_oauth_client` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`mcp_oauth_grant` TO 'dinqor_integration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`mcp_oauth_token` TO 'dinqor_integration'@'%';

FLUSH PRIVILEGES;
