package com.xander.lab.integration.mcp.csdn.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime configuration for the CSDN publisher. Cookie is supplied by environment only. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.csdn.platform")
public class CsdnProperties {
    private boolean enabled;
    private String baseUrl = "https://bizapi.csdn.net";
    private String apiKey = "203803574";
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private String credentialEncryptionKey;
    private int authorizationTimeoutSeconds = 180;
}
