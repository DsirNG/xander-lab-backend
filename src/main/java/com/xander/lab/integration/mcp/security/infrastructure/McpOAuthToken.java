package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Hash-only storage for short-lived MCP OAuth access and refresh tokens. */
@Data
@TableName("mcp_oauth_token")
public class McpOAuthToken {
    @TableId(type = IdType.AUTO) private Long id;
    private Long grantId;
    private String tokenHash;
    private String tokenType;
    private String tokenFamilyId;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
