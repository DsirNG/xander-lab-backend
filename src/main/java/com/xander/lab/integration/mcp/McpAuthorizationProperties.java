package com.xander.lab.integration.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration shared by all MCP endpoints. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.authorization")
public class McpAuthorizationProperties {
    /** Base64 encoded 32-byte AES-GCM encryption key. */
    private String credentialEncryptionKey;
    /** Browser authorization request lifetime in seconds. */
    private long requestTtlSeconds = 600;
}
