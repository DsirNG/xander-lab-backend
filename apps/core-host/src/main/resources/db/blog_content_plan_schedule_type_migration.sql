-- 为自定义计划增加每日、每周、一次性三种真实调度类型及 AI 规划方向。
ALTER TABLE `blog_content_plan`
  ADD COLUMN `schedule_type` VARCHAR(16) NOT NULL DEFAULT 'DAILY'
    COMMENT '计划类型：DAILY 每日 / WEEKLY 每周 / ONCE 一次性' AFTER `tone`,
  ADD COLUMN `scheduled_date` DATE NULL
    COMMENT 'ONCE 的执行日期或 WEEKLY 的首次执行日期（计划时区本地日期）' AFTER `schedule_type`,
  ADD COLUMN `ai_option` VARCHAR(16) NOT NULL DEFAULT 'DEEP'
    COMMENT 'AI 规划方向：DEEP / PRACTICAL / NEWS / OPINION' AFTER `scheduled_date`,
  ADD COLUMN `knowledge_material_id` BIGINT NULL
    COMMENT '绑定的个人知识材料ID，关联 recitation_material.id，生成时作为上下文' AFTER `ai_option`,
  ADD COLUMN `auto_publish` TINYINT(1) NOT NULL DEFAULT 1
    COMMENT '生成后是否自动发布（0 保存本站草稿 / 1 发布并按配置同步）' AFTER `knowledge_material_id`;

UPDATE `blog_content_plan`
SET `schedule_type` = CASE WHEN `run_once` = 1 THEN 'ONCE' ELSE 'DAILY' END
WHERE `schedule_type` = 'DAILY';

INSERT IGNORE INTO `schema_migration` (`version`)
VALUES ('20260829_blog_content_plan_schedule_type');
