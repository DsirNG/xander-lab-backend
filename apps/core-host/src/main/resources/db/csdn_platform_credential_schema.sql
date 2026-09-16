CREATE TABLE IF NOT EXISTS `csdn_platform_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Credential primary key',
  `user_id` BIGINT NOT NULL COMMENT 'Application user ID',
  `encrypted_cookie` MEDIUMTEXT NOT NULL COMMENT 'AES-GCM encrypted CSDN cookie header',
  `csdn_username` VARCHAR(128) NULL COMMENT 'CSDN account display identifier',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `authorized_at` DATETIME NOT NULL COMMENT 'Last successful authorization time',
  `last_validated_at` DATETIME NOT NULL COMMENT 'Last credential validation time',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csdn_credential_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Encrypted per-user CSDN authorization';
