package com.xander.lab.integration.mcp.juejin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the Juejin MCP endpoint. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.juejin")
public class JuejinMcpProperties {
    private boolean enabled = false;
}