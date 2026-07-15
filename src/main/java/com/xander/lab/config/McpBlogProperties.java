package com.xander.lab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mcp.blog")
public class McpBlogProperties {
    private boolean enabled = false;
    private String serverToken;
    private String credentialEncryptionKey;
    private String publicBaseUrl = "http://localhost:30002";
}
