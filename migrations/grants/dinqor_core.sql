-- DinQorAI microservice migration — GRANT script for `dinqor_core` (V9 §63 / §66).
--
-- GENERATED FILE. Do not hand-edit.
--   source : docs/table-ownership.yaml
--   tool   : scripts/migration/gen_grants.py
--
-- Effective wave: wave1
--   Wave 0A: generate only, do not apply (V9 §6: 暂不强制拆进程)
--   Wave 1 : apply together with the first real split, in the SAME change window
--            that narrows core-host — otherwise ownership is a paper concept.
--
-- Runtime-service accounts hold DML only and must never receive DDL rights
-- (V9 §102 禁止项: 业务服务拿 DDL 权限). The migration-runner account is the
-- controlled DDL exception and is emitted explicitly below.
--
-- Idempotent: safe to re-run.


-- Tables owned by this account (37):
--   account_security_state
--   auth_refresh_session
--   auth_refresh_token_history
--   billing_model_price
--   component_category
--   component_detail_page
--   component_item
--   component_scenario
--   feature_model_config
--   flow_ai_task
--   flow_canvas
--   flow_project
--   flow_snapshot
--   img2three_task
--   knowledge_chapter
--   knowledge_file
--   knowledge_file_content
--   knowledge_file_tag
--   knowledge_folder
--   knowledge_quiz_attempt
--   knowledge_quiz_session
--   knowledge_structure_node
--   knowledge_tag
--   knowledge_upload_chunk
--   knowledge_upload_session
--   model_provider
--   points_ledger
--   recitation_attempt
--   recitation_material
--   security_event_outbox
--   shedlock
--   studio_project
--   sys_user
--   sys_user_points
--   virtual_pay_order
--   virtual_pay_settings
--   wechat_credential

CREATE USER IF NOT EXISTS 'dinqor_core'@'%' IDENTIFIED BY '<from deploy secret>';

GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`account_security_state` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`auth_refresh_session` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`auth_refresh_token_history` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`billing_model_price` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`component_category` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`component_detail_page` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`component_item` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`component_scenario` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`feature_model_config` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`flow_ai_task` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`flow_canvas` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`flow_project` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`flow_snapshot` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`img2three_task` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_chapter` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_file` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_file_content` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_file_tag` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_folder` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_quiz_attempt` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_quiz_session` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_structure_node` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_tag` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_upload_chunk` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`knowledge_upload_session` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`model_provider` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`points_ledger` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`recitation_attempt` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`recitation_material` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`security_event_outbox` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`shedlock` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`studio_project` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`sys_user` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`sys_user_points` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`virtual_pay_order` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`virtual_pay_settings` TO 'dinqor_core'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`wechat_credential` TO 'dinqor_core'@'%';

FLUSH PRIVILEGES;
