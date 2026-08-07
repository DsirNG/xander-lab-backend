package com.xander.lab.integration.mcp.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable authorization linking one MCP client installation to one application user. */
@Data
@TableName("mcp_client_authorization")
public class McpClientAuthorization {
    @TableId(type = IdType.AUTO) private Long id;
    private String clientId;
    private String clientSecretHash;
    private Long userId;
    private String encryptedTokens;
    private String status;
    private LocalDateTime authorizedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
