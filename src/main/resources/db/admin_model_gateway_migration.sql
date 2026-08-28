-- 管理台模型网关：为既有部署补建 model_provider / feature_model_config 两张表。
-- Apply manually. Safe to re-run via migration marker; guarded CREATE TABLE keeps it idempotent.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移应用时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

SET @admin_model_gateway_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260815_admin_model_gateway'
);

SET @sql_model_provider = IF(
  @admin_model_gateway_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_provider'
  ),
  'CREATE TABLE `model_provider` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL COMMENT ''供应商显示名称，管理台下拉与列表展示用，全局唯一'',
    `vendor` VARCHAR(50) NOT NULL DEFAULT ''OPENAI_COMPATIBLE'' COMMENT ''协议类型：OPENAI_COMPATIBLE=OpenAI 兼容 Responses/Images 网关'',
    `base_url` VARCHAR(255) NOT NULL COMMENT ''OpenAI 兼容 API 基础地址，如 https://api.openai.com/v1'',
    `encrypted_api_key` VARCHAR(512) NOT NULL COMMENT ''AES-GCM 加密的 API 密钥（密钥来自 MODEL_PROVIDER_ENCRYPTION_KEY，Base64 32 字节），绝不通过接口回传'',
    `description` VARCHAR(500) NULL COMMENT ''供应商备注，仅管理台展示'',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=启用 0=停用；停用的供应商不会被任何功能选中'',
    `priority` INT NOT NULL DEFAULT 0 COMMENT ''管理台展示排序，数字越小越靠前'',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''最近更新时间'',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_provider_name` (`name`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''模型供应商管理（管理台配置的 OpenAI 兼容网关）''',
  'SELECT 1'
);
PREPARE stmt_model_provider FROM @sql_model_provider;
EXECUTE stmt_model_provider;
DEALLOCATE PREPARE stmt_model_provider;

SET @sql_feature_model_config = IF(
  @admin_model_gateway_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feature_model_config'
  ),
  'CREATE TABLE `feature_model_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `feature_key` VARCHAR(50) NOT NULL COMMENT ''功能标识：agent=智能体对话、blog_agent=博客生成、blog_agent_image=博客插图、img2three_vision=图生3D 视觉重建'',
    `primary_provider_id` BIGINT NULL COMMENT ''主供应商 model_provider.id；为空表示该功能未配置主模型'',
    `primary_model` VARCHAR(100) NULL COMMENT ''主供应商使用的主模型名'',
    `fallback_provider_id` BIGINT NULL COMMENT ''兜底供应商 model_provider.id；主供应商请求重试耗尽后切换'',
    `fallback_model` VARCHAR(100) NULL COMMENT ''兜底供应商使用的兜底模型名'',
    `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=启用管理台配置（优先于环境变量） 0=回退到环境变量配置'',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''最近更新时间（创建时与创建时间一致）'',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feature_model_config_feature` (`feature_key`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''各功能使用的模型与兜底模型配置（管理台维护）''',
  'SELECT 1'
);
PREPARE stmt_feature_model_config FROM @sql_feature_model_config;
EXECUTE stmt_feature_model_config;
DEALLOCATE PREPARE stmt_feature_model_config;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260815_admin_model_gateway');

COMMIT;
