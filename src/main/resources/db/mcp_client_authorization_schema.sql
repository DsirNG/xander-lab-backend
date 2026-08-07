CREATE TABLE IF NOT EXISTS `mcp_client_authorization` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'MCP client authorization primary key',
  `client_id` VARCHAR(128) NOT NULL COMMENT 'Stable opaque identifier configured in one MCP client installation',
  `client_secret_hash` CHAR(64) NOT NULL COMMENT 'SHA-256 hash of the client secret required on every MCP request',
  `user_id` BIGINT NOT NULL COMMENT 'Xander Lab user represented by this MCP client',
  `encrypted_tokens` MEDIUMTEXT NOT NULL COMMENT 'AES-GCM encrypted Xander Lab access and refresh token pair',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE permits MCP calls; REVOKED denies calls until reauthorized',
  `authorized_at` DATETIME NOT NULL COMMENT 'Most recent successful browser authorization time',
  `created_at` DATETIME NOT NULL COMMENT 'Authorization record creation time',
  `updated_at` DATETIME NOT NULL COMMENT 'Last authorization or token refresh time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_client_authorization_client` (`client_id`),
  KEY `idx_mcp_client_authorization_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='External MCP client authorization mapped to a Xander Lab user';
