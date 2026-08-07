package com.xander.lab.integration.mcp.dual;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the composite local-blog and CSDN MCP endpoint. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.dual")
public class McpDualProperties {
    private boolean enabled = false;
    private String serverToken;
    private String publicBaseUrl = "http://localhost:30002";
}
