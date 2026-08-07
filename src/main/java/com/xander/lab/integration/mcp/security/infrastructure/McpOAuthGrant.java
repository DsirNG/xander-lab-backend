package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable user consent grant for one public MCP OAuth client. */
@Data
@TableName("mcp_oauth_grant")
public class McpOAuthGrant {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private String clientId;
    private String scopes;
    private String status;
    private LocalDateTime grantedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
