-- 模型供应商表：管理台录入的 OpenAI 兼容模型网关。
-- Fresh-database baseline; safe to re-run (CREATE TABLE IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS `model_provider` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL COMMENT '供应商显示名称，管理台下拉与列表展示用，全局唯一',
  `vendor` VARCHAR(50) NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT '协议类型：OPENAI_COMPATIBLE=OpenAI 兼容 Responses/Images 网关',
  `base_url` VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容 API 基础地址，如 https://api.openai.com/v1',
  `encrypted_api_key` VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密的 API 密钥（密钥来自 MODEL_PROVIDER_ENCRYPTION_KEY，Base64 32 字节），绝不通过接口回传',
  `description` VARCHAR(500) NULL COMMENT '供应商备注，仅管理台展示',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用；停用的供应商不会被任何功能选中',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '管理台展示排序，数字越小越靠前',
  `created_at` DATETIME NOT NULL COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL COMMENT '最近更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型供应商管理（管理台配置的 OpenAI 兼容网关）';