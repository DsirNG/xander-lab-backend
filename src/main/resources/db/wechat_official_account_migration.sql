CREATE TABLE IF NOT EXISTS `wechat_official_account_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '公众号凭据主键',
  `user_id` BIGINT NOT NULL COMMENT '应用用户 ID，凭据只归属于该用户',
  `app_id` VARCHAR(100) NOT NULL COMMENT '微信公众号 AppID，非密钥但属于账号配置',
  `encrypted_app_secret` VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密的公众号 AppSecret，绝不通过接口回传',
  `account_name` VARCHAR(128) NULL COMMENT '公众号名称，仅用于前端展示',
  `encrypted_access_token` VARCHAR(1024) NULL COMMENT 'AES-GCM 加密的微信 access_token，避免每次同步重复获取',
  `access_token_expires_at` DATETIME NULL COMMENT 'access_token 过期时间（UTC）',
  `status` VARCHAR(16) NOT NULL COMMENT '凭据状态：ACTIVE=可用，REVOKED=用户主动解除，EXPIRED=凭据失效',
  `last_validated_at` DATETIME NULL COMMENT '最近一次成功获取 access_token 的时间（UTC）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wechat_official_account_user` (`user_id`),
  KEY `idx_wechat_official_account_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按用户保存的微信公众号发布凭据';
