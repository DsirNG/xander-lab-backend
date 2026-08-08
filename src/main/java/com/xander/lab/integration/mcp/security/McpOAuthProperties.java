package com.xander.lab.integration.mcp.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the public OAuth authorization server used by remote MCP clients. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.oauth")
public class McpOAuthProperties {
    private boolean enabled = false;
    private String publicBaseUrl = "http://localhost:30002";
    private int authorizationRequestTtlSeconds = 600;
    private int authorizationCodeTtlSeconds = 300;
    private int accessTokenTtlSeconds = 3600;
    private int refreshTokenTtlSeconds = 2592000;
    private int rateLimitMaxRequests = 20;
    private int rateLimitWindowSeconds = 60;
}
