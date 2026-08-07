package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persisted public OAuth client registered by an MCP host. */
@Data
@TableName("mcp_oauth_client")
public class McpOAuthClient {
    @TableId(type = IdType.AUTO) private Long id;
    private String clientId;
    private String clientName;
    private String redirectUris;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
