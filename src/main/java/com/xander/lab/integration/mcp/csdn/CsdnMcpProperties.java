package com.xander.lab.integration.mcp.csdn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mcp.csdn")
public class CsdnMcpProperties {
    private boolean enabled = false;
    private String serverToken;
    private String publicBaseUrl = "http://localhost:30002";
}
