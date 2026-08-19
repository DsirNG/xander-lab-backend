CREATE TABLE IF NOT EXISTS `juejin_platform_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭据主键',
  `user_id` BIGINT NOT NULL COMMENT '应用用户 ID，唯一归属当前用户',
  `encrypted_cookie` MEDIUMTEXT NOT NULL COMMENT 'AES-GCM 加密的掘金浏览器凭据包（Cookie 头、x-secsdk-csrf-token、aid、uuid、用户资料 JSON）',
  `juejin_username` VARCHAR(128) NULL COMMENT '掘金账号昵称，展示用',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE=可用；EXPIRED=登录态失效待重新授权',
  `authorized_at` DATETIME NOT NULL COMMENT '最近一次成功授权时间',
  `last_validated_at` DATETIME NOT NULL COMMENT '最近一次凭据有效性校验时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_juejin_credential_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='加密的按用户掘金授权凭据';
