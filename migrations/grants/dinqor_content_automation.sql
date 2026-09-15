-- DinQorAI microservice migration — GRANT script for `dinqor_content_automation` (V9 §63 / §66).
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


-- Tables owned by this account (17):
--   blog_agent_event
--   blog_agent_message
--   blog_agent_source
--   blog_agent_task
--   blog_agent_version
--   blog_category
--   blog_content_plan
--   blog_media_asset
--   blog_notification
--   blog_plan_run
--   blog_post
--   blog_post_tag
--   blog_post_view
--   blog_publish_record
--   blog_tag
--   email_reminder_task
--   shedlock

CREATE USER IF NOT EXISTS 'dinqor_content_automation'@'%' IDENTIFIED BY '<from deploy secret>';

GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_agent_event` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_agent_message` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_agent_source` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_agent_task` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_agent_version` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_category` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_content_plan` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_media_asset` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_notification` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_plan_run` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_post` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_post_tag` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_post_view` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_publish_record` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`blog_tag` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`email_reminder_task` TO 'dinqor_content_automation'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`shedlock` TO 'dinqor_content_automation'@'%';

FLUSH PRIVILEGES;
