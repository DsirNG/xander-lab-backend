-- DinQorAI microservice migration — GRANT script for `dinqor_migration` (V9 §63 / §66).
--
-- GENERATED FILE. Do not hand-edit.
--   source : docs/table-ownership.yaml
--   tool   : scripts/migration/gen_grants.py
--
-- Effective wave: wave3+
--   Wave 0A: generate only, do not apply (V9 §6: 暂不强制拆进程)
--   Wave 1 : apply together with the first real split, in the SAME change window
--            that narrows core-host — otherwise ownership is a paper concept.
--
-- This account holds DML only. It must never receive DDL rights
-- (V9 §102 禁止项: 业务服务拿 DDL 权限).
--
-- Idempotent: safe to re-run.


-- The migration account is the ONLY account with DDL rights.
-- It must never be used as a runtime account by any service.

CREATE USER IF NOT EXISTS 'dinqor_migration'@'%' IDENTIFIED BY '<from deploy secret>';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW, SHOW VIEW
  ON `xander_lab`.* TO 'dinqor_migration'@'%';

-- Flyway needs to read its own history table and schema metadata.
GRANT SELECT ON `mysql`.* TO 'dinqor_migration'@'%';

FLUSH PRIVILEGES;
