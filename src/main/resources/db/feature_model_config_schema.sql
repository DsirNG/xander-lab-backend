-- 功能模型配置表：管理台为每个功能指定主供应商/主模型与兜底供应商/兜底模型。
-- Fresh-database baseline; safe to re-run (CREATE TABLE IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS `feature_model_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `feature_key` VARCHAR(50) NOT NULL COMMENT '功能标识：agent=智能体对话、blog_agent=博客生成、blog_agent_image=博客插图、img2three_vision=图生3D 视觉重建',
  `primary_provider_id` BIGINT NULL COMMENT '主供应商 model_provider.id；为空表示该功能未配置主模型',
  `primary_model` VARCHAR(100) NULL COMMENT '主供应商使用的主模型名',
  `primary_api_style` VARCHAR(30) NULL COMMENT '主槽位接口风格：IMAGES_GENERATIONS=/images/generations、CHAT_COMPLETIONS=/chat/completions（messages+modalities）、CHAT_PROMPT=/chat/completions（顶层 prompt，聚合网关如 okinto）、RESPONSES=/responses；为空按功能取默认',
  `fallback_provider_id` BIGINT NULL COMMENT '兜底供应商 model_provider.id；主供应商请求重试耗尽后切换',
  `fallback_model` VARCHAR(100) NULL COMMENT '兜底供应商使用的兜底模型名',
  `fallback_api_style` VARCHAR(30) NULL COMMENT '兜底槽位接口风格：IMAGES_GENERATIONS=/images/generations、CHAT_COMPLETIONS=/chat/completions（messages+modalities）、CHAT_PROMPT=/chat/completions（顶层 prompt，聚合网关如 okinto）、RESPONSES=/responses；为空按功能取默认',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=启用管理台配置（优先于环境变量） 0=回退到环境变量配置',
  `updated_at` DATETIME NOT NULL COMMENT '最近更新时间（创建时与创建时间一致）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_model_config_feature` (`feature_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='各功能使用的模型与兜底模型配置（管理台维护）';