-- 智能体出站 MCP 客户端：允许本平台连接第三方 MCP 服务器，把远端工具并进智能体工具面。
-- 与 mcp_oauth_schema.sql 的方向相反：那份是本平台作为 MCP 服务端接受外部宿主连接，
-- 这份是本平台作为客户端主动连出去。两者互不依赖。
CREATE TABLE IF NOT EXISTS `agent_mcp_server` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '远端MCP服务器配置主键',
  `owner_user_id` BIGINT NOT NULL DEFAULT 0 COMMENT '归属用户ID，关联sys_user.id；0表示平台级配置由管理员维护并对全部用户可用，故不设外键',
  `server_key` VARCHAR(20) NOT NULL COMMENT '服务器稳定标识，用于拼装工具名前缀mcp__<server_key>__<tool>，限2-20位小写kebab-case',
  `display_name` VARCHAR(120) NOT NULL COMMENT '面向用户的服务器展示名',
  `endpoint_url` VARCHAR(500) NOT NULL COMMENT '远端MCP Streamable HTTP端点地址，仅允许无凭据的HTTPS且须解析到公网地址',
  `headers_encrypted` TEXT NULL COMMENT '附加请求头JSON的AES-GCM密文，用于存放远端服务器要求的Authorization等凭据；明文不落库',
  `enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用：1时该服务器已探测到的工具进入智能体工具面，0时完全不出现',
  `tools_json` LONGTEXT NULL COMMENT '上次探测成功的远端工具目录缓存；渲染工具面时读这里，避免每一轮模型调用都发起网络请求',
  `last_status` VARCHAR(16) NULL COMMENT '上次连接结果：OK成功/FAILED失败；从未连接时为空',
  `last_error` VARCHAR(500) NULL COMMENT '上次连接失败的安全摘要，不保存堆栈、响应正文或凭据',
  `last_connected_at` DATETIME NULL COMMENT '上次连接成功并取回工具目录的时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp_server_owner_key` (`owner_user_id`, `server_key`),
  KEY `idx_agent_mcp_server_enabled` (`enabled`, `owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体可连接的远端MCP服务器配置';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260914_agent_mcp_client');
