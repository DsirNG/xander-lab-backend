CREATE TABLE IF NOT EXISTS `agent_skill` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户私有Skill主键',
  `user_id` BIGINT NOT NULL COMMENT 'Skill归属用户ID，关联sys_user.id',
  `skill_key` VARCHAR(80) NOT NULL COMMENT '用户命名的kebab-case稳定Skill标识',
  `version` INT NOT NULL COMMENT '同一Skill标识下递增的不可变版本号',
  `name` VARCHAR(120) NOT NULL COMMENT '面向用户和模型目录展示的Skill名称',
  `description` VARCHAR(500) NOT NULL COMMENT '提供给模型选择的受限摘要，不含完整指令',
  `instructions` TEXT NOT NULL COMMENT '模型经load_skill显式读取的受限Skill正文',
  `tool_names_json` VARCHAR(2000) NOT NULL COMMENT 'Skill可申请使用的已注册工具名称JSON数组',
  `status` VARCHAR(16) NOT NULL COMMENT 'Skill状态：ACTIVE可加载，ARCHIVED仅保留审计',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Skill版本创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Skill版本最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill_user_key_version` (`user_id`, `skill_key`, `version`),
  KEY `idx_agent_skill_user_status_key` (`user_id`, `status`, `skill_key`),
  CONSTRAINT `fk_agent_skill_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体用户私有Skill版本库';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260903_agent_skill');
