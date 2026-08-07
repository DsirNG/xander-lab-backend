-- Apply this migration once to an existing database before enabling MCP OAuth.
CREATE TABLE `mcp_oauth_client` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'OAuth public client primary key',
  `client_id` VARCHAR(128) NOT NULL COMMENT 'Dynamic registration generated public OAuth client identifier',
  `client_name` VARCHAR(120) NOT NULL COMMENT 'Human-readable MCP host name shown during user consent',
  `redirect_uris` TEXT NOT NULL COMMENT 'JSON array of exact registered HTTPS or loopback redirect URIs',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE accepts OAuth flows; REVOKED or DISABLED rejects new grants',
  `created_at` DATETIME NOT NULL COMMENT 'Client registration creation time',
  `updated_at` DATETIME NOT NULL COMMENT 'Last registration metadata update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_oauth_client_client_id` (`client_id`),
  KEY `idx_mcp_oauth_client_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Public OAuth clients dynamically registered by remote MCP hosts';

CREATE TABLE `mcp_oauth_grant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'OAuth user consent grant primary key',
  `user_id` BIGINT NOT NULL COMMENT 'Xander Lab user who approved the MCP client',
  `client_id` VARCHAR(128) NOT NULL COMMENT 'Registered OAuth client identifier receiving this grant',
  `scopes` VARCHAR(512) NOT NULL COMMENT 'Space-delimited approved MCP capability scopes',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE permits token use; REVOKED invalidates all issued tokens',
  `granted_at` DATETIME NOT NULL COMMENT 'Most recent user approval time',
  `revoked_at` DATETIME NULL COMMENT 'User revocation time when status is REVOKED',
  `created_at` DATETIME NOT NULL COMMENT 'Grant creation time',
  `updated_at` DATETIME NOT NULL COMMENT 'Last consent or lifecycle update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_oauth_grant_user_client` (`user_id`, `client_id`),
  KEY `idx_mcp_oauth_grant_user_status` (`user_id`, `status`),
  KEY `idx_mcp_oauth_grant_client_status` (`client_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-user OAuth consent grants for remote MCP clients';

CREATE TABLE `mcp_oauth_token` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Hash-only OAuth token primary key',
  `grant_id` BIGINT NOT NULL COMMENT 'Consent grant that owns this token',
  `token_hash` CHAR(43) NOT NULL COMMENT 'Base64URL SHA-256 hash of access or refresh token; raw token is never stored',
  `token_type` VARCHAR(16) NOT NULL COMMENT 'ACCESS for MCP bearer calls or REFRESH for rotating token exchange',
  `token_family_id` VARCHAR(64) NOT NULL COMMENT 'Refresh-token rotation family identifier for replay containment',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE is usable; USED, REVOKED, or EXPIRED is rejected',
  `expires_at` DATETIME NOT NULL COMMENT 'Token expiration time in server UTC',
  `used_at` DATETIME NULL COMMENT 'Refresh token consumption time after rotation',
  `revoked_at` DATETIME NULL COMMENT 'Explicit revocation time',
  `created_at` DATETIME NOT NULL COMMENT 'Token issuance time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_oauth_token_hash` (`token_hash`),
  KEY `idx_mcp_oauth_token_grant_status` (`grant_id`, `status`),
  KEY `idx_mcp_oauth_token_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Hashed OAuth access and refresh token lifecycle for remote MCP';
