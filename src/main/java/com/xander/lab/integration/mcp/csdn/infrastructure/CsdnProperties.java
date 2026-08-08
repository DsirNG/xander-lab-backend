package com.xander.lab.integration.mcp.csdn.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime configuration for the CSDN publisher. Credentials are supplied by environment only. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.csdn.platform")
public class CsdnProperties {
    private boolean enabled;
    private String baseUrl = "https://bizapi.csdn.net";
    /** CSDN partner API key, sourced from the CSDN_API_KEY environment variable. */
    private String apiKey;
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private String credentialEncryptionKey;
    private int authorizationTimeoutSeconds = 35;
    /** How often the background sweep checks for expired authorization sessions, in milliseconds. */
    private long cleanupIntervalMs = 15_000;
    /** Web-console HMAC signing secret embedded in CSDN's mp_v3 frontend; overridable so it can be rotated. */
    private String webConsoleSecret = "9znpamsyl2c7cdrr9sas0le9vbc3r6ba";
}
